package io.github.tonymontanov.bookmap.moex.source.finam;

import com.google.type.Decimal;
import java.math.BigDecimal;

/**
 * {@code google.type.Decimal} шлюза — десятичная строка ({@code "87000.0"},
 * {@code "0.01"}) — в {@link BigDecimal}. Пустая строка — отсутствие значения,
 * то есть ноль: так шлюз передаёт, например, объём удалённого уровня.
 */
final class FinamDecimals {

    private FinamDecimals() {
    }

    /** @throws NumberFormatException если строка не число */
    static BigDecimal parse(Decimal decimal) {
        String value = decimal.getValue();
        return value.isEmpty() ? BigDecimal.ZERO : new BigDecimal(value.strip());
    }
}
