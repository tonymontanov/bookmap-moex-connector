package io.github.tonymontanov.bookmap.moex.source;

import io.github.tonymontanov.bookmap.moex.feed.MarketDataSource;
import io.github.tonymontanov.bookmap.moex.source.finam.FinamSource;

/** Создаёт источник данных по выбранному в окне входа шлюзу. */
public final class MarketDataSources {

    private MarketDataSources() {
    }

    /** Источник ещё не подключён: следующий шаг — {@link MarketDataSource#connect()}. */
    public static MarketDataSource create(SourceSettings settings) {
        return switch (settings.kind()) {
            case FINAM -> new FinamSource(settings.secret(), settings.accountId());
        };
    }
}
