package io.github.tonymontanov.bookmap.moex.source.finam;

import io.github.tonymontanov.bookmap.moex.feed.SourceException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/** Перевод ответов gRPC шлюза в {@link SourceException} и текст для пользователя. */
final class FinamStatus {

    /** Блокирующий вызов стаба. */
    @FunctionalInterface
    interface Call<T> {
        T run();
    }

    private FinamStatus() {
    }

    /** Выполняет вызов; отказ gRPC становится {@link SourceException} с описанием действия. */
    static <T> T call(String action, Call<T> call) throws SourceException {
        try {
            return call.run();
        } catch (StatusRuntimeException e) {
            throw toException(action, e.getStatus());
        }
    }

    static SourceException toException(String action, Status status) {
        SourceException.Reason reason = switch (status.getCode()) {
            case UNAUTHENTICATED, PERMISSION_DENIED -> SourceException.Reason.AUTH;
            case UNAVAILABLE, DEADLINE_EXCEEDED -> SourceException.Reason.NETWORK;
            case NOT_FOUND -> SourceException.Reason.NOT_FOUND;
            case RESOURCE_EXHAUSTED -> SourceException.Reason.RATE_LIMIT;
            default -> SourceException.Reason.OTHER;
        };
        return new SourceException(reason, "Finam, " + action + ": " + describe(status), status.asRuntimeException());
    }

    /** {@code UNAVAILABLE — io exception}: код и описание шлюза, если оно есть. */
    static String describe(Status status) {
        String description = status.getDescription();
        if (description == null || description.isBlank()) {
            Throwable cause = status.getCause();
            description = cause == null ? null : cause.getMessage();
        }
        return description == null || description.isBlank()
                ? status.getCode().name()
                : status.getCode().name() + " — " + description;
    }
}
