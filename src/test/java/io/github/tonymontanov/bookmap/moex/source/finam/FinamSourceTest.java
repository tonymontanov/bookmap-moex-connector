package io.github.tonymontanov.bookmap.moex.source.finam;

import static io.github.tonymontanov.bookmap.moex.source.finam.FinamBookMapperTest.decimal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import grpc.tradeapi.v1.Side;
import grpc.tradeapi.v1.assets.AssetsServiceGrpc;
import grpc.tradeapi.v1.assets.GetAssetRequest;
import grpc.tradeapi.v1.assets.GetAssetResponse;
import grpc.tradeapi.v1.auth.AuthRequest;
import grpc.tradeapi.v1.auth.AuthResponse;
import grpc.tradeapi.v1.auth.AuthServiceGrpc;
import grpc.tradeapi.v1.auth.MDPermission;
import grpc.tradeapi.v1.auth.TokenDetailsRequest;
import grpc.tradeapi.v1.auth.TokenDetailsResponse;
import grpc.tradeapi.v1.marketdata.MarketDataServiceGrpc;
import grpc.tradeapi.v1.marketdata.StreamOrderBook;
import grpc.tradeapi.v1.marketdata.StreamOrderBook.Row;
import grpc.tradeapi.v1.marketdata.StreamOrderBook.Row.Action;
import grpc.tradeapi.v1.marketdata.SubscribeLatestTradesRequest;
import grpc.tradeapi.v1.marketdata.SubscribeLatestTradesResponse;
import grpc.tradeapi.v1.marketdata.SubscribeOrderBookRequest;
import grpc.tradeapi.v1.marketdata.SubscribeOrderBookResponse;
import grpc.tradeapi.v1.marketdata.Trade;
import io.github.tonymontanov.bookmap.moex.feed.InstrumentSpec;
import io.github.tonymontanov.bookmap.moex.feed.SourceException;
import io.github.tonymontanov.bookmap.moex.feed.Subscription;
import io.github.tonymontanov.bookmap.moex.feed.SymbolQuery;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link FinamSource} против поддельного шлюза в процессе: вход, справочник, стримы и JWT в заголовке. */
class FinamSourceTest {

    private static final String SECRET = "good-secret";
    private static final String JWT = "jwt-1";
    private static final String ACCOUNT = "A-1";
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final AtomicInteger authCalls = new AtomicInteger();
    private final Set<String> unauthorizedCalls = ConcurrentHashMap.newKeySet();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void startGateway() throws Exception {
        String name = InProcessServerBuilder.generateName();
        ServerInterceptor requireJwt = new RequireJwt();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(new FakeAuth())
                .addService(ServerInterceptors.intercept(new FakeAssets(), requireJwt))
                .addService(ServerInterceptors.intercept(new FakeMarketData(), requireJwt))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void stopGateway() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void resolvesFuturesAndSharesWithAccountFromToken() throws Exception {
        try (FinamSource source = new FinamSource(channel, SECRET, "")) {
            source.connect();

            InstrumentSpec futures = source.resolve(new SymbolQuery("SiZ6", "", ""));
            InstrumentSpec shares = source.resolve(new SymbolQuery("SBER", "", ""));

            assertEquals("SiZ6@RTSX", futures.alias());
            assertEquals(0, BigDecimal.ONE.compareTo(futures.priceStep()));
            assertEquals("SBER@MISX", shares.alias());
            assertEquals(0, new BigDecimal("0.01").compareTo(shares.priceStep()));
        }
        assertEquals(1, authCalls.get(), "JWT получен один раз и переиспользован");
        assertEquals(Set.of(), unauthorizedCalls);
    }

    @Test
    void unknownInstrumentIsNotFound() throws Exception {
        try (FinamSource source = new FinamSource(channel, SECRET, "")) {
            source.connect();

            SourceException error = assertThrows(SourceException.class,
                    () -> source.resolve(new SymbolQuery("NOPE", "", "")));

            assertEquals(SourceException.Reason.NOT_FOUND, error.reason());
            assertTrue(error.getMessage().contains("NOPE@RTSX / NOPE@MISX"), error.getMessage());
        }
    }

    @Test
    void wrongSecretFailsLoginAsAuth() {
        try (FinamSource source = new FinamSource(channel, "bad-secret", "")) {
            SourceException error = assertThrows(SourceException.class, source::connect);

            assertEquals(SourceException.Reason.AUTH, error.reason());
        }
    }

    @Test
    void subscriptionDeliversBookAndLiveTrades() throws Exception {
        RecordingFeedListener listener = new RecordingFeedListener();
        try (FinamSource source = new FinamSource(channel, SECRET, "")) {
            source.connect();
            InstrumentSpec spec = source.resolve(new SymbolQuery("SiZ6@RTSX", "", ""));

            Subscription subscription = source.subscribe(spec, listener);

            // Стримы открываются по очереди и, с directExecutor, отвечают сразу.
            assertEquals("up BOOK", listener.next(WAIT));
            assertEquals("snapshot bids=[90000:5] asks=[90001:3]", listener.next(WAIT));
            assertEquals("level BID 90000 7", listener.next(WAIT));
            assertEquals("up TRADES", listener.next(WAIT));
            assertEquals("trade 90001.0 x2 BUYER @1762600000", listener.next(WAIT));

            subscription.close();
        }
        assertEquals(Set.of(), unauthorizedCalls);
    }

    /** Пропускает к справочнику и данным только запросы с действующим JWT. */
    private final class RequireJwt implements ServerInterceptor {
        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(
                ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
            if (!JWT.equals(headers.get(FinamSession.AUTHORIZATION))) {
                unauthorizedCalls.add(call.getMethodDescriptor().getFullMethodName());
                call.close(Status.UNAUTHENTICATED.withDescription("нет JWT"), new Metadata());
                return new ServerCall.Listener<>() {
                };
            }
            return next.startCall(call, headers);
        }
    }

    private final class FakeAuth extends AuthServiceGrpc.AuthServiceImplBase {
        @Override
        public void auth(AuthRequest request, StreamObserver<AuthResponse> responses) {
            authCalls.incrementAndGet();
            if (!SECRET.equals(request.getSecret())) {
                responses.onError(Status.UNAUTHENTICATED.withDescription("неверный секрет").asRuntimeException());
                return;
            }
            responses.onNext(AuthResponse.newBuilder().setToken(JWT).build());
            responses.onCompleted();
        }

        @Override
        public void tokenDetails(TokenDetailsRequest request, StreamObserver<TokenDetailsResponse> responses) {
            responses.onNext(TokenDetailsResponse.newBuilder()
                    .addAccountIds(ACCOUNT)
                    .addMdPermissions(MDPermission.newBuilder()
                            .setMic("RTSX")
                            .setQuoteLevel(MDPermission.QuoteLevel.QUOTE_LEVEL_DEPTH_OF_MARKET))
                    .build());
            responses.onCompleted();
        }
    }

    private static final class FakeAssets extends AssetsServiceGrpc.AssetsServiceImplBase {
        @Override
        public void getAsset(GetAssetRequest request, StreamObserver<GetAssetResponse> responses) {
            if (!ACCOUNT.equals(request.getAccountId())) {
                responses.onError(Status.INVALID_ARGUMENT.withDescription("Invalid arguments:account_id")
                        .asRuntimeException());
                return;
            }
            GetAssetResponse.Builder asset = switch (request.getSymbol()) {
                case "SiZ6@RTSX" -> GetAssetResponse.newBuilder()
                        .setTicker("SiZ6").setMic("RTSX").setType("FUTURES").setMinStep(1).setDecimals(0);
                case "SBER@MISX" -> GetAssetResponse.newBuilder()
                        .setTicker("SBER").setMic("MISX").setType("EQUITIES").setMinStep(1).setDecimals(2)
                        .setLotSize(decimal("10"));
                default -> null;
            };
            if (asset == null) {
                responses.onError(Status.NOT_FOUND.withDescription("asset not found").asRuntimeException());
                return;
            }
            responses.onNext(asset.build());
            responses.onCompleted();
        }
    }

    private static final class FakeMarketData extends MarketDataServiceGrpc.MarketDataServiceImplBase {
        @Override
        public void subscribeOrderBook(
                SubscribeOrderBookRequest request, StreamObserver<SubscribeOrderBookResponse> responses) {
            responses.onNext(book(request.getSymbol(), true,
                    Row.newBuilder().setPrice(decimal("90000")).setBuySize(decimal("5")).setAction(Action.ACTION_ADD),
                    Row.newBuilder().setPrice(decimal("90001")).setSellSize(decimal("3")).setAction(Action.ACTION_ADD)));
            responses.onNext(book(request.getSymbol(), false,
                    Row.newBuilder().setPrice(decimal("90000")).setBuySize(decimal("7"))
                            .setAction(Action.ACTION_UPDATE)));
        }

        @Override
        public void subscribeLatestTrades(
                SubscribeLatestTradesRequest request, StreamObserver<SubscribeLatestTradesResponse> responses) {
            responses.onNext(SubscribeLatestTradesResponse.newBuilder()
                    .setSymbol(request.getSymbol())
                    .addTrades(trade("89999", "1", true))
                    .addTrades(trade("90001", "2", false))
                    .build());
        }

        private static SubscribeOrderBookResponse book(String symbol, boolean snapshot, Row.Builder... rows) {
            StreamOrderBook.Builder book = StreamOrderBook.newBuilder().setSymbol(symbol).setIsDataSnapshot(snapshot);
            for (Row.Builder row : rows) {
                book.addRows(row);
            }
            return SubscribeOrderBookResponse.newBuilder().addOrderBook(book).build();
        }

        private static Trade trade(String price, String size, boolean snapshot) {
            return Trade.newBuilder()
                    .setPrice(decimal(price))
                    .setSize(decimal(size))
                    .setSide(Side.SIDE_BUY)
                    .setIsDataSnapshot(snapshot)
                    .setTimestamp(Timestamp.newBuilder().setSeconds(1_762_600_000L))
                    .build();
        }
    }
}
