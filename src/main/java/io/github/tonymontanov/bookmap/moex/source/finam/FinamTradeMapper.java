package io.github.tonymontanov.bookmap.moex.source.finam;

import com.google.protobuf.Timestamp;
import grpc.tradeapi.v1.marketdata.SubscribeLatestTradesResponse;
import grpc.tradeapi.v1.marketdata.Trade;
import io.github.tonymontanov.bookmap.moex.feed.Aggressor;
import io.github.tonymontanov.bookmap.moex.feed.FeedListener;
import io.github.tonymontanov.bookmap.moex.feed.InstrumentScale;
import io.github.tonymontanov.bookmap.moex.shared.ConnectorLog;
import java.time.Instant;

/**
 * Сообщения стрима {@code SubscribeLatestTrades} → сделки {@link FeedListener}.
 *
 * <p>Сразу после подписки шлюз присылает последние сделки с
 * {@code is_data_snapshot}. Они пропускаются: Bookmap ставит событию метку в
 * момент вызова, и история легла бы в ленту как только что прошедшие сделки.
 * Сторона сделки ({@code side}) — направление агрессора.
 */
final class FinamTradeMapper {

    private final String alias;
    private final InstrumentScale scale;
    private boolean warnedMalformedTrade;

    FinamTradeMapper(String alias, InstrumentScale scale) {
        this.alias = alias;
        this.scale = scale;
    }

    void dispatch(SubscribeLatestTradesResponse response, FeedListener listener) {
        for (Trade trade : response.getTradesList()) {
            if (trade.getIsDataSnapshot()) {
                continue;
            }
            try {
                listener.onTrade(new io.github.tonymontanov.bookmap.moex.feed.Trade(
                        scale.fractionalTicks(FinamDecimals.parse(trade.getPrice())),
                        InstrumentScale.size(FinamDecimals.parse(trade.getSize())),
                        aggressor(trade),
                        instant(trade.getTimestamp())));
            } catch (ArithmeticException | IllegalArgumentException e) {
                if (!warnedMalformedTrade) {
                    warnedMalformedTrade = true;
                    ConnectorLog.warn("Finam сделки " + alias + ": сделка не разобрана: " + e.getMessage()
                            + " (" + trade.toString().replace('\n', ' ') + "); дальнейшие такие пропускаются молча");
                }
            }
        }
    }

    private static Aggressor aggressor(Trade trade) {
        return switch (trade.getSide()) {
            case SIDE_BUY -> Aggressor.BUYER;
            case SIDE_SELL -> Aggressor.SELLER;
            case SIDE_UNSPECIFIED, UNRECOGNIZED -> Aggressor.UNKNOWN;
        };
    }

    private static Instant instant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
