package io.github.tonymontanov.bookmap.moex.source.finam;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.type.Decimal;
import grpc.tradeapi.v1.marketdata.StreamOrderBook;
import grpc.tradeapi.v1.marketdata.StreamOrderBook.Row;
import grpc.tradeapi.v1.marketdata.StreamOrderBook.Row.Action;
import grpc.tradeapi.v1.marketdata.SubscribeOrderBookResponse;
import io.github.tonymontanov.bookmap.moex.feed.InstrumentScale;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinamBookMapperTest {

    private final FinamBookMapper mapper = new FinamBookMapper("SiZ6@RTSX", new InstrumentScale(new BigDecimal("0.5")));
    private final RecordingFeedListener listener = new RecordingFeedListener();

    @Test
    void snapshotBecomesOneSnapshotCallWithSidesSplit() {
        mapper.dispatch(response(true,
                buy("100.5", "10", Action.ACTION_ADD),
                sell("101", "3", Action.ACTION_ADD),
                buy("100.0", "5", Action.ACTION_ADD)), listener);

        assertEquals(List.of("snapshot bids=[201:10,200:5] asks=[202:3]"), listener.drain());
    }

    @Test
    void changesBecomeLevelCalls() {
        mapper.dispatch(response(false,
                buy("100", "7", Action.ACTION_UPDATE),
                sell("101", "4", Action.ACTION_ADD),
                sell("101.5", "", Action.ACTION_REMOVE),
                row("100.5", Action.ACTION_REMOVE)), listener);

        assertEquals(List.of(
                "level BID 200 7",
                "level ASK 202 4",
                "level ASK 203 0",
                "removed 201"), listener.drain());
    }

    @Test
    void zeroSizedUpdateRemovesTheLevel() {
        mapper.dispatch(response(false, buy("100", "0", Action.ACTION_UPDATE)), listener);

        assertEquals(List.of("level BID 200 0"), listener.drain());
    }

    @Test
    void malformedRowsAreSkippedWithoutBreakingTheMessage() {
        mapper.dispatch(response(false,
                row("100", Action.ACTION_ADD),
                buy("не число", "1", Action.ACTION_ADD),
                buy("100", "2", Action.ACTION_ADD)), listener);

        assertEquals(List.of("level BID 200 2"), listener.drain());
    }

    private static SubscribeOrderBookResponse response(boolean snapshot, Row... rows) {
        return SubscribeOrderBookResponse.newBuilder()
                .addOrderBook(StreamOrderBook.newBuilder()
                        .setSymbol("SiZ6@RTSX")
                        .setIsDataSnapshot(snapshot)
                        .addAllRows(List.of(rows)))
                .build();
    }

    private static Row buy(String price, String size, Action action) {
        return Row.newBuilder().setPrice(decimal(price)).setBuySize(decimal(size)).setAction(action).build();
    }

    private static Row sell(String price, String size, Action action) {
        return Row.newBuilder().setPrice(decimal(price)).setSellSize(decimal(size)).setAction(action).build();
    }

    private static Row row(String price, Action action) {
        return Row.newBuilder().setPrice(decimal(price)).setAction(action).build();
    }

    static Decimal decimal(String value) {
        return Decimal.newBuilder().setValue(value).build();
    }
}
