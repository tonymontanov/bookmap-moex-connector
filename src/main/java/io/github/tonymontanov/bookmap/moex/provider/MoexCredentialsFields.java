package io.github.tonymontanov.bookmap.moex.provider;

import io.github.tonymontanov.bookmap.moex.source.SourceKind;
import io.github.tonymontanov.bookmap.moex.source.SourceSettings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import velox.api.layer0.credentialscomponents.CredentialsComponent;
import velox.api.layer0.credentialscomponents.CredentialsDropdown;
import velox.api.layer0.credentialscomponents.CredentialsFieldManager;
import velox.api.layer0.credentialscomponents.CredentialsSerializationField;
import velox.api.layer0.credentialscomponents.CredentialsTextField;
import velox.api.layer1.data.ExtendedLoginData;
import velox.api.layer1.data.LoginData;
import velox.api.layer1.data.UserPasswordLoginData;

/**
 * Окно входа коннектора в Bookmap: шлюз, секрет API, счёт.
 *
 * <p>Bookmap создаёт экземпляр сам (нужен открытый конструктор без
 * аргументов), показывает компоненты и после нажатия «Connect» передаёт
 * значения провайдеру как {@link ExtendedLoginData}. Секрет хранится Bookmap
 * в скрытом поле и в журнал не попадает.
 */
public final class MoexCredentialsFields implements CredentialsFieldManager {

    public MoexCredentialsFields() {
    }

    @Override
    public List<CredentialsComponent> getCredentialsComponents() {
        return List.of(
                new CredentialsDropdown(LoginFields.SOURCE, true, "Шлюз", SourceKind.titles().toArray(String[]::new)),
                new CredentialsTextField(LoginFields.SECRET, false, "Секрет API", true),
                new CredentialsTextField(LoginFields.ACCOUNT, false, "Счёт (необязательно)", false));
    }

    @Override
    public boolean isConfigured(Map<String, CredentialsSerializationField> values) {
        return LoginFields.isConfigured(plain(values));
    }

    /**
     * Настройки подключения из данных входа. Без аннотации окна (или в старой
     * версии Bookmap) приходит пара логин/пароль: пароль — секрет, логин — счёт.
     *
     * @throws IllegalArgumentException если данных недостаточно; сообщение — для пользователя
     */
    static SourceSettings settings(LoginData loginData) {
        if (loginData instanceof ExtendedLoginData extended) {
            return LoginFields.parse(plain(extended.extendedData));
        }
        if (loginData instanceof UserPasswordLoginData userPassword) {
            return new SourceSettings(SourceKind.FINAM, userPassword.password, userPassword.user);
        }
        throw new IllegalArgumentException("Неизвестный вид данных входа: "
                + (loginData == null ? "null" : loginData.getClass().getName()));
    }

    private static Map<String, String> plain(Map<String, CredentialsSerializationField> values) {
        Map<String, String> result = new HashMap<>();
        if (values != null) {
            values.forEach((name, field) -> result.put(name, field == null ? "" : field.getStringValue()));
        }
        return result;
    }
}
