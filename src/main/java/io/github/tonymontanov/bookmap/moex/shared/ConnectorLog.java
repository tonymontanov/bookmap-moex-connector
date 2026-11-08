package io.github.tonymontanov.bookmap.moex.shared;

import java.util.Objects;

/**
 * Журнал коннектора.
 *
 * <p>Слои ниже провайдера не знают о Bookmap и пишут сюда. Провайдер при
 * создании подключает приёмник, который отдаёт строки в журнал Bookmap; без
 * приёмника (тесты, отладка вне Bookmap) строки идут в stderr.
 *
 * <p>Секреты и JWT сюда не передаются никогда — ни целиком, ни частью. Маскировать
 * на месте записи поздно: строка с секретом уже собрана и могла уйти в исключение.
 */
public final class ConnectorLog {

    /** Приёмник строк журнала. */
    public interface Sink {
        void info(String message);

        void warn(String message, Throwable error);
    }

    private static final String PREFIX = "[MOEX] ";

    private static final Sink STDERR = new Sink() {
        @Override
        public void info(String message) {
            System.err.println(message);
        }

        @Override
        public void warn(String message, Throwable error) {
            System.err.println(message);
            if (error != null) {
                error.printStackTrace(System.err);
            }
        }
    };

    private static volatile Sink sink = STDERR;

    private ConnectorLog() {
    }

    /** Подключает приёмник. Последний подключённый действует для всего процесса. */
    public static void install(Sink newSink) {
        sink = Objects.requireNonNull(newSink, "sink");
    }

    public static void info(String message) {
        sink.info(PREFIX + message);
    }

    public static void warn(String message) {
        sink.warn(PREFIX + message, null);
    }

    public static void warn(String message, Throwable error) {
        sink.warn(PREFIX + message, error);
    }
}
