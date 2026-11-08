package io.github.tonymontanov.bookmap.moex.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void doublesUpToTheCeilingAndResets() {
        Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(5));

        assertEquals(Duration.ofSeconds(1), backoff.next());
        assertEquals(Duration.ofSeconds(2), backoff.next());
        assertEquals(Duration.ofSeconds(4), backoff.next());
        assertEquals(Duration.ofSeconds(5), backoff.next());
        assertEquals(Duration.ofSeconds(5), backoff.next());

        backoff.reset();
        assertEquals(Duration.ofSeconds(1), backoff.next());
    }

    @Test
    void rejectsInconsistentBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Backoff(Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new Backoff(Duration.ofSeconds(2), Duration.ofSeconds(1)));
    }
}
