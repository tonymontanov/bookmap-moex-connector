package io.github.tonymontanov.bookmap.moex.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import grpc.tradeapi.v1.marketdata.MarketDataServiceGrpc;
import grpc.tradeapi.v1.marketdata.StreamOrderBook;
import grpc.tradeapi.v1.marketdata.SubscribeOrderBookRequest;
import grpc.tradeapi.v1.marketdata.SubscribeOrderBookResponse;
import io.github.tonymontanov.bookmap.moex.shared.Backoff;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReconnectingStreamTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() throws InterruptedException {
        scheduler.shutdownNow();
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void reopensAfterFailureAndCancelsOnClose() throws Exception {
        FlakyBook service = new FlakyBook(1);
        ReconnectingStream<SubscribeOrderBookResponse> stream = start(service, Duration.ofMillis(10));

        assertEquals("down UNAVAILABLE", next());
        assertEquals("up", next());
        assertEquals("message SBER@MISX", next());

        stream.close();
        assertTrue(service.cancelled.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "сервер должен увидеть отмену");
        assertEquals(2, service.calls.get());
        assertNull(events.poll(100, TimeUnit.MILLISECONDS), "после close событий нет");
    }

    @Test
    void closeWhileWaitingCancelsTheScheduledRetry() throws Exception {
        FlakyBook service = new FlakyBook(Integer.MAX_VALUE);
        ReconnectingStream<SubscribeOrderBookResponse> stream = start(service, Duration.ofMillis(300));

        assertEquals("down UNAVAILABLE", next());
        stream.close();
        Thread.sleep(600);

        assertEquals(1, service.calls.get());
    }

    @Test
    void retryDelayDependsOnTheFailure() {
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(30));

        assertEquals(ReconnectingStream.RATE_LIMIT_PAUSE,
                ReconnectingStream.retryDelay(Status.RESOURCE_EXHAUSTED, backoff));
        assertEquals(ReconnectingStream.PERMANENT_ERROR_PAUSE,
                ReconnectingStream.retryDelay(Status.NOT_FOUND, backoff));
        assertEquals(Duration.ofSeconds(1), ReconnectingStream.retryDelay(Status.UNAVAILABLE, backoff));
        assertEquals(Duration.ofSeconds(2), ReconnectingStream.retryDelay(Status.UNAUTHENTICATED, backoff));
    }

    private ReconnectingStream<SubscribeOrderBookResponse> start(FlakyBook service, Duration retry) throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        MarketDataServiceGrpc.MarketDataServiceStub stub = MarketDataServiceGrpc.newStub(channel);
        SubscribeOrderBookRequest request = SubscribeOrderBookRequest.newBuilder().setSymbol("SBER@MISX").build();
        ReconnectingStream<SubscribeOrderBookResponse> stream = new ReconnectingStream<>(
                "тест",
                observer -> stub.subscribeOrderBook(request, observer),
                new StreamHandler<>() {
                    @Override
                    public void onUp() {
                        events.add("up");
                    }

                    @Override
                    public void onMessage(SubscribeOrderBookResponse message) {
                        events.add("message " + message.getOrderBook(0).getSymbol());
                    }

                    @Override
                    public void onDown(Status status, Duration retryIn) {
                        events.add("down " + status.getCode());
                    }
                },
                scheduler,
                new Backoff(retry, retry.multipliedBy(10)));
        stream.start();
        return stream;
    }

    private String next() throws InterruptedException {
        String event = events.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        if (event == null) {
            throw new AssertionError("нет события за " + WAIT);
        }
        return event;
    }

    /** Стакан, который первые {@code failures} вызовов отвечает UNAVAILABLE, а затем шлёт одно сообщение и держит стрим. */
    private static final class FlakyBook extends MarketDataServiceGrpc.MarketDataServiceImplBase {

        private final int failures;
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch cancelled = new CountDownLatch(1);

        FlakyBook(int failures) {
            this.failures = failures;
        }

        @Override
        public void subscribeOrderBook(
                SubscribeOrderBookRequest request, StreamObserver<SubscribeOrderBookResponse> responses) {
            if (calls.incrementAndGet() <= failures) {
                responses.onError(Status.UNAVAILABLE.withDescription("шлюз перезапускается").asRuntimeException());
                return;
            }
            ((ServerCallStreamObserver<SubscribeOrderBookResponse>) responses).setOnCancelHandler(cancelled::countDown);
            responses.onNext(SubscribeOrderBookResponse.newBuilder()
                    .addOrderBook(StreamOrderBook.newBuilder().setSymbol(request.getSymbol()))
                    .build());
        }
    }
}
