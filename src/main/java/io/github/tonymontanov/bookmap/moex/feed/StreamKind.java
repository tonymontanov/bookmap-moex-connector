package io.github.tonymontanov.bookmap.moex.feed;

/** Поток данных одного инструмента. */
public enum StreamKind {
    BOOK,
    TRADES;

    /** Имя потока для сообщений пользователю. */
    public String title() {
        return this == BOOK ? "стакан" : "лента сделок";
    }
}
