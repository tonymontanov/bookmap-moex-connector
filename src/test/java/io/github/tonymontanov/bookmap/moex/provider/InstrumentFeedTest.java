package io.github.tonymontanov.bookmap.moex.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tonymontanov.bookmap.moex.feed.Aggressor;
import io.github.tonymontanov.bookmap.moex.feed.Level;
import io.github.tonymontanov.bookmap.moex.feed.Side;
import io.github.tonymontanov.bookmap.moex.feed.StreamKind;
import io.github.tonymontanov.bookmap.moex.feed.Trade;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class InstrumentFeedTest {

    private final List<String> output = new ArrayList<>();
    private final RecordingOutput recordingOutput = new RecordingOutput();
    private final Executor direct = Runnable::run;
    private final ConnectionHealth health = new ConnectionHealth();

    @Test
    void snapshotsAndChangesReachBookmapAsLevelChanges() {
        InstrumentFeed feed = new InstrumentFeed("SiZ6@RTSX", direct, recordingOutput, health);

        feed.onBookSnapshot(List.of(new Level(100, 5)), List.of(new Level(101, 3)));
        feed.onBookLevel(Side.BID, 100, 5);
        feed.onBookLevel(Side.BID, 100, 8);
        feed.onBookPriceRemoved(101);
        feed.onBookSnapshot(List.of(new Level(99, 1)), List.of());

        assertEquals(List.of(
                "depth bid 100 5",
                "depth ask 101 3",
                "depth bid 100 8",
                "depth ask 101 0",
                "depth bid 100 0",
                "depth bid 99 1"), output);
    }

    @Test
    void unknownAggressorIsInferredFromTheBook() {
        InstrumentFeed feed = new InstrumentFeed("SiZ6@RTSX", direct, recordingOutput, health);
        feed.onBookSnapshot(List.of(new Level(100, 5)), List.of(new Level(104, 3)));
        output.clear();

        feed.onTrade(trade(104, Aggressor.UNKNOWN));
        feed.onTrade(trade(100, Aggressor.UNKNOWN));
        feed.onTrade(trade(103, Aggressor.UNKNOWN));
        feed.onTrade(trade(101, Aggressor.UNKNOWN));
        feed.onTrade(trade(100, Aggressor.BUYER));

        assertEquals(List.of(
                "trade 104.0 x1 buyer",
                "trade 100.0 x1 seller",
                "trade 103.0 x1 buyer",
                "trade 101.0 x1 seller",
                "trade 100.0 x1 buyer"), output);
    }

    @Test
    void connectionIsLostWhileAnyBookStreamIsDown() {
        InstrumentFeed si = new InstrumentFeed("SiZ6@RTSX", direct, recordingOutput, health);
        InstrumentFeed sber = new InstrumentFeed("SBER@MISX", direct, recordingOutput, health);

        si.onStreamDown(StreamKind.BOOK, "UNAVAILABLE");
        sber.onStreamDown(StreamKind.BOOK, "UNAVAILABLE");
        si.onStreamDown(StreamKind.TRADES, "UNAVAILABLE");
        si.onStreamUp(StreamKind.BOOK);
        sber.onStreamUp(StreamKind.BOOK);

        assertEquals(List.of("lost SiZ6@RTSX: стакан оборвался: UNAVAILABLE", "restored"), output);
    }

    @Test
    void closingADownInstrumentRestoresTheConnection() {
        InstrumentFeed si = new InstrumentFeed("SiZ6@RTSX", direct, recordingOutput, health);
        si.onStreamDown(StreamKind.BOOK, "UNAVAILABLE");
        output.clear();

        si.close();
        si.onBookLevel(Side.BID, 100, 1);

        assertEquals(List.of("restored"), output);
        assertTrue(health.isHealthy());
    }

    @Test
    void sizesAreClampedToInt() {
        assertEquals(Integer.MAX_VALUE, InstrumentFeed.clampSize(Long.MAX_VALUE));
        assertEquals(0, InstrumentFeed.clampSize(-1));
        assertEquals(7, InstrumentFeed.clampSize(7));
    }

    private static Trade trade(double priceTicks, Aggressor aggressor) {
        return new Trade(priceTicks, 1, aggressor, Instant.EPOCH);
    }

    private final class RecordingOutput implements BookmapOutput {
        @Override
        public void depth(String alias, boolean isBid, int price, int size) {
            output.add("depth " + (isBid ? "bid" : "ask") + " " + price + " " + size);
        }

        @Override
        public void trade(String alias, double price, int size, boolean isBidAggressor) {
            output.add("trade " + price + " x" + size + " " + (isBidAggressor ? "buyer" : "seller"));
        }

        @Override
        public void connectionLost(String message) {
            output.add("lost " + message);
        }

        @Override
        public void connectionRestored() {
            output.add("restored");
        }
    }
}
