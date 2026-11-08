package io.github.tonymontanov.bookmap.moex.feed;

/**
 * Запрос инструмента так, как его ввёл пользователь в окне подписки Bookmap.
 *
 * @param symbol   символ: {@code SBER}, {@code SiZ6} или полный {@code SBER@MISX}
 * @param exchange биржа или секция; может быть пустой
 * @param type     тип из окна подписки; может быть пустым
 */
public record SymbolQuery(String symbol, String exchange, String type) {

    public SymbolQuery {
        symbol = symbol == null ? "" : symbol.strip();
        exchange = exchange == null ? "" : exchange.strip();
        type = type == null ? "" : type.strip();
    }

    /** Запрос для сообщений пользователю: {@code SBER (MISX)}. */
    public String describe() {
        return exchange.isEmpty() ? symbol : symbol + " (" + exchange + ")";
    }
}
