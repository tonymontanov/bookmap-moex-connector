package io.github.tonymontanov.bookmap.moex.provider;

import io.github.tonymontanov.bookmap.moex.feed.Aggressor;
import io.github.tonymontanov.bookmap.moex.feed.DepthListener;
import io.github.tonymontanov.bookmap.moex.feed.FeedListener;
import io.github.tonymontanov.bookmap.moex.feed.Level;
import io.github.tonymontanov.bookmap.moex.feed.OrderBook;
import io.github.tonymontanov.bookmap.moex.feed.Side;
import io.github.tonymontanov.bookmap.moex.feed.StreamKind;
import io.github.tonymontanov.bookmap.moex.feed.Subscription;
import io.github.tonymontanov.bookmap.moex.feed.Trade;
import io.github.tonymontanov.bookmap.moex.shared.ConnectorLog;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Один подписанный инструмент: получает данные источника, ведёт стакан и
 * отдаёт Bookmap изменения уровней и сделки.
 *
 * <p>Источник вызывает {@link FeedListener} с потоков gRPC — стакан и ленту с
 * разных. Каждый вызов перекладывается на поток-диспетчер провайдера, и всё
 * состояние (стакан, здоровье связи) живёт только на нём. Это же даёт Bookmap
 * события в том порядке, в котором они пришли: метку времени Bookmap ставит
 * в момент вызова слушателя.
 */
final class InstrumentFeed implements FeedListener {

    private final String alias;
    private final Executor dispatcher;
    private final BookmapOutput output;
    private final ConnectionHealth health;
    private final OrderBook book = new OrderBook();
    private final DepthListener depth;

    // Только на диспетчере.
    private Subscription subscription;
    private boolean closed;

    InstrumentFeed(String alias, Executor dispatcher, BookmapOutput output, ConnectionHealth health) {
        this.alias = Objects.requireNonNull(alias, "alias");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.output = Objects.requireNonNull(output, "output");
        this.health = Objects.requireNonNull(health, "health");
        this.depth = (side, ticks, size) -> output.depth(alias, side == Side.BID, ticks, clampSize(size));
    }

    String alias() {
        return alias;
    }

    /** Привязывает подписку источника. На диспетчере. */
    void attach(Subscription newSubscription) {
        if (closed) {
            newSubscription.close();
            return;
        }
        subscription = newSubscription;
    }

    /** Закрывает подписку; события, которые ещё в очереди, отбрасываются. На диспетчере. */
    void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (subscription != null) {
            subscription.close();
        }
        if (health.forget(alias)) {
            output.connectionRestored();
        }
    }

    @Override
    public void onBookSnapshot(List<Level> bids, List<Level> asks) {
        post(() -> book.applySnapshot(bids, asks, depth));
    }

    @Override
    public void onBookLevel(Side side, int ticks, long size) {
        post(() -> book.applyLevel(side, ticks, size, depth));
    }

    @Override
    public void onBookPriceRemoved(int ticks) {
        post(() -> book.removePrice(ticks, depth));
    }

    @Override
    public void onTrade(Trade trade) {
        post(() -> output.trade(alias, trade.priceTicks(), clampSize(trade.size()), isBuyerAggressor(trade)));
    }

    @Override
    public void onStreamUp(StreamKind kind) {
        post(() -> {
            ConnectorLog.info(alias + ": " + kind.title() + " получает данные");
            if (kind == StreamKind.BOOK && health.markUp(alias)) {
                output.connectionRestored();
            }
        });
    }

    @Override
    public void onStreamDown(StreamKind kind, String reason) {
        post(() -> {
            ConnectorLog.warn(alias + ": " + kind.title() + " оборвался: " + reason);
            if (kind == StreamKind.BOOK && health.markDown(alias)) {
                output.connectionLost(alias + ": " + kind.title() + " оборвался: " + reason);
            }
        });
    }

    /**
     * Сторона агрессора. Если шлюз её не сообщил, она восстанавливается по
     * стакану: сделка по аску и выше — покупка, по биду и ниже — продажа,
     * внутри спреда — по ближайшей стороне.
     */
    private boolean isBuyerAggressor(Trade trade) {
        if (trade.aggressor() != Aggressor.UNKNOWN) {
            return trade.aggressor() == Aggressor.BUYER;
        }
        OptionalInt bid = book.bestBid();
        OptionalInt ask = book.bestAsk();
        double price = trade.priceTicks();
        if (ask.isPresent() && price >= ask.getAsInt()) {
            return true;
        }
        if (bid.isPresent() && price <= bid.getAsInt()) {
            return false;
        }
        if (bid.isPresent() && ask.isPresent()) {
            return price - bid.getAsInt() >= ask.getAsInt() - price;
        }
        return true;
    }

    /** Bookmap принимает объём в {@code int}; больший объём на MOEX невозможен, но переполнение хуже насыщения. */
    static int clampSize(long size) {
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(size, 0);
    }

    private void post(Runnable task) {
        try {
            dispatcher.execute(() -> {
                if (!closed) {
                    task.run();
                }
            });
        } catch (RejectedExecutionException e) {
            // Диспетчер остановлен: провайдер закрыт, событие больше некому отдавать.
        }
    }
}
