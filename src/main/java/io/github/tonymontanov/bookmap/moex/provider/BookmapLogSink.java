package io.github.tonymontanov.bookmap.moex.provider;

import io.github.tonymontanov.bookmap.moex.shared.ConnectorLog;
import velox.api.layer1.common.Log;

/** Отдаёт журнал коннектора в журнал Bookmap (Help → Show log file). */
final class BookmapLogSink implements ConnectorLog.Sink {

    @Override
    public void info(String message) {
        Log.info(message);
    }

    @Override
    public void warn(String message, Throwable error) {
        if (error == null) {
            Log.warn(message);
        } else {
            Log.warn("MOEX", message, error);
        }
    }
}
