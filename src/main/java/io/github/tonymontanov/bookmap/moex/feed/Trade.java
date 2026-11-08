package io.github.tonymontanov.bookmap.moex.feed;

import java.time.Instant;
import java.util.Objects;

/**
 * Сделка из ленты.
 *
 * @param priceTicks   цена в шагах цены; дробная, если сделка прошла вне сетки шага
 * @param size         объём в единицах шлюза
 * @param aggressor    инициатор сделки
 * @param exchangeTime метка биржи; для Bookmap не используется (он ставит метку
 *                     в момент вызова), но нужна журналу и тестам
 */
public record Trade(double priceTicks, long size, Aggressor aggressor, Instant exchangeTime) {

    public Trade {
        Objects.requireNonNull(aggressor, "aggressor");
        Objects.requireNonNull(exchangeTime, "exchangeTime");
    }
}
