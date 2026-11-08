package io.github.tonymontanov.bookmap.moex.provider;

import io.github.tonymontanov.bookmap.moex.feed.InstrumentSpec;
import io.github.tonymontanov.bookmap.moex.feed.MarketDataSource;
import io.github.tonymontanov.bookmap.moex.feed.SourceException;
import io.github.tonymontanov.bookmap.moex.feed.SymbolQuery;
import io.github.tonymontanov.bookmap.moex.shared.ConnectorLog;
import io.github.tonymontanov.bookmap.moex.shared.DaemonThreads;
import io.github.tonymontanov.bookmap.moex.source.MarketDataSources;
import io.github.tonymontanov.bookmap.moex.source.SourceSettings;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import velox.api.layer0.annotations.Layer0CredentialsFieldsManager;
import velox.api.layer0.annotations.Layer0LiveModule;
import velox.api.layer0.live.ExternalLiveBaseProvider;
import velox.api.layer1.Layer1ApiAdminListener;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.data.DisconnectionReason;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.Layer1ApiProviderSupportedFeatures;
import velox.api.layer1.data.LoginData;
import velox.api.layer1.data.LoginFailedReason;
import velox.api.layer1.data.OrderSendParameters;
import velox.api.layer1.data.OrderUpdateParameters;
import velox.api.layer1.data.SubscribeInfo;
import velox.api.layer1.data.SystemTextMessageType;
import velox.api.layer1.data.TradeInfo;

/**
 * Модуль Bookmap «MOEX»: живые данные Московской биржи через брокерский шлюз.
 *
 * <p>Потоки. У провайдера два своих потока. Рабочий ({@code moex-io}) делает
 * блокирующие запросы к шлюзу — вход и поиск инструмента, — чтобы не держать
 * поток Bookmap, из которого пришёл вызов. Диспетчер ({@code moex-dispatch})
 * владеет всем состоянием подписок и один вызывает слушателей Bookmap: так
 * события стакана и ленты разных стримов доходят до Bookmap по одному и в
 * порядке прихода. Карта {@link #instruments} читается ещё и из
 * {@link #formatPrice} на потоке Bookmap, поэтому она конкурентная.
 *
 * <p>Торговли нет: первая версия отдаёт только рыночные данные.
 *
 * <p>{@link Layer1ApiVersion} обязательна. Bookmap 7.9 читает её из байткода
 * до загрузки модуля и без неё отказывается грузить внешний L0-модуль
 * («Layer1ApiVersion annotation is absent»), а его же показ этой ошибки
 * падает с NullPointerException и вешает Bookmap на старте (08.11.2026).
 * VERSION3 — последняя в 7.9: модуль грузится загрузчиком child-first, и
 * зависимости модуля берутся из его jar раньше одноимённых из Bookmap.
 */
@Layer0LiveModule(fullName = "MOEX (Finam)", shortName = "MOEX")
@Layer0CredentialsFieldsManager(MoexCredentialsFields.class)
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION3)
public final class MoexLiveProvider extends ExternalLiveBaseProvider {

    /** Имя источника данных провайдера в Bookmap. */
    static final String SOURCE_NAME = "MOEX";

    private final ExecutorService io = Executors.newSingleThreadExecutor(DaemonThreads.named("io"));
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(DaemonThreads.named("dispatch"));
    private final BookmapOutput output = new ListenersOutput();

    /** Подписанные инструменты по alias. Только на диспетчере. */
    private final Map<String, InstrumentFeed> feeds = new HashMap<>();

    /** Описания подписанных инструментов для {@link #formatPrice}. */
    private final Map<String, InstrumentInfo> instruments = new ConcurrentHashMap<>();

    /** Только на диспетчере. */
    private final ConnectionHealth health = new ConnectionHealth();

    private volatile MarketDataSource source;
    private volatile boolean closed;

    public MoexLiveProvider() {
        ConnectorLog.install(new BookmapLogSink());
    }

    @Override
    public void login(LoginData loginData) {
        SourceSettings settings;
        try {
            settings = MoexCredentialsFields.settings(loginData);
        } catch (IllegalArgumentException e) {
            reportLoginFailed(LoginFailedReason.WRONG_CREDENTIALS, e.getMessage());
            return;
        }
        runIo(() -> connect(settings));
    }

    @Override
    public void subscribe(SubscribeInfo subscribeInfo) {
        SymbolQuery query = new SymbolQuery(subscribeInfo.symbol, subscribeInfo.exchange, subscribeInfo.type);
        runIo(() -> resolve(subscribeInfo, query));
    }

    @Override
    public void unsubscribe(String alias) {
        runOnDispatcher(() -> {
            InstrumentFeed feed = feeds.remove(alias);
            if (feed == null) {
                return;
            }
            instruments.remove(alias);
            feed.close();
            instrumentListeners.forEach(listener -> listener.onInstrumentRemoved(alias));
            ConnectorLog.info(alias + ": подписка снята");
        });
    }

    @Override
    public String formatPrice(String alias, double price) {
        InstrumentInfo info = instruments.get(alias);
        return formatPriceDefault(info == null ? 1.0 : info.pips, price);
    }

    @Override
    public Layer1ApiProviderSupportedFeatures getSupportedFeatures() {
        return super.getSupportedFeatures().toBuilder()
                .setTrading(false)
                .setDepth(true)
                .setMbo(false)
                .setExchangeUsedForSubscription(true)
                .setTypeUsedForSubscription(false)
                .build();
    }

    @Override
    public String getSource() {
        return SOURCE_NAME;
    }

    @Override
    public void sendOrder(OrderSendParameters orderSendParameters) {
        throw new UnsupportedOperationException("Коннектор MOEX не торгует: только рыночные данные");
    }

    @Override
    public void updateOrder(OrderUpdateParameters orderUpdateParameters) {
        throw new UnsupportedOperationException("Коннектор MOEX не торгует: только рыночные данные");
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        io.shutdownNow();
        runOnDispatcher(() -> {
            feeds.values().forEach(InstrumentFeed::close);
            feeds.clear();
            instruments.clear();
            MarketDataSource current = source;
            source = null;
            if (current != null) {
                current.close();
            }
        });
        dispatcher.shutdown();
        ConnectorLog.info("Провайдер закрыт");
    }

    /** Вход: на рабочем потоке. */
    private void connect(SourceSettings settings) {
        MarketDataSource candidate = null;
        try {
            candidate = MarketDataSources.create(settings);
            candidate.connect();
            MarketDataSource connected = candidate;
            boolean accepted = runOnDispatcher(() -> {
                if (closed) {
                    connected.close();
                    return;
                }
                source = connected;
                adminListeners.forEach(Layer1ApiAdminListener::onLoginSuccessful);
            });
            if (!accepted) {
                // Провайдер закрыт, пока шёл вход: источник больше некому закрыть.
                connected.close();
            }
        } catch (SourceException e) {
            closeQuietly(candidate);
            ConnectorLog.warn("Вход не выполнен: " + e.getMessage());
            reportLoginFailed(loginFailedReason(e.reason()), e.getMessage());
        } catch (RuntimeException e) {
            closeQuietly(candidate);
            ConnectorLog.warn("Вход не выполнен из-за ошибки коннектора", e);
            reportLoginFailed(LoginFailedReason.FATAL, "Ошибка коннектора: " + e);
        }
    }

    /** Поиск инструмента: на рабочем потоке. */
    private void resolve(SubscribeInfo subscribeInfo, SymbolQuery query) {
        MarketDataSource current = source;
        if (current == null || closed) {
            runOnDispatcher(() -> instrumentListeners.forEach(listener -> listener.onInstrumentNotFound(
                    subscribeInfo.symbol, subscribeInfo.exchange, subscribeInfo.type)));
            return;
        }
        try {
            InstrumentSpec spec = current.resolve(query);
            runOnDispatcher(() -> attach(subscribeInfo, spec, current));
        } catch (SourceException e) {
            ConnectorLog.warn("Инструмент " + query.describe() + " не подписан: " + e.getMessage());
            runOnDispatcher(() -> {
                adminListeners.forEach(listener -> listener.onSystemTextMessage(
                        "MOEX: " + e.getMessage(), SystemTextMessageType.UNCLASSIFIED));
                instrumentListeners.forEach(listener -> listener.onInstrumentNotFound(
                        subscribeInfo.symbol, subscribeInfo.exchange, subscribeInfo.type));
            });
        }
    }

    /** Регистрирует инструмент в Bookmap и открывает его стримы: на диспетчере. */
    private void attach(SubscribeInfo subscribeInfo, InstrumentSpec spec, MarketDataSource current) {
        if (closed || current != source) {
            return;
        }
        String alias = spec.alias();
        if (feeds.containsKey(alias)) {
            instrumentListeners.forEach(listener -> listener.onInstrumentAlreadySubscribed(
                    subscribeInfo.symbol, subscribeInfo.exchange, subscribeInfo.type));
            return;
        }
        InstrumentInfo info = instrumentInfo(spec);
        InstrumentFeed feed = new InstrumentFeed(alias, dispatcher, output, health);
        feeds.put(alias, feed);
        instruments.put(alias, info);
        // Инструмент объявляется до первых данных: события стримов встают в
        // очередь диспетчера после этой задачи.
        instrumentListeners.forEach(listener -> listener.onInstrumentAdded(alias, info));
        feed.attach(current.subscribe(spec, feed));
        ConnectorLog.info(alias + ": подписка открыта; шаг цены " + spec.priceStep().toPlainString()
                + ", лот " + spec.lotSize().toPlainString() + ", тип " + spec.type());
    }

    /**
     * Описание инструмента для Bookmap. {@code multiplier} — стоимость единицы
     * цены для P&L — оставлен 1: без торговли P&L не считается, а стоимость
     * пункта фьючерса шлюз в справочнике не отдаёт.
     */
    static InstrumentInfo instrumentInfo(InstrumentSpec spec) {
        return new InstrumentInfo.Builder()
                .setSymbol(spec.ticker())
                .setExchange(spec.exchange())
                .setType(spec.type())
                .setPips(spec.priceStep().doubleValue())
                .setMultiplier(1.0)
                .setFullName(spec.fullName().isEmpty() ? spec.alias() : spec.fullName())
                .setFullDepth(false)
                .setSizeMultiplier(1.0)
                .build();
    }

    private static LoginFailedReason loginFailedReason(SourceException.Reason reason) {
        return switch (reason) {
            case AUTH -> LoginFailedReason.WRONG_CREDENTIALS;
            case NETWORK -> LoginFailedReason.NO_INTERNET_CONNECTION;
            case NOT_FOUND, RATE_LIMIT, OTHER -> LoginFailedReason.UNKNOWN;
        };
    }

    private void reportLoginFailed(LoginFailedReason reason, String message) {
        runOnDispatcher(() -> adminListeners.forEach(listener -> listener.onLoginFailed(reason, message)));
    }

    private void runIo(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException e) {
            // Провайдер закрыт.
        }
    }

    /** @return {@code false}, если диспетчер уже остановлен и задача не будет выполнена */
    private boolean runOnDispatcher(Runnable task) {
        try {
            dispatcher.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private static void closeQuietly(MarketDataSource candidate) {
        if (candidate != null) {
            candidate.close();
        }
    }

    /** Слушатели Bookmap; после {@link #close()} молчат, как требует контракт провайдера. */
    private final class ListenersOutput implements BookmapOutput {

        @Override
        public void depth(String alias, boolean isBid, int price, int size) {
            if (!closed) {
                dataListeners.forEach(listener -> listener.onDepth(alias, isBid, price, size));
            }
        }

        @Override
        public void trade(String alias, double price, int size, boolean isBidAggressor) {
            if (!closed) {
                TradeInfo tradeInfo = new TradeInfo(false, isBidAggressor);
                dataListeners.forEach(listener -> listener.onTrade(alias, price, size, tradeInfo));
            }
        }

        @Override
        public void connectionLost(String message) {
            if (!closed) {
                adminListeners.forEach(listener -> listener.onConnectionLost(DisconnectionReason.NO_INTERNET, message));
            }
        }

        @Override
        public void connectionRestored() {
            if (!closed) {
                adminListeners.forEach(Layer1ApiAdminListener::onConnectionRestored);
            }
        }
    }
}
