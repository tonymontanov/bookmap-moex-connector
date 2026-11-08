package io.github.tonymontanov.bookmap.moex.source.finam;

import grpc.tradeapi.v1.marketdata.StreamOrderBook;
import grpc.tradeapi.v1.marketdata.SubscribeOrderBookResponse;
import io.github.tonymontanov.bookmap.moex.feed.FeedListener;
import io.github.tonymontanov.bookmap.moex.feed.InstrumentScale;
import io.github.tonymontanov.bookmap.moex.feed.Level;
import io.github.tonymontanov.bookmap.moex.feed.Side;
import io.github.tonymontanov.bookmap.moex.shared.ConnectorLog;
import java.util.ArrayList;
import java.util.List;

/**
 * Сообщения стрима {@code SubscribeOrderBook} → вызовы {@link FeedListener}.
 *
 * <p>Шлюз шлёт стакан двумя видами сообщений. Сообщение с
 * {@code is_data_snapshot} — полный снимок: при подписке, после
 * переподключения и раз в 60 секунд (замер 12.09.2026); его строки все
 * {@code ACTION_ADD}, порядок не биржевой. Остальные — построчные изменения:
 * ADD и UPDATE несут новый объём уровня, REMOVE удаляет уровень. Сторону
 * строки задаёт заполненное поле {@code buy_size} или {@code sell_size}.
 *
 * <p>Не проверено живьём: как выглядят изменения на торгующемся рынке (в
 * записях 12.09 были только снимки — суббота) и указывает ли REMOVE сторону.
 * Поэтому строка удаления без стороны снимает цену с той стороны, где она
 * есть, а строка добавления без стороны пропускается с предупреждением —
 * ближайший снимок всё равно выровняет стакан.
 */
final class FinamBookMapper {

    private final String alias;
    private final InstrumentScale scale;
    private boolean warnedMalformedRow;

    FinamBookMapper(String alias, InstrumentScale scale) {
        this.alias = alias;
        this.scale = scale;
    }

    void dispatch(SubscribeOrderBookResponse response, FeedListener listener) {
        for (StreamOrderBook book : response.getOrderBookList()) {
            if (book.getIsDataSnapshot()) {
                dispatchSnapshot(book, listener);
            } else {
                dispatchChanges(book, listener);
            }
        }
    }

    private void dispatchSnapshot(StreamOrderBook book, FeedListener listener) {
        List<Level> bids = new ArrayList<>();
        List<Level> asks = new ArrayList<>();
        for (StreamOrderBook.Row row : book.getRowsList()) {
            if (row.getAction() == StreamOrderBook.Row.Action.ACTION_REMOVE) {
                continue;
            }
            Side side = side(row);
            if (side == null) {
                warnMalformed("строка снимка без стороны", row);
                continue;
            }
            try {
                Level level = new Level(scale.ticks(FinamDecimals.parse(row.getPrice())), size(row, side));
                (side == Side.BID ? bids : asks).add(level);
            } catch (ArithmeticException | IllegalArgumentException e) {
                warnMalformed("строка снимка не разобрана: " + e.getMessage(), row);
            }
        }
        listener.onBookSnapshot(List.copyOf(bids), List.copyOf(asks));
    }

    private void dispatchChanges(StreamOrderBook book, FeedListener listener) {
        for (StreamOrderBook.Row row : book.getRowsList()) {
            try {
                int ticks = scale.ticks(FinamDecimals.parse(row.getPrice()));
                Side side = side(row);
                if (row.getAction() == StreamOrderBook.Row.Action.ACTION_REMOVE) {
                    if (side == null) {
                        listener.onBookPriceRemoved(ticks);
                    } else {
                        listener.onBookLevel(side, ticks, 0);
                    }
                } else if (side == null) {
                    warnMalformed("строка изменения без стороны", row);
                } else {
                    listener.onBookLevel(side, ticks, size(row, side));
                }
            } catch (ArithmeticException | IllegalArgumentException e) {
                warnMalformed("строка изменения не разобрана: " + e.getMessage(), row);
            }
        }
    }

    private static Side side(StreamOrderBook.Row row) {
        return switch (row.getSideCase()) {
            case BUY_SIZE -> Side.BID;
            case SELL_SIZE -> Side.ASK;
            case SIDE_NOT_SET -> null;
        };
    }

    private static long size(StreamOrderBook.Row row, Side side) {
        return InstrumentScale.size(FinamDecimals.parse(side == Side.BID ? row.getBuySize() : row.getSellSize()));
    }

    /** Предупреждает один раз на подписку: стрим с испорченными строками иначе засыпал бы журнал. */
    private void warnMalformed(String problem, StreamOrderBook.Row row) {
        if (warnedMalformedRow) {
            return;
        }
        warnedMalformedRow = true;
        ConnectorLog.warn("Finam стакан " + alias + ": " + problem + " (" + row.toString().replace('\n', ' ')
                + "); дальнейшие такие строки пропускаются молча");
    }
}
