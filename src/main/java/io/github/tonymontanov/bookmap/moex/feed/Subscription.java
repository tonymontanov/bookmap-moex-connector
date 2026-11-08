package io.github.tonymontanov.bookmap.moex.feed;

/** Подписка на данные инструмента. Закрытие останавливает все её стримы. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {

    @Override
    void close();
}
