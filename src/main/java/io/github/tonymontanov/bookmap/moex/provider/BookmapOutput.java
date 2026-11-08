package io.github.tonymontanov.bookmap.moex.provider;

/**
 * События, которые провайдер отдаёт слушателям Bookmap. Выделены в интерфейс,
 * чтобы логику инструмента ({@link InstrumentFeed}) можно было проверить без
 * Bookmap: в тесте её получатель — запись вызовов.
 *
 * <p>Цена — в шагах цены инструмента, объём — в целых единицах.
 */
interface BookmapOutput {

    void depth(String alias, boolean isBid, int price, int size);

    void trade(String alias, double price, int size, boolean isBidAggressor);

    void connectionLost(String message);

    void connectionRestored();
}
