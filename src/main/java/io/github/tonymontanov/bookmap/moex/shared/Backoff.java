package io.github.tonymontanov.bookmap.moex.shared;

import java.time.Duration;
import java.util.Objects;

/**
 * Экспоненциальная пауза между попытками: каждая следующая вдвое длиннее
 * предыдущей, но не длиннее потолка. Сбрасывается, когда попытка удалась.
 *
 * <p>Не потокобезопасна: у каждого переподключаемого потока своя пауза, и
 * владелец обращается к ней под своей блокировкой.
 */
public final class Backoff {

    private final Duration initial;
    private final Duration max;
    private Duration next;

    public Backoff(Duration initial, Duration max) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(max, "max");
        if (initial.isNegative() || initial.isZero()) {
            throw new IllegalArgumentException("начальная пауза должна быть положительной: " + initial);
        }
        if (max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("потолок " + max + " меньше начальной паузы " + initial);
        }
        this.initial = initial;
        this.max = max;
        this.next = initial;
    }

    /** Пауза перед следующей попыткой; последующий вызов вернёт вдвое большую. */
    public Duration next() {
        Duration current = next;
        Duration doubled = current.multipliedBy(2);
        next = doubled.compareTo(max) > 0 ? max : doubled;
        return current;
    }

    /** Попытка удалась: следующая пауза снова начальная. */
    public void reset() {
        next = initial;
    }
}
