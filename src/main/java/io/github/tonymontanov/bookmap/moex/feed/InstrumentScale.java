package io.github.tonymontanov.bookmap.moex.feed;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Перевод десятичных цен и объёмов шлюза в целые единицы Bookmap.
 *
 * <p>Bookmap принимает цену стакана целым числом шагов цены, объём — целым
 * числом. Шлюзы присылают обе величины десятичными строками ({@code "87000.0"}),
 * и перевод идёт через {@link BigDecimal}: в {@code double} цена 0.1 + 0.2 уже
 * не попадает на сетку шага, и соседние уровни сливались бы в один.
 */
public final class InstrumentScale {

    private final BigDecimal priceStep;

    public InstrumentScale(BigDecimal priceStep) {
        Objects.requireNonNull(priceStep, "priceStep");
        if (priceStep.signum() <= 0) {
            throw new IllegalArgumentException("шаг цены должен быть положительным: " + priceStep);
        }
        this.priceStep = priceStep;
    }

    public static InstrumentScale of(InstrumentSpec spec) {
        return new InstrumentScale(spec.priceStep());
    }

    /**
     * Цена уровня стакана в шагах. Цена вне сетки шага округляется к ближайшему
     * шагу: уровень стакана по определению стоит на сетке, и отклонение может
     * быть только артефактом представления.
     *
     * @throws ArithmeticException если число шагов не помещается в {@code int}
     */
    public int ticks(BigDecimal price) {
        return price.divide(priceStep, 0, RoundingMode.HALF_UP).intValueExact();
    }

    /** Цена сделки в шагах без округления: Bookmap принимает её дробной. */
    public double fractionalTicks(BigDecimal price) {
        return price.divide(priceStep, MathContext.DECIMAL64).doubleValue();
    }

    /**
     * Объём в целых единицах. Объёмы MOEX целые (контракты, лоты, штуки); дробная
     * часть означала бы иную единицу у шлюза и округляется, а не отбрасывается.
     *
     * @throws ArithmeticException если объём не помещается в {@code long}
     */
    public static long size(BigDecimal size) {
        return size.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
