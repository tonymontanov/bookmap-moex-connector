package io.github.tonymontanov.bookmap.moex.feed;

/**
 * Уровень стакана: цена в шагах цены инструмента и суммарный объём на ней.
 *
 * @param ticks цена, делённая на шаг цены
 * @param size  объём в единицах шлюза (контракты, лоты или штуки); 0 — уровня нет
 */
public record Level(int ticks, long size) {

    public Level {
        if (size < 0) {
            throw new IllegalArgumentException("отрицательный объём уровня " + ticks + ": " + size);
        }
    }
}
