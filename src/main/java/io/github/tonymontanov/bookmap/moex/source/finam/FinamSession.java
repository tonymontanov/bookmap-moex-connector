package io.github.tonymontanov.bookmap.moex.source.finam;

import grpc.tradeapi.v1.auth.AuthRequest;
import grpc.tradeapi.v1.auth.AuthResponse;
import grpc.tradeapi.v1.auth.AuthServiceGrpc;
import grpc.tradeapi.v1.auth.TokenDetailsRequest;
import grpc.tradeapi.v1.auth.TokenDetailsResponse;
import io.github.tonymontanov.bookmap.moex.feed.SourceException;
import io.grpc.CallCredentials;
import io.grpc.Channel;
import io.grpc.Metadata;
import io.grpc.Status;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Авторизация в Finam Trade API.
 *
 * <p>Схема двухэтапная. Секрет из раздела «Токены» портала обменивается на JWT
 * ({@code AuthService/Auth}), и уже JWT идёт заголовком {@code authorization}
 * в каждом запросе данных — голой строкой, без {@code Bearer}: так в примерах
 * самого Finam, и так проверено живьём в bdpb-research-lab 12.09.2026.
 *
 * <p>JWT живёт 15 минут. Он обновляется заранее, по возрасту в 10 минут, и
 * немедленно — если шлюз его отверг ({@link #invalidate()}): обновить раньше
 * дешевле, чем получить UNAUTHENTICATED на открытии стрима. Обновление ленивое
 * — при первом запросе после истечения возраста, — поэтому без подписок
 * сессия шлюз не беспокоит.
 */
final class FinamSession {

    /** Возраст, после которого JWT обновляется до следующего запроса. Жизнь JWT — 15 минут. */
    static final Duration TOKEN_REFRESH_AGE = Duration.ofMinutes(10);

    /** Предел ожидания ответа на одиночный запрос. */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);

    /** Метка приложения в запросе авторизации: по ней Finam различает клиентов API. */
    static final String SOURCE_APP_ID = "bookmap-moex-connector";

    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final AuthServiceGrpc.AuthServiceBlockingStub auth;
    private final String secret;
    private final LongSupplier nanoClock;

    // Под this.
    private String token;
    private long issuedAtNanos;
    private boolean rejected;

    FinamSession(Channel channel, String secret) {
        this(channel, secret, System::nanoTime);
    }

    FinamSession(Channel channel, String secret, LongSupplier nanoClock) {
        this.auth = AuthServiceGrpc.newBlockingStub(channel);
        this.secret = Objects.requireNonNull(secret, "secret");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * Действующий JWT: обновляется, если его нет, он старше
     * {@link #TOKEN_REFRESH_AGE} или отвергнут шлюзом. Блокирует на время
     * запроса авторизации.
     */
    synchronized String token() throws SourceException {
        long now = nanoClock.getAsLong();
        if (token == null || rejected || now - issuedAtNanos >= TOKEN_REFRESH_AGE.toNanos()) {
            AuthResponse response = authenticate();
            if (response.getToken().isEmpty()) {
                throw new SourceException(SourceException.Reason.AUTH, "Finam вернул пустой токен на верный секрет");
            }
            token = response.getToken();
            issuedAtNanos = now;
            rejected = false;
        }
        return token;
    }

    /** Шлюз отверг JWT: следующий {@link #token()} получит новый. */
    synchronized void invalidate() {
        rejected = true;
    }

    /**
     * Сведения о токене: счета, права на рыночные данные, только ли чтение.
     * Запрос идёт без заголовка авторизации, с токеном в теле: с заголовком
     * шлюз отвечает «Token is invalid or malformed» (проверено 12.09.2026).
     */
    TokenDetailsResponse details() throws SourceException {
        String current = token();
        return FinamStatus.call("сведения о токене", () -> auth
                .withDeadlineAfter(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .tokenDetails(TokenDetailsRequest.newBuilder().setToken(current).build()));
    }

    /** Учётные данные вызова: подставляют действующий JWT в каждый запрос данных. */
    CallCredentials credentials() {
        return new CallCredentials() {
            @Override
            public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
                appExecutor.execute(() -> {
                    try {
                        Metadata headers = new Metadata();
                        headers.put(AUTHORIZATION, token());
                        applier.apply(headers);
                    } catch (SourceException e) {
                        Status status = e.reason() == SourceException.Reason.AUTH
                                ? Status.UNAUTHENTICATED
                                : Status.UNAVAILABLE;
                        applier.fail(status.withDescription(e.getMessage()));
                    } catch (RuntimeException e) {
                        applier.fail(Status.UNAVAILABLE.withDescription("не удалось получить JWT").withCause(e));
                    }
                });
            }
        };
    }

    /**
     * Обмен секрета на JWT. Любой отказ, кроме сетевого, — неверный секрет:
     * на испорченный секрет шлюз отвечает и UNAUTHENTICATED, и INVALID_ARGUMENT.
     */
    private AuthResponse authenticate() throws SourceException {
        try {
            return FinamStatus.call("авторизация", () -> auth
                    .withDeadlineAfter(CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .auth(AuthRequest.newBuilder().setSecret(secret).setSourceAppId(SOURCE_APP_ID).build()));
        } catch (SourceException e) {
            if (e.reason() == SourceException.Reason.NETWORK || e.reason() == SourceException.Reason.RATE_LIMIT) {
                throw e;
            }
            throw new SourceException(SourceException.Reason.AUTH, e.getMessage(), e.getCause());
        }
    }
}
