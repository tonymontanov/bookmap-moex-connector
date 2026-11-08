package io.github.tonymontanov.bookmap.moex.transport;

import io.grpc.Status;
import java.time.Duration;

/**
 * Получатель событий переподключаемого стрима ({@link ReconnectingStream}).
 *
 * <p>Методы вызываются на потоке gRPC, последовательно в пределах одной
 * попытки и без перекрытия между попытками: следующая открывается только
 * после того, как прошлая закончилась и об этом сообщено {@link #onDown}.
 */
public interface StreamHandler<T> {

    /** Пришло первое сообщение попытки: стрим жив. */
    void onUp();

    void onMessage(T message);

    /** Попытка оборвалась; следующая будет открыта через {@code retryIn}. */
    void onDown(Status status, Duration retryIn);
}
