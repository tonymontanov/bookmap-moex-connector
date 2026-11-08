package io.github.tonymontanov.bookmap.moex.source.finam;

import io.github.tonymontanov.bookmap.moex.feed.SymbolQuery;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Нотация символов Finam: {@code ticker@mic}, например {@code SBER@MISX} или
 * {@code SiZ6@RTSX}.
 *
 * <p>Пользователь в окне подписки Bookmap пишет как привык: {@code SBER},
 * {@code SiZ6}, {@code SBER@TQBR}, указывает или не указывает биржу. Отсюда
 * получается список символов Finam, которые стоит проверить по порядку.
 * Регистр тикера сохраняется: у фьючерсов он значим ({@code SiZ6}), а MIC
 * приводится к верхнему.
 */
final class FinamSymbols {

    /** MIC срочного рынка MOEX (FORTS). */
    static final String MIC_FORTS = "RTSX";

    /** MIC фондового рынка MOEX. */
    static final String MIC_SHARES = "MISX";

    static final char SEPARATOR = '@';

    /** Привычные названия секций → MIC Finam. Неизвестное название передаётся как MIC. */
    private static final Map<String, String> EXCHANGE_ALIASES = Map.ofEntries(
            Map.entry("RTSX", MIC_FORTS),
            Map.entry("FORTS", MIC_FORTS),
            Map.entry("SPBFUT", MIC_FORTS),
            Map.entry("FUT", MIC_FORTS),
            Map.entry("FUTURES", MIC_FORTS),
            Map.entry("MISX", MIC_SHARES),
            Map.entry("TQBR", MIC_SHARES),
            Map.entry("SHARES", MIC_SHARES),
            Map.entry("STOCK", MIC_SHARES),
            Map.entry("STOCKS", MIC_SHARES),
            Map.entry("EQUITIES", MIC_SHARES));

    /** Биржа не указана или указана вся MOEX: проверяются обе секции. */
    private static final Set<String> ANY_SECTION = Set.of("", "MOEX");

    /**
     * Порядок проверки без биржи: сначала срочный рынок. Тикеры секций не
     * пересекаются ({@code SiZ6} нет на фондовом, {@code SBER} нет на срочном),
     * поэтому порядок влияет только на число запросов, а основной рынок
     * коннектора — фьючерсы.
     */
    private static final List<String> DEFAULT_ORDER = List.of(MIC_FORTS, MIC_SHARES);

    private FinamSymbols() {
    }

    /** Символы Finam для проверки по порядку; пустой список — запрос пуст или негоден. */
    static List<String> candidates(SymbolQuery query) {
        String symbol = query.symbol();
        if (symbol.isEmpty()) {
            return List.of();
        }
        int separator = symbol.indexOf(SEPARATOR);
        if (separator >= 0) {
            String ticker = symbol.substring(0, separator).strip();
            String mic = normalizeExchange(symbol.substring(separator + 1));
            return ticker.isEmpty() || mic.isEmpty() ? List.of() : List.of(join(ticker, mic));
        }
        String exchange = query.exchange().toUpperCase(Locale.ROOT);
        if (ANY_SECTION.contains(exchange)) {
            return DEFAULT_ORDER.stream().map(mic -> join(symbol, mic)).toList();
        }
        return List.of(join(symbol, normalizeExchange(exchange)));
    }

    static String join(String ticker, String mic) {
        return ticker + SEPARATOR + mic;
    }

    /** Тикер из символа {@code ticker@mic}. */
    static String ticker(String symbol) {
        int separator = symbol.indexOf(SEPARATOR);
        return separator < 0 ? symbol : symbol.substring(0, separator);
    }

    /** MIC из символа {@code ticker@mic}; пустой, если его нет. */
    static String mic(String symbol) {
        int separator = symbol.indexOf(SEPARATOR);
        return separator < 0 ? "" : symbol.substring(separator + 1);
    }

    private static String normalizeExchange(String exchange) {
        String upper = exchange.strip().toUpperCase(Locale.ROOT);
        return EXCHANGE_ALIASES.getOrDefault(upper, upper);
    }
}
