# Bookmap MOEX Connector — инструкции агенту

## Работа

Русский для общения, комментариев, документации и коммитов. Перед изменениями
проверить status, ветку и HEAD и сохранить пользовательские правки. Устройство
модуля — [docs/architecture.md](docs/architecture.md), шлюз Finam —
[docs/finam.md](docs/finam.md).

Java 21 (`release 21`: под неё собран API Bookmap 7.9), Gradle через wrapper,
gRPC + protobuf, один jar через Shadow. Точные версии — в `build.gradle.kts`.
Слои: `provider → source → feed → transport → shared`, импорт только вниз,
`velox.*` (Bookmap) — только в `provider`. Новый шлюз — пакет в `source/`
с реализацией `MarketDataSource`, без второго пути до Bookmap.

Комментарии объясняют «почему» и несут источник факта о шлюзе: контракт
`.proto`, замер с датой, проверка в этом репозитории. Непроверенное живьём
так и помечается — в коде и в разделе «Что не проверено» [docs/finam.md](docs/finam.md).

## Инварианты

- Секрет API и JWT не попадают в журнал, исключения, `toString`, тексты для
  пользователя. Не собирать строку с секретом даже для маскировки.
- Журнал Netty модуля — `java.util.logging` (`GrpcChannels`). Не возвращать
  его на SLF4J: logback Bookmap на DEBUG пишет кадры HTTP/2 с секретом.
- У `MoexLiveProvider` обязательны `@Layer0LiveModule` и
  `@Layer1ApiVersion(VERSION3)`: без второй Bookmap 7.9 не грузит модуль и
  виснет на старте (проверяют `ProviderEntryPointTest` и скрипт установки).
- Слушателей Bookmap вызывает только поток-диспетчер провайдера; после
  `close()` провайдер их не вызывает.
- Bookmap получает только изменения уровней; снимки шлюза проходят через
  `OrderBook`. Цены — через `BigDecimal` и `InstrumentScale`, не `double`.
- `com.google.*` в модуле перенесён в свой пакет (Guava 25 у Bookmap).
  Сервис-файлы gRPC должны сливаться полностью — проверять после смены Shadow
  или gRPC: `unzip -p build/libs/<jar> META-INF/services/io.grpc.NameResolverProvider`
  содержит `DnsNameResolverProvider`.
- `.proto` Finam в `src/main/proto` не правятся руками; обновление — по
  [docs/finam.md](docs/finam.md#контракт).

## Проверки

- `./gradlew build` — компиляция с `-Xlint:all -Werror`, тесты, модуль. Проверять
  exit code. Тесты не ходят в сеть и не требуют Bookmap.
- Изменение загрузки классов, сборки jar или транспорта — дополнительно
  загрузить модуль под JRE Bookmap его же загрузчиком (`velox.bookmap.pL.a`
  → `velox.bookmap.pI.a` → `Class.forName`, как делает `velox.bookmap.fY`)
  и проверить вход (неверный секрет → `WRONG_CREDENTIALS`) и отсутствие DEBUG
  Netty в выводе. Модуль, который Bookmap не загрузил, вешает его на старте.
- Установка в Bookmap — только `scripts/install-bookmap.sh`, откат —
  `--uninstall`; список модулей внутри `Bookmap.app` руками не править.
- Живая проверка данных — только в торговую сессию, с секретом пользователя
  из окна Bookmap; секрет не сохранять в репозитории и файлах.

Не обновлять зависимости ради обычной правки. Версии gRPC и protobuf связаны:
`protoc` = `protobuf-java`, который приносит `grpc-protobuf`.

Локальные коммиты не разрешают push. Не коммитить секреты, токены, номера
счетов, `local.properties`.
