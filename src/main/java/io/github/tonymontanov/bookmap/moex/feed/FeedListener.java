package io.github.tonymontanov.bookmap.moex.feed;

import java.util.List;

/**
 * Получатель данных одной подписки. Источник переводит сообщения шлюза в эти
 * вызовы и ничего не знает о Bookmap; стакан, разницу снимков и потоки
 * Bookmap ведёт получатель.
 *
 * <p>Вызовы приходят с потоков gRPC: стакан и лента — с разных, каждый поток
 * по порядку. Получатель сам сводит их в один поток, если ему нужен общий
 * порядок.
 */
public interface FeedListener {

    /** Полный снимок стакана: всё, чего в нём нет, из стакана исчезло. */
    void onBookSnapshot(List<Level> bids, List<Level> asks);

    /** Изменение одного уровня; {@code size == 0} — уровень исчез. */
    void onBookLevel(Side side, int ticks, long size);

    /** Уровень исчез, но шлюз не указал сторону. */
    void onBookPriceRemoved(int ticks);

    void onTrade(Trade trade);

    /** Стрим (пере)открыт и прислал первое сообщение. */
    void onStreamUp(StreamKind kind);

    /**
     * Стрим оборвался. Источник переоткроет его сам; до этого данные потока
     * не приходят, а стакан после переоткрытия придёт новым снимком.
     */
    void onStreamDown(StreamKind kind, String reason);
}
