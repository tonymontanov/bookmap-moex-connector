package io.github.tonymontanov.bookmap.moex.source.finam;

import static io.github.tonymontanov.bookmap.moex.source.finam.FinamBookMapperTest.decimal;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.Timestamp;
import grpc.tradeapi.v1.Side;
import grpc.tradeapi.v1.marketdata.SubscribeLatestTradesResponse;
import grpc.tradeapi.v1.marketdata.Trade;
import io.github.tonymontanov.bookmap.moex.feed.InstrumentScale;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinamTradeMapperTest {

    private final FinamTradeMapper mapper = new FinamTradeMapper("SBER@MISX", new InstrumentScale(new BigDecimal("0.01")));
    private final RecordingFeedListener listener = new RecordingFeedListener();

    @Test
    void historySnapshotIsSkippedAndLiveTradesKeepAggressor() {
        mapper.dispatch(SubscribeLatestTradesResponse.newBuilder()
                .setSymbol("SBER@MISX")
                .addTrades(trade("300.00", "1", Side.SIDE_BUY, true))
                .addTrades(trade("300.12", "15", Side.SIDE_BUY, false))
                .addTrades(trade("300.11", "2", Side.SIDE_SELL, false))
                .addTrades(trade("300.11", "3", Side.SIDE_UNSPECIFIED, false))
                .build(), listener);

        assertEquals(List.of(
                "trade 30012.0 x15 BUYER @1762600000",
                "trade 30011.0 x2 SELLER @1762600000",
                "trade 30011.0 x3 UNKNOWN @1762600000"), listener.drain());
    }

    private static Trade trade(String price, String size, Side side, boolean snapshot) {
        return Trade.newBuilder()
                .setPrice(decimal(price))
                .setSize(decimal(size))
                .setSide(side)
                .setIsDataSnapshot(snapshot)
                .setTimestamp(Timestamp.newBuilder().setSeconds(1_762_600_000L))
                .build();
    }
}
