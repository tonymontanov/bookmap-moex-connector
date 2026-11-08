package io.github.tonymontanov.bookmap.moex.source.finam;

import io.github.tonymontanov.bookmap.moex.feed.FeedListener;
import io.github.tonymontanov.bookmap.moex.feed.Level;
import io.github.tonymontanov.bookmap.moex.feed.Side;
import io.github.tonymontanov.bookmap.moex.feed.StreamKind;
import io.github.tonymontanov.bookmap.moex.feed.Trade;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Записывает вызовы {@link FeedListener} строками, чтобы тест сравнивал их как текст. */
final class RecordingFeedListener implements FeedListener {

    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();

    @Override
    public void onBookSnapshot(List<Level> bids, List<Level> asks) {
        events.add("snapshot bids=" + format(bids) + " asks=" + format(asks));
    }

    @Override
    public void onBookLevel(Side side, int ticks, long size) {
        events.add("level " + side + " " + ticks + " " + size);
    }

    @Override
    public void onBookPriceRemoved(int ticks) {
        events.add("removed " + ticks);
    }

    @Override
    public void onTrade(Trade trade) {
        events.add("trade " + trade.priceTicks() + " x" + trade.size() + " " + trade.aggressor()
                + " @" + trade.exchangeTime().getEpochSecond());
    }

    @Override
    public void onStreamUp(StreamKind kind) {
        events.add("up " + kind);
    }

    @Override
    public void onStreamDown(StreamKind kind, String reason) {
        events.add("down " + kind);
    }

    /** Всё записанное к этому моменту. */
    List<String> drain() {
        List<String> result = new ArrayList<>();
        events.drainTo(result);
        return result;
    }

    /** Следующее событие; ошибка теста, если его нет за {@code timeout}. */
    String next(Duration timeout) throws InterruptedException {
        String event = events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (event == null) {
            throw new AssertionError("нет события за " + timeout);
        }
        return event;
    }

    private static String format(List<Level> levels) {
        return levels.stream().map(level -> level.ticks() + ":" + level.size()).collect(Collectors.joining(",", "[", "]"));
    }
}
