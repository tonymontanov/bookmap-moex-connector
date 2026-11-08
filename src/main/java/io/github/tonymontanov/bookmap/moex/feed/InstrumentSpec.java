package io.github.tonymontanov.bookmap.moex.feed;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Спецификация инструмента, по которой источник переводит цены в шаги и
 * провайдер описывает инструмент Bookmap.
 *
 * @param alias     ключ инструмента в Bookmap и символ подписки у шлюза: {@code SBER@MISX}
 * @param ticker    тикер: {@code SBER}, {@code SiZ6}
 * @param exchange  MIC площадки: {@code MISX} — фондовый рынок, {@code RTSX} — срочный
 * @param type      тип инструмента в классификации шлюза: {@code EQUITIES}, {@code FUTURES} …
 * @param fullName  полное наименование; может быть пустым
 * @param priceStep минимальный шаг цены, больше нуля
 * @param lotSize   штук в лоте; 1, если шлюз не сообщил
 */
public record InstrumentSpec(
        String alias,
        String ticker,
        String exchange,
        String type,
        String fullName,
        BigDecimal priceStep,
        BigDecimal lotSize) {

    public InstrumentSpec {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(ticker, "ticker");
        Objects.requireNonNull(exchange, "exchange");
        type = type == null ? "" : type;
        fullName = fullName == null ? "" : fullName;
        Objects.requireNonNull(priceStep, "priceStep");
        if (priceStep.signum() <= 0) {
            throw new IllegalArgumentException("шаг цены " + alias + " должен быть положительным: " + priceStep);
        }
        lotSize = lotSize == null || lotSize.signum() <= 0 ? BigDecimal.ONE : lotSize;
    }
}
