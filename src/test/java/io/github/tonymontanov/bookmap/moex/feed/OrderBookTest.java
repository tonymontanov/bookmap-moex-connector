package io.github.tonymontanov.bookmap.moex.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    private final OrderBook book = new OrderBook();
    private final List<String> changes = new ArrayList<>();
    private final DepthListener recorder = (side, ticks, size) -> changes.add(side + " " + ticks + " " + size);

    @Test
    void firstSnapshotReportsEveryLevel() {
        book.applySnapshot(List.of(new Level(100, 5), new Level(99, 7)), List.of(new Level(101, 3)), recorder);

        assertEquals(List.of("BID 99 7", "BID 100 5", "ASK 101 3"), changes);
        assertEquals(OptionalInt.of(100), book.bestBid());
        assertEquals(OptionalInt.of(101), book.bestAsk());
    }

    @Test
    void nextSnapshotReportsOnlyTheDifferenceRemovalsFirst() {
        book.applySnapshot(List.of(new Level(100, 5), new Level(99, 7)), List.of(new Level(101, 3)), recorder);
        changes.clear();

        // Биды: 99 исчез, 100 не изменился, 98 появился. Аски: 101 исчез, 102 появился.
        book.applySnapshot(List.of(new Level(100, 5), new Level(98, 1)), List.of(new Level(102, 4)), recorder);

        assertEquals(List.of("BID 99 0", "ASK 101 0", "BID 98 1", "ASK 102 4"), changes);
    }

    @Test
    void priceCrossingSidesIsRemovedBeforeItIsAdded() {
        book.applySnapshot(List.of(new Level(100, 5)), List.of(new Level(101, 3)), recorder);
        changes.clear();

        book.applySnapshot(List.of(new Level(101, 2)), List.of(new Level(102, 3)), recorder);

        assertEquals(List.of("BID 100 0", "ASK 101 0", "BID 101 2", "ASK 102 3"), changes);
    }

    @Test
    void zeroSizedSnapshotLevelsMeanAbsence() {
        book.applySnapshot(List.of(new Level(100, 5)), List.of(), recorder);
        changes.clear();

        book.applySnapshot(List.of(new Level(100, 0)), List.of(), recorder);

        assertEquals(List.of("BID 100 0"), changes);
        assertEquals(0, book.depth(Side.BID));
    }

    @Test
    void levelUpdatesAreReportedOnlyWhenSizeChanges() {
        book.applyLevel(Side.BID, 100, 5, recorder);
        book.applyLevel(Side.BID, 100, 5, recorder);
        book.applyLevel(Side.BID, 100, 6, recorder);
        book.applyLevel(Side.BID, 100, 0, recorder);
        book.applyLevel(Side.BID, 100, 0, recorder);

        assertEquals(List.of("BID 100 5", "BID 100 6", "BID 100 0"), changes);
    }

    @Test
    void removePriceWithoutSideClearsWhicheverSideHasIt() {
        book.applyLevel(Side.ASK, 101, 3, recorder);
        changes.clear();

        book.removePrice(101, recorder);
        book.removePrice(101, recorder);

        assertEquals(List.of("ASK 101 0"), changes);
        assertTrue(book.bestAsk().isEmpty());
    }

    @Test
    void clearRemovesEverything() {
        book.applySnapshot(List.of(new Level(100, 5)), List.of(new Level(101, 3)), recorder);
        changes.clear();

        book.clear(recorder);

        assertEquals(List.of("BID 100 0", "ASK 101 0"), changes);
        assertEquals(0, book.depth(Side.BID) + book.depth(Side.ASK));
    }
}
