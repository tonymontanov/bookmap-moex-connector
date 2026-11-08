package io.github.tonymontanov.bookmap.moex.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import velox.api.layer0.annotations.Layer0CredentialsFieldsManager;
import velox.api.layer0.annotations.Layer0LiveModule;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;

/**
 * Точка входа модуля такая, какой её ищет Bookmap 7.9. Без
 * {@link Layer1ApiVersion} Bookmap не грузит модуль и виснет на старте —
 * так было при первой установке 08.11.2026.
 */
class ProviderEntryPointTest {

    @Test
    void providerCarriesEveryAnnotationBookmapReads() {
        Layer0LiveModule module = MoexLiveProvider.class.getAnnotation(Layer0LiveModule.class);
        Layer1ApiVersion version = MoexLiveProvider.class.getAnnotation(Layer1ApiVersion.class);
        Layer0CredentialsFieldsManager fields = MoexLiveProvider.class.getAnnotation(Layer0CredentialsFieldsManager.class);

        assertNotNull(module, "@Layer0LiveModule");
        assertEquals("MOEX (Finam)", module.fullName());
        assertNotNull(version, "@Layer1ApiVersion: без неё Bookmap 7.9 виснет на старте");
        assertEquals(Layer1ApiVersionValue.VERSION3, version.value());
        assertNotNull(fields, "@Layer0CredentialsFieldsManager");
        assertEquals(MoexCredentialsFields.class, fields.value());
    }

    @Test
    void bookmapCanInstantiateEntryPointsReflectively() {
        assertTrue(Modifier.isPublic(MoexLiveProvider.class.getModifiers()));
        assertTrue(Modifier.isPublic(MoexCredentialsFields.class.getModifiers()));
        assertDoesNotThrow(() -> MoexLiveProvider.class.getConstructor());
        assertDoesNotThrow(() -> MoexCredentialsFields.class.getConstructor());
    }
}
