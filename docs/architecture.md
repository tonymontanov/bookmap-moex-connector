# Архитектура коннектора

Обзор устройства модуля: слои и правило импортов, потоки, путь данных от
шлюза до Bookmap, решения сборки. Особенности шлюза Finam — в
[finam.md](./finam.md).

---

## 1. Слои

Пять слоёв, от самого зависимого к самому независимому. Корневой пакет —
`io.github.tonymontanov.bookmap.moex`.

```text
provider/    модуль Bookmap: MoexLiveProvider, окно входа, стакан инструмента → слушатели Bookmap
source/      брокерские шлюзы: фабрика, настройки входа; source/finam — Finam Trade API
feed/        контракт источника и доменные типы: MarketDataSource, FeedListener, OrderBook, InstrumentScale
transport/   gRPC без предметной области: каналы TLS, переподключаемый стрим
shared/      утилиты без предметной области: журнал, пауза повтора, фабрика потоков
```

### 1.1. Правило импортов

Слой импортирует только нижележащие:

```text
provider → source → feed → transport → shared
```

Импорт вверх запрещён. Классы Bookmap (`velox.*`) импортирует только
`provider`: всё ниже проверяется тестами без Bookmap. Правило проверяет
[LayerDependencyTest](../src/test/java/io/github/tonymontanov/bookmap/moex/architecture/LayerDependencyTest.java)
— он падает с файлом и строкой нарушения.

Внутри `source` реализации шлюзов (`source/finam`, позже `source/tinvest`)
друг друга не импортируют и не импортируют сам `source`: фабрика
`MarketDataSources` знает о них, они о ней — нет. Поэтому `FinamSource`
принимает секрет и счёт строками, а не `SourceSettings`.

### 1.2. Где что менять

| Задача | Где |
|---|---|
| Новый шлюз (T-Invest) | `source/tinvest/` с реализацией `MarketDataSource`, константа в `SourceKind`, ветка в `MarketDataSources` |
| Поведение стакана, разница снимков | `feed/OrderBook` |
| Перевод цен и объёмов | `feed/InstrumentScale` |
| Повторы и паузы стримов | `transport/ReconnectingStream` |
| Окно входа | `provider/MoexCredentialsFields`, разбор — `provider/LoginFields` |
| Что уходит в Bookmap | `provider/InstrumentFeed`, `provider/MoexLiveProvider` |

---

## 2. Путь данных

```text
api.finam.ru (gRPC)
  └─ ReconnectingStream            стрим стакана и стрим сделок на инструмент; переоткрытие с паузой
       └─ FinamBookMapper /        protobuf → вызовы FeedListener; цены в шаги через InstrumentScale
          FinamTradeMapper
            └─ InstrumentFeed      FeedListener провайдера: перекладывает вызов на диспетчер
                 └─ OrderBook      снимок → разница с прошлым состоянием; изменение → если объём другой
                      └─ Bookmap   onDepth / onTrade / onConnectionLost / onConnectionRestored
```

Ключевые решения:

- **Bookmap получает только изменения уровней.** Шлюзы присылают и снимки, и
  изменения; `OrderBook` хранит последнее состояние и отдаёт разницу. Уровень,
  исчезнувший за время обрыва, удаляется из Bookmap ближайшим снимком, а не
  висит до конца сессии.
- **Цены — через `BigDecimal`.** Шлюз присылает десятичные строки; в `double`
  соседние уровни дробного шага сливались бы в один.
- **Связь провайдера — по стримам стакана.** У Bookmap один признак связи на
  провайдер. Потерянной она считается, пока лежит хоть один стрим стакана;
  лента сделок в расчёт не идёт — неликвид молчит часами (`ConnectionHealth`).
- **История сделок при подписке пропускается.** Bookmap ставит событию метку
  в момент вызова, и история легла бы в ленту как только что прошедшие сделки.

---

## 3. Потоки

| Поток | Чей | Что делает |
|---|---|---|
| поток Bookmap | Bookmap | вызывает `login`, `subscribe`, `unsubscribe`, `formatPrice`, `close` |
| `moex-io-N` | провайдер | блокирующие запросы к шлюзу: вход, поиск инструмента |
| `moex-dispatch-N` | провайдер | всё состояние подписок и все вызовы слушателей Bookmap |
| `grpc-default-executor-*`, `grpc-nio-worker-*` | gRPC | колбэки стримов → `FeedListener` |
| `moex-finam-retry-N` | источник | запланированные переоткрытия стримов |

Слушателей Bookmap вызывает только диспетчер. Bookmap ставит событию метку
времени в момент вызова, поэтому один поток — это ещё и порядок событий: стакан
и лента разных стримов доходят до Bookmap в том порядке, в котором пришли.
После `close()` провайдер слушателей не вызывает (контракт `ExternalLiveBaseProvider`).

---

## 4. Сборка модуля

- **API Bookmap** берётся из установленного Bookmap (`compileOnly`, свойство
  `bookmapLib`), а не из Maven: версия API у компиляции и у запуска совпадает
  по построению. `bm-l1api.jar` 7.9 собран под Java 21 — отсюда `release 21`.
- **gRPC** — `grpc-netty-shaded`: самодостаточен, без Kotlin и okio (у Bookmap
  в classpath свой Kotlin). Стабы генерируются из `.proto` Finam в
  `src/main/proto` (откуда и как обновлять — [finam.md](./finam.md#контракт)).
- **Один jar с переносом пакетов.** В classpath Bookmap лежит Guava 25, а gRPC
  нужна 33.x: весь `com.google.*` (Guava, protobuf, common-protos, gson)
  переносится в `io.github.tonymontanov.bookmap.moex.shaded`. `io.grpc` не
  переносится: у Bookmap его нет, а сервис-провайдеры gRPC завязаны на имена.
- **Сервис-файлы gRPC сливаются полностью.** При стратегии дублей EXCLUDE из
  модуля молча пропадали `DnsNameResolverProvider` и `PickFirstLoadBalancerProvider`.
- **Точка входа — `@Layer1ApiVersion(VERSION3)`.** Bookmap 7.9 читает версию
  API из байткода до загрузки и без неё не грузит внешний модуль (и виснет:
  его показ ошибки падает с NPE). VERSION3 — загрузчик child-first: классы
  модуля раньше одноимённых из Bookmap; классы `velox.*` — общие с Bookmap.
- **Журнал Netty — через `java.util.logging`.** Иначе Netty находит logback
  Bookmap и на DEBUG пишет кадры HTTP/2 с секретом и JWT (`GrpcChannels`).
