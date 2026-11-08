package io.github.tonymontanov.bookmap.moex.source.finam;

import static io.github.tonymontanov.bookmap.moex.source.finam.FinamBookMapperTest.decimal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import grpc.tradeapi.v1.assets.GetAssetResponse;
import io.github.tonymontanov.bookmap.moex.feed.InstrumentSpec;
import io.github.tonymontanov.bookmap.moex.feed.SourceException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FinamInstrumentsTest {

    @Test
    void priceStepIsMinStepScaledByDecimals() throws SourceException {
        InstrumentSpec spec = FinamInstruments.toSpec("SBER@MISX", GetAssetResponse.newBuilder()
                .setTicker("SBER")
                .setMic("MISX")
                .setType("EQUITIES")
                .setName("Сбербанк ао")
                .setDecimals(2)
                .setMinStep(1)
                .setLotSize(decimal("10"))
                .build());

        assertEquals("SBER@MISX", spec.alias());
        assertEquals(0, new BigDecimal("0.01").compareTo(spec.priceStep()));
        assertEquals(0, BigDecimal.TEN.compareTo(spec.lotSize()));
        assertEquals("EQUITIES", spec.type());
        assertEquals("Сбербанк ао", spec.fullName());
    }

    @Test
    void canonicalTickerFromTheGatewayWins() throws SourceException {
        InstrumentSpec spec = FinamInstruments.toSpec("sber@MISX", GetAssetResponse.newBuilder()
                .setTicker("SBER").setMic("MISX").setMinStep(1).setDecimals(2).build());

        assertEquals("SBER@MISX", spec.alias());
    }

    @Test
    void missingTickerAndMicFallBackToTheRequest() throws SourceException {
        InstrumentSpec spec = FinamInstruments.toSpec("SiZ6@RTSX", GetAssetResponse.newBuilder()
                .setMinStep(1).setDecimals(0).build());

        assertEquals("SiZ6@RTSX", spec.alias());
        assertEquals(0, BigDecimal.ONE.compareTo(spec.priceStep()));
        assertEquals(0, BigDecimal.ONE.compareTo(spec.lotSize()));
    }

    @Test
    void instrumentWithoutPriceStepIsRejected() {
        assertThrows(SourceException.class, () -> FinamInstruments.toSpec("X@MISX",
                GetAssetResponse.newBuilder().setMinStep(0).build()));
    }
}
