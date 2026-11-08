package io.github.tonymontanov.bookmap.moex.feed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * Агрегированный стакан (L2) одного инструмента в шагах цены.
 *
 * <p>Bookmap принимает только изменения уровней: «на цене P у бидов теперь
 * объём S», где S = 0 — уровень исчез. Шлюзы же присылают и изменения, и
 * полные снимки: Finam — снимок при подписке, раз в минуту и после
 * переподключения, T-Invest — только снимки. Стакан хранит последнее известное
 * состояние, сравнивает с ним снимок и отдаёт наружу ровно разницу. Так
 * уровни, исчезнувшие за время обрыва или между снимками, удаляются из
 * Bookmap, а не висят в нём до конца сессии.
 *
 * <p>Не потокобезопасен: владелец обращается к нему с одного потока.
 */
public final class OrderBook {

    private final TreeMap<Integer, Long> bids = new TreeMap<>();
    private final TreeMap<Integer, Long> asks = new TreeMap<>();

    /**
     * Заменяет стакан снимком и сообщает изменения: сначала исчезнувшие уровни
     * обеих сторон, затем новые и изменившиеся. Удаления идут первыми, чтобы
     * цена, перешедшая с одной стороны на другую, ни в один момент не
     * оказалась в стакане дважды.
     *
     * <p>Уровни с нулевым объёмом в снимке означают отсутствие уровня. Если цена
     * в снимке повторяется, действует последний уровень.
     */
    public void applySnapshot(Collection<Level> snapshotBids, Collection<Level> snapshotAsks, DepthListener out) {
        Map<Integer, Long> freshBids = index(snapshotBids);
        Map<Integer, Long> freshAsks = index(snapshotAsks);
        removeMissing(Side.BID, bids, freshBids, out);
        removeMissing(Side.ASK, asks, freshAsks, out);
        putChanged(Side.BID, bids, freshBids, out);
        putChanged(Side.ASK, asks, freshAsks, out);
    }

    /** Применяет изменение одного уровня; сообщает его, только если объём действительно изменился. */
    public void applyLevel(Side side, int ticks, long size, DepthListener out) {
        TreeMap<Integer, Long> book = side(side);
        if (size <= 0) {
            if (book.remove(ticks) != null) {
                out.onLevel(side, ticks, 0);
            }
            return;
        }
        Long previous = book.put(ticks, size);
        if (previous == null || previous != size) {
            out.onLevel(side, ticks, size);
        }
    }

    /**
     * Удаляет цену с той стороны, где она есть. Нужен для строк удаления, в
     * которых шлюз не указал сторону.
     */
    public void removePrice(int ticks, DepthListener out) {
        if (bids.remove(ticks) != null) {
            out.onLevel(Side.BID, ticks, 0);
        }
        if (asks.remove(ticks) != null) {
            out.onLevel(Side.ASK, ticks, 0);
        }
    }

    /** Удаляет все уровни, сообщая о каждом. */
    public void clear(DepthListener out) {
        applySnapshot(List.of(), List.of(), out);
    }

    public OptionalInt bestBid() {
        return bids.isEmpty() ? OptionalInt.empty() : OptionalInt.of(bids.lastKey());
    }

    public OptionalInt bestAsk() {
        return asks.isEmpty() ? OptionalInt.empty() : OptionalInt.of(asks.firstKey());
    }

    /** Число уровней стороны. */
    public int depth(Side side) {
        return side(side).size();
    }

    /** Объём уровня; 0, если уровня нет. */
    public long size(Side side, int ticks) {
        Long size = side(side).get(ticks);
        return size == null ? 0 : size;
    }

    private TreeMap<Integer, Long> side(Side side) {
        return side == Side.BID ? bids : asks;
    }

    /** Снимок как карта цена → объём, упорядоченная по цене: изменения уходят наружу в предсказуемом порядке. */
    private static Map<Integer, Long> index(Collection<Level> levels) {
        Map<Integer, Long> result = new TreeMap<>();
        for (Level level : levels) {
            if (level.size() > 0) {
                result.put(level.ticks(), level.size());
            } else {
                result.remove(level.ticks());
            }
        }
        return result;
    }

    private static void removeMissing(
            Side side, NavigableMap<Integer, Long> book, Map<Integer, Long> fresh, DepthListener out) {
        List<Integer> gone = new ArrayList<>();
        for (Integer ticks : book.keySet()) {
            if (!fresh.containsKey(ticks)) {
                gone.add(ticks);
            }
        }
        for (Integer ticks : gone) {
            book.remove(ticks);
            out.onLevel(side, ticks, 0);
        }
    }

    private static void putChanged(
            Side side, NavigableMap<Integer, Long> book, Map<Integer, Long> fresh, DepthListener out) {
        for (Map.Entry<Integer, Long> level : fresh.entrySet()) {
            long size = level.getValue();
            Long previous = book.put(level.getKey(), size);
            if (previous == null || previous != size) {
                out.onLevel(side, level.getKey(), size);
            }
        }
    }
}
