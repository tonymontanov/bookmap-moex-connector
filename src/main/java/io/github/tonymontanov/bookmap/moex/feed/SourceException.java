package io.github.tonymontanov.bookmap.moex.feed;

import java.util.Objects;

/** Отказ источника данных: вход, справочник или сеть. Сообщение — для пользователя. */
public final class SourceException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Причина отказа: от неё зависит, что провайдер скажет Bookmap. */
    public enum Reason {
        /** Секрет или токен отвергнут. */
        AUTH,
        /** Шлюз недоступен или не ответил вовремя. */
        NETWORK,
        /** Шлюз не знает инструмента. */
        NOT_FOUND,
        /** Превышен лимит запросов шлюза. */
        RATE_LIMIT,
        OTHER
    }

    private final Reason reason;

    public SourceException(Reason reason, String message) {
        this(reason, message, null);
    }

    public SourceException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
