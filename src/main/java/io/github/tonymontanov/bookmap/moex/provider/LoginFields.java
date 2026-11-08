package io.github.tonymontanov.bookmap.moex.provider;

import io.github.tonymontanov.bookmap.moex.source.SourceKind;
import io.github.tonymontanov.bookmap.moex.source.SourceSettings;
import java.util.Map;

/**
 * Поля окна входа и их разбор. Отделены от {@link MoexCredentialsFields},
 * которому нужны классы Bookmap, чтобы разбор проверялся тестом без Bookmap.
 */
final class LoginFields {

    /** Шлюз: выпадающий список с {@link SourceKind#title()}. */
    static final String SOURCE = "source";

    /** Секрет API шлюза; поле скрытое. */
    static final String SECRET = "secret";

    /** Торговый счёт; необязательное. */
    static final String ACCOUNT = "account";

    private LoginFields() {
    }

    /**
     * Настройки подключения из значений полей.
     *
     * @throws IllegalArgumentException если шлюз неизвестен или секрет пуст; сообщение — для пользователя
     */
    static SourceSettings parse(Map<String, String> values) {
        String sourceTitle = values.getOrDefault(SOURCE, "");
        SourceKind kind = sourceTitle == null || sourceTitle.isBlank()
                ? SourceKind.FINAM
                : SourceKind.fromTitle(sourceTitle).orElseThrow(() -> new IllegalArgumentException(
                        "Неизвестный шлюз «" + sourceTitle + "»; доступны: " + String.join(", ", SourceKind.titles())));
        return new SourceSettings(kind, values.get(SECRET), values.get(ACCOUNT));
    }

    /** Заполнено ли окно настолько, чтобы пробовать вход. */
    static boolean isConfigured(Map<String, String> values) {
        String secret = values.get(SECRET);
        return secret != null && !secret.isBlank();
    }
}
