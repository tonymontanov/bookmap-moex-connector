package io.github.tonymontanov.bookmap.moex.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InstrumentScaleTest {

    @Test
    void stockPricesLandExactlyOnTheStepGrid() {
        InstrumentScale scale = new InstrumentScale(new BigDecimal("0.01"));

        assertEquals(30_012, scale.ticks(new BigDecimal("300.12")));
        // 0.1 + 0.2 в double — 0.30000000000000004; в BigDecimal ровно 30 шагов.
        assertEquals(30, scale.ticks(new BigDecimal("0.1").add(new BigDecimal("0.2"))));
    }

    @Test
    void futuresPricesWithWholeStep() {
        InstrumentScale scale = new InstrumentScale(new BigDecimal("1"));

        assertEquals(87_000, scale.ticks(new BigDecimal("87000.0")));
    }

    @Test
    void offGridBookPriceRoundsToNearestStep() {
        InstrumentScale scale = new InstrumentScale(new BigDecimal("5"));

        assertEquals(20, scale.ticks(new BigDecimal("101")));
        assertEquals(21, scale.ticks(new BigDecimal("103")));
    }

    @Test
    void tradePriceKeepsFraction() {
        InstrumentScale scale = new InstrumentScale(new BigDecimal("5"));

        assertEquals(20.2, scale.fractionalTicks(new BigDecimal("101")), 1e-9);
    }

    @Test
    void ticksOverflowingIntAreRejected() {
        InstrumentScale scale = new InstrumentScale(new BigDecimal("0.00001"));

        assertThrows(ArithmeticException.class, () -> scale.ticks(new BigDecimal("1000000")));
    }

    @Test
    void sizesAreWholeUnits() {
        assertEquals(15, InstrumentScale.size(new BigDecimal("15.0")));
        assertEquals(0, InstrumentScale.size(BigDecimal.ZERO));
    }

    @Test
    void nonPositiveStepIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new InstrumentScale(BigDecimal.ZERO));
    }
}
