package io.github.tonymontanov.bookmap.moex.shared;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Фабрика потоков коннектора: демоны с узнаваемым именем {@code moex-<роль>-N}.
 *
 * <p>Демоны — потому что Bookmap может выгрузить модуль без вызова {@code close()}
 * (аварийное завершение), и недемонический поток держал бы процесс. Имя —
 * потому что в дампе потоков Bookmap их сотни, и свои нужно находить сразу.
 */
public final class DaemonThreads {

    private DaemonThreads() {
    }

    public static ThreadFactory named(String role) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "moex-" + role + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
