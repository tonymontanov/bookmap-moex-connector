package io.github.tonymontanov.bookmap.moex.feed;

/** Получатель изменений уровней стакана, которые вычисляет {@link OrderBook}. */
@FunctionalInterface
public interface DepthListener {

    /**
     * Уровень изменился.
     *
     * @param size новый объём; 0 — уровень исчез
     */
    void onLevel(Side side, int ticks, long size);
}
