package io.github.tonymontanov.bookmap.moex.source.finam;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.tonymontanov.bookmap.moex.feed.SymbolQuery;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinamSymbolsTest {

    @Test
    void bareTickerChecksFuturesThenShares() {
        assertEquals(List.of("SiZ6@RTSX", "SiZ6@MISX"), candidates("SiZ6", ""));
        assertEquals(List.of("SBER@RTSX", "SBER@MISX"), candidates(" SBER ", "moex"));
    }

    @Test
    void exchangeFieldPicksTheSection() {
        assertEquals(List.of("SBER@MISX"), candidates("SBER", "TQBR"));
        assertEquals(List.of("SiZ6@RTSX"), candidates("SiZ6", "forts"));
        assertEquals(List.of("GAZP@XNAS"), candidates("GAZP", "xnas"));
    }

    @Test
    void fullSymbolIsUsedAsIsWithNormalizedMic() {
        assertEquals(List.of("SBER@MISX"), candidates("SBER@misx", "RTSX"));
        assertEquals(List.of("SBER@MISX"), candidates("SBER@TQBR", ""));
    }

    @Test
    void emptyOrBrokenSymbolsGiveNothing() {
        assertEquals(List.of(), candidates("", ""));
        assertEquals(List.of(), candidates("@MISX", ""));
        assertEquals(List.of(), candidates("SBER@", ""));
    }

    @Test
    void splitsSymbolIntoTickerAndMic() {
        assertEquals("SiZ6", FinamSymbols.ticker("SiZ6@RTSX"));
        assertEquals("RTSX", FinamSymbols.mic("SiZ6@RTSX"));
        assertEquals("", FinamSymbols.mic("SiZ6"));
    }

    private static List<String> candidates(String symbol, String exchange) {
        return FinamSymbols.candidates(new SymbolQuery(symbol, exchange, ""));
    }
}
