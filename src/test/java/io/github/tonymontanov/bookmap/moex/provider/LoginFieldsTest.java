package io.github.tonymontanov.bookmap.moex.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tonymontanov.bookmap.moex.source.SourceKind;
import io.github.tonymontanov.bookmap.moex.source.SourceSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoginFieldsTest {

    @Test
    void parsesAllFields() {
        SourceSettings settings = LoginFields.parse(Map.of(
                LoginFields.SOURCE, "finam",
                LoginFields.SECRET, "  s3cret  ",
                LoginFields.ACCOUNT, " 1440399 "));

        assertEquals(SourceKind.FINAM, settings.kind());
        assertEquals("s3cret", settings.secret());
        assertEquals("1440399", settings.accountId());
    }

    @Test
    void sourceDefaultsToFinamAndAccountIsOptional() {
        SourceSettings settings = LoginFields.parse(Map.of(LoginFields.SECRET, "s3cret"));

        assertEquals(SourceKind.FINAM, settings.kind());
        assertEquals("", settings.accountId());
    }

    @Test
    void missingSecretOrUnknownSourceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LoginFields.parse(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> LoginFields.parse(Map.of(LoginFields.SOURCE, "Bloomberg", LoginFields.SECRET, "x")));
    }

    @Test
    void configuredOnlyWithSecret() {
        assertFalse(LoginFields.isConfigured(Map.of(LoginFields.SECRET, "  ")));
        assertTrue(LoginFields.isConfigured(Map.of(LoginFields.SECRET, "x")));
    }

    @Test
    void settingsNeverPrintTheSecret() {
        SourceSettings settings = new SourceSettings(SourceKind.FINAM, "very-secret-value", "");

        assertFalse(settings.toString().contains("very-secret-value"));
    }
}
