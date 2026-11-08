package io.github.tonymontanov.bookmap.moex.transport;

import io.github.tonymontanov.bookmap.moex.shared.Backoff;
import io.github.tonymontanov.bookmap.moex.shared.ConnectorLog;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Server-side стрим gRPC, который переоткрывается после любого обрыва, пока
 * его не закроют явно.
 *
 * <p>Брокерские стримы рвутся штатно: шлюз Finam закрывает соединения раз в
 * сутки и в техокно 05:00–06:15 МСК, сеть пользователя пропадает. Каждый обрыв
 * доходит до получателя через {@link StreamHandler#onDown}, а стрим сам
 * открывается снова после паузы. Пауза растёт экспоненциально и сбрасывается
 * первым сообщением новой попытки, поэтому одиночный обрыв восстанавливается
 * за секунду, а лежащий шлюз не забрасывается запросами.
 *
 * <p>Каждая попытка помечена номером. Колбэки попытки, которая уже не текущая
 * (стрим закрыт или переоткрыт), игнорируются: иначе запоздавший
 * {@code onError} старой попытки запустил бы лишнее переподключение.
 */
public final class ReconnectingStream<T> implements AutoCloseable {

    /**
     * Пауза после RESOURCE_EXHAUSTED. Лимит шлюза Finam считается в минуту на
     * метод (200 запросов), и повтор раньше минуты только продлевает отказ.
     */
    static final Duration RATE_LIMIT_PAUSE = Duration.ofMinutes(1);

    /**
     * Пауза после ошибки, которая сама не пройдёт: неверный символ, нет прав на
     * данные, метод не поддержан. Повтор всё равно нужен — права выдают, шлюз
     * обновляют, — но не чаще раза в минуту.
     */
    static final Duration PERMANENT_ERROR_PAUSE = Duration.ofMinutes(1);

    private static final String CANCEL_REASON = "стрим закрыт коннектором";

    private final String name;
    private final Consumer<StreamObserver<T>> opener;
    private final StreamHandler<T> handler;
    private final ScheduledExecutorService scheduler;
    private final Object lock = new Object();

    // Все поля ниже — под lock.
    private final Backoff backoff;
    private ClientCallStreamObserver<?> call;
    private long attempt;
    private boolean closed;

    /**
     * @param name      имя стрима для журнала, например «Finam стакан SBER@MISX»
     * @param opener    открывает одну попытку: вызывает метод асинхронного стаба
     *                  с переданным наблюдателем
     * @param handler   получатель событий
     * @param scheduler планировщик повторов; его остановка прекращает повторы
     * @param backoff   пауза между повторами; принадлежит этому стриму
     */
    public ReconnectingStream(
            String name,
            Consumer<StreamObserver<T>> opener,
            StreamHandler<T> handler,
            ScheduledExecutorService scheduler,
            Backoff backoff) {
        this.name = Objects.requireNonNull(name, "name");
        this.opener = Objects.requireNonNull(opener, "opener");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
    }

    /** Открывает первую попытку. */
    public void start() {
        open();
    }

    /** Закрывает стрим навсегда: отменяет текущую попытку и запланированные повторы. */
    @Override
    public void close() {
        ClientCallStreamObserver<?> current;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            current = call;
            call = null;
        }
        if (current != null) {
            current.cancel(CANCEL_REASON, null);
        }
    }

    /** Пауза перед повтором в зависимости от причины обрыва. */
    static Duration retryDelay(Status status, Backoff backoff) {
        return switch (status.getCode()) {
            case RESOURCE_EXHAUSTED -> RATE_LIMIT_PAUSE;
            case INVALID_ARGUMENT, NOT_FOUND, PERMISSION_DENIED, UNIMPLEMENTED, FAILED_PRECONDITION ->
                    PERMANENT_ERROR_PAUSE;
            default -> backoff.next();
        };
    }

    private void open() {
        Attempt observer;
        synchronized (lock) {
            if (closed) {
                return;
            }
            attempt++;
            observer = new Attempt(attempt);
        }
        try {
            opener.accept(observer);
        } catch (RuntimeException e) {
            // Стаб бросает синхронно, если канал уже остановлен; это тот же обрыв.
            observer.onError(e);
        }
    }

    private boolean isCurrent(long id) {
        synchronized (lock) {
            return !closed && id == attempt;
        }
    }

    private void scheduleReconnect(Duration delay) {
        try {
            scheduler.schedule(this::open, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Планировщик остановлен: источник закрывается, повторять некуда.
        }
    }

    /** Наблюдатель одной попытки. */
    private final class Attempt implements ClientResponseObserver<Object, T> {

        private final long id;
        private boolean receivedMessage;

        Attempt(long id) {
            this.id = id;
        }

        @Override
        public void beforeStart(ClientCallStreamObserver<Object> requestStream) {
            boolean stale;
            synchronized (lock) {
                stale = closed || id != attempt;
                if (!stale) {
                    call = requestStream;
                }
            }
            if (stale) {
                requestStream.cancel(CANCEL_REASON, null);
            }
        }

        @Override
        public void onNext(T message) {
            if (!isCurrent(id)) {
                return;
            }
            if (!receivedMessage) {
                receivedMessage = true;
                synchronized (lock) {
                    backoff.reset();
                }
                handler.onUp();
            }
            try {
                handler.onMessage(message);
            } catch (RuntimeException e) {
                // Одно неразобранное сообщение не повод рвать стрим: книга
                // восстановится ближайшим снимком, а исключение из onNext
                // gRPC превратил бы в отмену вызова.
                ConnectorLog.warn(name + ": сообщение не обработано", e);
            }
        }

        @Override
        public void onError(Throwable error) {
            finish(Status.fromThrowable(error));
        }

        @Override
        public void onCompleted() {
            finish(Status.UNAVAILABLE.withDescription("сервер закрыл стрим"));
        }

        private void finish(Status status) {
            Duration delay;
            synchronized (lock) {
                if (closed || id != attempt) {
                    return;
                }
                call = null;
                delay = retryDelay(status, backoff);
            }
            handler.onDown(status, delay);
            scheduleReconnect(delay);
        }
    }
}
