package io.github.tonymontanov.bookmap.moex.transport;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.util.internal.logging.InternalLoggerFactory;
import io.grpc.netty.shaded.io.netty.util.internal.logging.JdkLoggerFactory;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Каналы gRPC к брокерским шлюзам.
 *
 * <p>Канал строится напрямую через {@link NettyChannelBuilder}, а не через
 * {@code ManagedChannelBuilder.forTarget}: тот ищет транспорт сервис-лоадером,
 * а внутри Bookmap модуль загружен своим загрузчиком классов, и поиск по
 * контекстному загрузчику потока может не увидеть провайдер из нашего jar.
 */
public final class GrpcChannels {

    /**
     * Как часто слать ping HTTP/2 в соединение с открытыми стримами. Служебного
     * heartbeat в стримах брокеров нет, а ночью инструмент молчит часами: без
     * ping мёртвое соединение не отличить от тихого рынка. Если сервер сочтёт
     * ping слишком частым (GOAWAY too_many_pings), gRPC сам удвоит интервал.
     */
    static final Duration KEEPALIVE_TIME = Duration.ofSeconds(30);

    /** Сколько ждать ответа на ping, прежде чем считать соединение мёртвым. */
    static final Duration KEEPALIVE_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Предел размера входящего сообщения. Снимок стакана с сотнями уровней и
     * справочник укладываются в единицы мегабайт; умолчание gRPC (4 МиБ)
     * оставляет мало запаса.
     */
    static final int MAX_INBOUND_MESSAGE_BYTES = 32 * 1024 * 1024;

    static {
        // Netty внутри gRPC сам ищет SLF4J и в Bookmap находит его logback,
        // который без конфигурации пишет DEBUG. На DEBUG Netty журналирует
        // кадры HTTP/2 целиком: тело запроса авторизации с секретом API (hex)
        // и заголовок authorization с JWT. Журнал java.util.logging по
        // умолчанию DEBUG не пишет. Настройка действует только на копию Netty
        // внутри модуля и не трогает журналирование Bookmap.
        InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE);
    }

    private GrpcChannels() {
    }

    /** Канал TLS к {@code host:port} с проверкой сертификата по системному хранилищу JRE. */
    public static ManagedChannel tls(String host, int port, String userAgent) {
        return NettyChannelBuilder.forAddress(host, port)
                .useTransportSecurity()
                .userAgent(userAgent)
                .keepAliveTime(KEEPALIVE_TIME.toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveTimeout(KEEPALIVE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .maxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES)
                .build();
    }
}
