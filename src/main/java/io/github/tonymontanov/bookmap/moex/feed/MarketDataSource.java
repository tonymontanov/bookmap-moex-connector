package io.github.tonymontanov.bookmap.moex.feed;

/**
 * Брокерский шлюз к рыночным данным MOEX: Finam Trade API, позже T-Invest.
 *
 * <p>Жизненный цикл: {@link #connect()} один раз, затем сколько угодно
 * {@link #resolve} и {@link #subscribe}, в конце {@link #close()}. Блокирующие
 * методы вызываются с рабочего потока провайдера, не с потока Bookmap.
 */
public interface MarketDataSource extends AutoCloseable {

    /** Имя шлюза для журнала и сообщений: «Finam». */
    String name();

    /** Проверяет учётные данные и готовит доступ к данным. Блокирует. */
    void connect() throws SourceException;

    /** Находит инструмент по запросу пользователя. Блокирует. */
    InstrumentSpec resolve(SymbolQuery query) throws SourceException;

    /**
     * Открывает стримы стакана и сделок инструмента. Не блокирует: данные и
     * обрывы приходят в {@code listener}, переподключения источник делает сам.
     */
    Subscription subscribe(InstrumentSpec instrument, FeedListener listener);

    /** Закрывает все подписки и соединения. Повторный вызов ничего не делает. */
    @Override
    void close();
}
