package io.github.tonymontanov.bookmap.moex.source;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Брокерский шлюз, через который читается MOEX.
 *
 * <p>Площадка одна, шлюзов несколько, и они не эквивалентны: Finam отдаёт
 * снимок стакана и построчные изменения, T-Invest — снимки до 50 уровней не
 * чаще раза в 100 мс. Новый шлюз — новая константа здесь и ветка в
 * {@link MarketDataSources}.
 */
public enum SourceKind {
    FINAM("Finam");

    private final String title;

    SourceKind(String title) {
        this.title = title;
    }

    /** Имя шлюза в окне входа и сообщениях. */
    public String title() {
        return title;
    }

    public static List<String> titles() {
        return Arrays.stream(values()).map(SourceKind::title).toList();
    }

    /** Шлюз по имени из окна входа; регистр и пробелы не важны. */
    public static Optional<SourceKind> fromTitle(String title) {
        if (title == null) {
            return Optional.empty();
        }
        String normalized = title.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(kind -> kind.title.toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst();
    }
}
