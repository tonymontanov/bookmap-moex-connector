package io.github.tonymontanov.bookmap.moex.source.finam;

import grpc.tradeapi.v1.assets.AssetsServiceGrpc;
import grpc.tradeapi.v1.assets.GetAssetRequest;
import grpc.tradeapi.v1.assets.GetAssetResponse;
import io.github.tonymontanov.bookmap.moex.feed.InstrumentSpec;
import io.github.tonymontanov.bookmap.moex.feed.SourceException;
import io.github.tonymontanov.bookmap.moex.feed.SymbolQuery;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Справочник инструментов Finam: запрос пользователя → {@link InstrumentSpec}.
 *
 * <p>Спецификацию отдаёт {@code AssetsService/GetAsset}. Шаг цены в ней —
 * целое {@code min_step} при {@code decimals} знаках: шаг = min_step / 10^decimals.
 * Список инструментов ({@code Assets}) шага не несёт, поэтому без запроса по
 * символу не обойтись.
 *
 * <p>{@code GetAsset} требует торговый счёт: без {@code account_id} шлюз
 * отвечает 400 «Invalid arguments: account_id», хотя в контракте поле
 * необязательное (проверено 12.09.2026). Счёт берётся из сведений о токене
 * или из окна входа.
 */
final class FinamInstruments {

    private final AssetsServiceGrpc.AssetsServiceBlockingStub assets;

    FinamInstruments(Channel channel, FinamSession session) {
        this.assets = AssetsServiceGrpc.newBlockingStub(channel).withCallCredentials(session.credentials());
    }

    /**
     * Находит инструмент: проверяет символы-кандидаты по порядку, первый
     * найденный — ответ.
     */
    InstrumentSpec resolve(SymbolQuery query, String accountId) throws SourceException {
        List<String> candidates = FinamSymbols.candidates(query);
        if (candidates.isEmpty()) {
            throw new SourceException(SourceException.Reason.NOT_FOUND,
                    "Пустой или неверный символ «" + query.describe() + "». Пример: SBER, SiZ6, SBER@MISX");
        }
        Status lastMiss = Status.NOT_FOUND;
        for (String symbol : candidates) {
            try {
                GetAssetResponse asset = assets
                        .withDeadlineAfter(FinamSession.CALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .getAsset(GetAssetRequest.newBuilder().setSymbol(symbol).setAccountId(accountId).build());
                return toSpec(symbol, asset);
            } catch (StatusRuntimeException e) {
                Status status = e.getStatus();
                if (status.getCode() != Status.Code.NOT_FOUND && status.getCode() != Status.Code.INVALID_ARGUMENT) {
                    throw FinamStatus.toException("инструмент " + symbol, status);
                }
                lastMiss = status;
            }
        }
        throw new SourceException(SourceException.Reason.NOT_FOUND,
                "Finam не нашёл " + String.join(" / ", candidates) + ": " + FinamStatus.describe(lastMiss)
                        + accountHint(lastMiss, accountId));
    }

    /** Спецификация из ответа шлюза; символ запроса — запасной источник тикера и MIC. */
    static InstrumentSpec toSpec(String requestedSymbol, GetAssetResponse asset) throws SourceException {
        if (asset.getMinStep() <= 0 || asset.getDecimals() < 0) {
            throw new SourceException(SourceException.Reason.OTHER, "Finam не сообщил шаг цены " + requestedSymbol
                    + " (min_step=" + asset.getMinStep() + ", decimals=" + asset.getDecimals() + ")");
        }
        String ticker = asset.getTicker().isEmpty() ? FinamSymbols.ticker(requestedSymbol) : asset.getTicker();
        String mic = asset.getMic().isEmpty() ? FinamSymbols.mic(requestedSymbol) : asset.getMic();
        BigDecimal priceStep = BigDecimal.valueOf(asset.getMinStep(), asset.getDecimals());
        BigDecimal lotSize;
        try {
            lotSize = FinamDecimals.parse(asset.getLotSize());
        } catch (NumberFormatException e) {
            lotSize = BigDecimal.ONE;
        }
        return new InstrumentSpec(
                FinamSymbols.join(ticker, mic), ticker, mic, asset.getType(), asset.getName(), priceStep, lotSize);
    }

    private static String accountHint(Status status, String accountId) {
        String description = status.getDescription();
        boolean aboutAccount = description != null && description.toLowerCase(Locale.ROOT).contains("account");
        if (!aboutAccount) {
            return "";
        }
        return accountId.isEmpty()
                ? ". Шлюзу нужен торговый счёт: укажите его в поле «Счёт» окна входа"
                : ". Шлюз не принял счёт " + accountId + ": проверьте поле «Счёт» окна входа";
    }
}
