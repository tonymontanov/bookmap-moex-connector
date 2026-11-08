package io.github.tonymontanov.bookmap.moex.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConnectionHealthTest {

    private final ConnectionHealth health = new ConnectionHealth();

    @Test
    void reportsOnlyTransitionsOfTheWholeProvider() {
        assertTrue(health.markDown("A"), "первый упавший — потеря связи");
        assertFalse(health.markDown("B"), "второй упавший — связь уже потеряна");
        assertFalse(health.markDown("A"), "повтор того же — ничего нового");
        assertFalse(health.markUp("A"), "B ещё лежит");
        assertTrue(health.markUp("B"), "последний поднялся — связь восстановлена");
        assertFalse(health.markUp("B"), "повтор — ничего нового");
        assertTrue(health.isHealthy());
    }

    @Test
    void upWithoutDownIsNotARestore() {
        assertFalse(health.markUp("A"));
        assertFalse(health.forget("A"));
    }
}
