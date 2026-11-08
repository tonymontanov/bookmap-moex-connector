package io.github.tonymontanov.bookmap.moex.source;

import java.util.Objects;

/**
 * Настройки подключения к шлюзу из окна входа Bookmap.
 *
 * @param kind      шлюз
 * @param secret    секрет API шлюза; в журнал и исключения не попадает
 * @param accountId торговый счёт; пустой — взять первый счёт токена
 */
public record SourceSettings(SourceKind kind, String secret, String accountId) {

    public SourceSettings {
        Objects.requireNonNull(kind, "kind");
        secret = secret == null ? "" : secret.strip();
        accountId = accountId == null ? "" : accountId.strip();
        if (secret.isEmpty()) {
            throw new IllegalArgumentException("не указан секрет API " + kind.title());
        }
    }

    /** Без секрета: запись попадает в журнал и стек-трейсы. */
    @Override
    public String toString() {
        return "SourceSettings[kind=" + kind + ", secret=<скрыт>, accountId=" + accountId + "]";
    }
}
