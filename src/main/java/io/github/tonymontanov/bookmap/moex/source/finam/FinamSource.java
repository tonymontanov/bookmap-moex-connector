package io.github.tonymontanov.bookmap.moex.source.finam;

import grpc.tradeapi.v1.auth.MDPermission;
import grpc.tradeapi.v1.auth.TokenDetailsResponse;
import grpc.tradeapi.v1.marketdata.MarketDataServiceGrpc;
import grpc.tradeapi.v1.marketdata.SubscribeLatestTradesRequest;
import grpc.tradeapi.v1.marketdata.SubscribeLatestTradesResponse;
import grpc.tradeapi.v1.marketdata.SubscribeOrderBookRequest;
import grpc.tradeapi.v1.marketdata.SubscribeOrderBookResponse;
import io.github.tonymontanov.bookmap.moex.feed.FeedListener;
import io.github.tonymontanov.bookmap.moex.feed.InstrumentScale;
import io.github.tonymontanov.bookmap.moex.feed.InstrumentSpec;
import io.github.tonymontanov.bookmap.moex.feed.MarketDataSource;
import io.github.tonymontanov.bookmap.moex.feed.SourceException;
import io.github.tonymontanov.bookmap.moex.feed.StreamKind;
import io.github.tonymontanov.bookmap.moex.feed.Subscription;
import io.github.tonymontanov.bookmap.moex.feed.SymbolQuery;
import io.github.tonymontanov.bookmap.moex.shared.Backoff;
import io.github.tonymontanov.bookmap.moex.shared.ConnectorLog;
import io.github.tonymontanov.bookmap.moex.shared.DaemonThreads;
import io.github.tonymontanov.bookmap.moex.transport.GrpcChannels;
import io.github.tonymontanov.bookmap.moex.transport.ReconnectingStream;
import io.github.tonymontanov.bookmap.moex.transport.StreamHandler;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Рыночные данные MOEX через Finam Trade API (gRPC, {@code api.finam.ru:443}).
 *
 * <p>На инструмент открываются два server-side стрима: стакан
 * ({@code SubscribeOrderBook}) и лента сделок ({@code SubscribeLatestTrades}) —
 * шлюз принимает по одному инструменту на стрим. Каждый переоткрывается после
 * обрыва сам ({@link ReconnectingStream}); после переоткрытия стакан приходит
 * снимком, и получатель выравнивает по нему своё состояние.
 *
 * <p>Глубина стакана не документирована и зависит от прав токена
 * ({@code MDPermission.quote_level}); права пишутся в журнал при входе.
 */
public final class FinamSource implements MarketDataSource {

    static final String HOST = "api.finam.ru";
    static final int PORT = 443;
    static final String USER_AGENT = "bookmap-moex-connector";

    /** Первая пауза перед переоткрытием стрима: одиночный обрыв восстанавливается за секунду. */
    static final Duration RETRY_INITIAL = Duration.ofSeconds(1);

    /** Потолок паузы: техокно шлюза длится час, проверять его чаще раза в 30 секунд незачем. */
    static final Duration RETRY_MAX = Duration.ofSeconds(30);

    private final ManagedChannel channel;
    private final FinamSession session;
    private final FinamInstruments instruments;
    private final String configuredAccountId;
    private final ScheduledExecutorService retryScheduler =
            Executors.newSingleThreadScheduledExecutor(DaemonThreads.named("finam-retry"));

    private volatile String accountId = "";

    public FinamSource(String secret, String accountId) {
        this(GrpcChannels.tls(HOST, PORT, USER_AGENT), secret, accountId);
    }

    FinamSource(ManagedChannel channel, String secret, String accountId) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.session = new FinamSession(channel, secret);
        this.instruments = new FinamInstruments(channel, session);
        this.configuredAccountId = accountId == null ? "" : accountId.strip();
    }

    @Override
    public String name() {
        return "Finam";
    }

    @Override
    public void connect() throws SourceException {
        session.token();
        TokenDetailsResponse details = null;
        try {
            details = session.details();
        } catch (SourceException e) {
            // Без сведений о токене данные всё равно читаются; нужен только
            // счёт для справочника, а его можно указать в окне входа.
            ConnectorLog.warn("Finam: сведения о токене недоступны: " + e.getMessage());
        }
        accountId = chooseAccount(configuredAccountId, details);
        ConnectorLog.info("Finam: вход выполнен; счёт для справочника: "
                + (accountId.isEmpty() ? "не определён" : accountId)
                + (details == null ? "" : "; права на данные: " + describePermissions(details)));
    }

    @Override
    public InstrumentSpec resolve(SymbolQuery query) throws SourceException {
        return instruments.resolve(query, accountId);
    }

    @Override
    public Subscription subscribe(InstrumentSpec instrument, FeedListener listener) {
        String symbol = instrument.alias();
        InstrumentScale scale = InstrumentScale.of(instrument);
        MarketDataServiceGrpc.MarketDataServiceStub stub =
                MarketDataServiceGrpc.newStub(channel).withCallCredentials(session.credentials());

        FinamBookMapper bookMapper = new FinamBookMapper(symbol, scale);
        SubscribeOrderBookRequest bookRequest = SubscribeOrderBookRequest.newBuilder().setSymbol(symbol).build();
        ReconnectingStream<SubscribeOrderBookResponse> book = new ReconnectingStream<>(
                "Finam стакан " + symbol,
                observer -> stub.subscribeOrderBook(bookRequest, observer),
                new Handler<>(StreamKind.BOOK, listener, bookMapper::dispatch),
                retryScheduler,
                new Backoff(RETRY_INITIAL, RETRY_MAX));

        FinamTradeMapper tradeMapper = new FinamTradeMapper(symbol, scale);
        SubscribeLatestTradesRequest tradesRequest =
                SubscribeLatestTradesRequest.newBuilder().setSymbol(symbol).build();
        ReconnectingStream<SubscribeLatestTradesResponse> trades = new ReconnectingStream<>(
                "Finam сделки " + symbol,
                observer -> stub.subscribeLatestTrades(tradesRequest, observer),
                new Handler<>(StreamKind.TRADES, listener, tradeMapper::dispatch),
                retryScheduler,
                new Backoff(RETRY_INITIAL, RETRY_MAX));

        book.start();
        trades.start();
        return () -> {
            book.close();
            trades.close();
        };
    }

    @Override
    public void close() {
        retryScheduler.shutdownNow();
        channel.shutdownNow();
    }

    /** Счёт из окна входа важнее счетов токена: пользователь выбрал его явно. */
    static String chooseAccount(String configured, TokenDetailsResponse details) {
        if (!configured.isEmpty()) {
            return configured;
        }
        if (details == null || details.getAccountIdsCount() == 0) {
            return "";
        }
        return details.getAccountIds(0);
    }

    /** {@code RTSX: DEPTH_OF_MARKET; MISX: DEPTH_OF_MARKET, задержка 15 мин} — для журнала. */
    static String describePermissions(TokenDetailsResponse details) {
        if (details.getMdPermissionsCount() == 0) {
            return "не сообщены";
        }
        return details.getMdPermissionsList().stream()
                .map(FinamSource::describePermission)
                .collect(Collectors.joining("; "));
    }

    private static String describePermission(MDPermission permission) {
        String scope = switch (permission.getConditionCase()) {
            case MIC -> permission.getMic();
            case COUNTRY -> "страна " + permission.getCountry();
            case CONTINENT -> "континент " + permission.getContinent();
            case WORLDWIDE -> "весь мир";
            case CONDITION_NOT_SET -> "без условия";
        };
        String level = permission.getQuoteLevel().name().replace("QUOTE_LEVEL_", "");
        String delay = permission.getDelayMinutes() > 0 ? ", задержка " + permission.getDelayMinutes() + " мин" : "";
        return scope + ": " + level + delay;
    }

    /** Переводит события стрима в вызовы получателя подписки. */
    private final class Handler<T> implements StreamHandler<T> {

        private final StreamKind kind;
        private final FeedListener listener;
        private final BiConsumer<T, FeedListener> mapper;

        Handler(StreamKind kind, FeedListener listener, BiConsumer<T, FeedListener> mapper) {
            this.kind = kind;
            this.listener = listener;
            this.mapper = mapper;
        }

        @Override
        public void onUp() {
            listener.onStreamUp(kind);
        }

        @Override
        public void onMessage(T message) {
            mapper.accept(message, listener);
        }

        @Override
        public void onDown(Status status, Duration retryIn) {
            if (status.getCode() == Status.Code.UNAUTHENTICATED) {
                session.invalidate();
            }
            listener.onStreamDown(kind, FinamStatus.describe(status) + "; повтор через " + retryIn.toSeconds() + " с");
        }
    }
}
