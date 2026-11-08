# Bookmap MOEX Connector

Модуль Bookmap (Layer 0, live) с данными Московской биржи в реальном времени:
срочный рынок (FORTS) и фондовый рынок через брокерский шлюз
**Finam Trade API**. Bookmap 7.9.

| Что есть | Чего нет |
|---|---|
| Агрегированный стакан по уровням цены: снимок + построчные изменения | Журнала заявок (MBO) и очереди: брокерские шлюзы их не отдают |
| Лента сделок со стороной агрессора | Торговли из Bookmap — следующий этап |
| Переподключение после обрывов и техокна шлюза | Истории при подключении: Bookmap начинает с момента подписки |
| Срочный и фондовый рынок одним модулем | Шлюза T-Invest — следующий этап, за тем же интерфейсом |

Глубина стакана зависит от прав токена Finam: у ключа с `DEPTH_OF_MARKET`
снимок фьючерса BTU6 нёс 54 строки на обе стороны. Подробности шлюза —
[docs/finam.md](./docs/finam.md).

---

## Сборка

### Требования

- **Bookmap 7.9 с доступом к L0 API.** Свои (не подписанные Bookmap)
  L0-коннекторы Bookmap запускает, только если в лицензии есть право
  `allow_quant` — его дают решения [Quant](https://bookmap.com/en/b2b/quant-solutions)
  или Connect после одобрения Bookmap
  ([API](https://bookmap.com/knowledgebase/docs/API),
  [Connect to own data source](https://bookmap.com/knowledgebase/docs/KB-GettingStarted-Connect-To-Own-Data-Source)).
  Без него модуль загружается, но в списке платформ не появляется, а в
  журнале Bookmap — `Live module not allowed by license: ...MoexLiveProvider`.
  Второй путь — подпись Bookmap: модули из каталога Bookmap проходят по
  списку `signedL0Api/permissions.bin` без этого права.
- Из установки Bookmap берётся API (`bm-l1api.jar`).
- **JDK 21** для сборки. Если Gradle его не найдёт, скачает сам (foojay).
  На macOS: `brew install openjdk@21`.

### Команды

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew build
```

| Команда | Что делает |
|---|---|
| `./gradlew build` | генерация стабов из `.proto`, компиляция, тесты, модуль `build/libs/bookmap-moex-connector-<версия>.jar` |
| `./gradlew test` | только тесты; сети и Bookmap не нужно |
| `./gradlew shadowJar` | только модуль |

Bookmap установлен не в `/Applications/Bookmap.app`? Укажите каталог его
библиотек: `./gradlew build -PbookmapLib=/путь/к/Bookmap/lib` или строкой
`bookmapLib=...` в `~/.gradle/gradle.properties`.

В модуль идёт только `bookmap-moex-connector-<версия>.jar`; файл с
`-plain` — без зависимостей, Bookmap его не загрузит.

---

## Установка в Bookmap

Свои L0-модули Bookmap 7.9 загружает при старте по списку
`lib/UserModules/L0/external-live-modules.txt` внутри установки — строка
`<класс> <путь к jar>`. Через меню локальный L0-модуль не добавить:
«Settings → Configure add-ons» принимает только L1-аддоны, «Settings → Manage
plugins» — только модули из каталога Bookmap.

1. Закрыть Bookmap.
2. Установить модуль:

   ```bash
   ./gradlew build && ./scripts/install-bookmap.sh
   ```

   Скрипт копирует jar в `~/Library/Application Support/Bookmap/API/Custom/`
   и добавляет в `/Applications/Bookmap.app/Contents/app/lib/UserModules/L0/external-live-modules.txt`
   строку

   ```text
   io.github.tonymontanov.bookmap.moex.provider.MoexLiveProvider /Users/<вы>/Library/Application Support/Bookmap/API/Custom/bookmap-moex-connector.jar
   ```

   Исходный список сохраняется рядом как `.orig`. Если macOS запретит запись
   в `Bookmap.app`, разрешите терминалу (или VS Code) «Управление
   приложениями» в «Системные настройки → Конфиденциальность и безопасность».
3. Запустить Bookmap: **Connections → Configure → ADD CONNECTION**, в списке
   **Platform** выбрать **MOEX (Finam)**.
4. Заполнить окно входа:

| Поле | Что ввести |
|---|---|
| Шлюз | `Finam` |
| Секрет API | секрет из раздела «Токены» портала Finam Trade API |
| Счёт (необязательно) | торговый счёт; пусто — первый счёт токена. Нужен шлюзу для справочника инструментов |

После пересборки достаточно закрыть Bookmap и снова запустить скрипт. После
обновления Bookmap список модулей возвращается к исходному — скрипт нужно
запустить ещё раз. Файл списка входит в подпись `Bookmap.app`; правка нарушает
её печать, но запуск уже одобренного приложения это не блокирует.

**Если Bookmap завис на старте.** Модуль, который Bookmap не смог загрузить,
не даёт сообщения об ошибке, а вешает его: показ ошибки для модулей из этого
списка падает с `NullPointerException ... PluginInfo.getName()`. Завершите
Bookmap принудительно (⌥⌘⎋) и снимите модуль:

```bash
./scripts/install-bookmap.sh --uninstall
```

Скрипт убирает только строку этого модуля; полный откат — вернуть
`external-live-modules.txt.orig` на место. Перед установкой скрипт сам
проверяет, что у точки входа есть аннотации, которые читает Bookmap
(`@Layer0LiveModule`, `@Layer1ApiVersion`), и негодный jar не регистрирует.

Журнал коннектора — в журнале Bookmap (Help → Show log file), строки с
префиксом `[MOEX]`. При входе там же — права токена на данные. Секрет и JWT в
журнал не попадают.

---

## Подписка

В окне подписки Bookmap — символ и, при желании, биржа:

| Символ | Биржа | Что будет |
|---|---|---|
| `SiZ6` | пусто | проверяется `SiZ6@RTSX`, затем `SiZ6@MISX` |
| `SBER` | пусто | `SBER@RTSX` не найден → `SBER@MISX` |
| `SBER` | `TQBR`, `MISX`, `SHARES` | `SBER@MISX` |
| `SiZ6` | `FORTS`, `RTSX`, `SPBFUT` | `SiZ6@RTSX` |
| `SBER@MISX` | любая | как есть |

Регистр тикера сохраняется: у фьючерсов он значим (`SiZ6`). Инструмент в
Bookmap называется `тикер@MIC`.

---

## Структура

```text
src/main/java/io/github/tonymontanov/bookmap/moex/
├── provider/     модуль Bookmap: провайдер, окно входа, стакан инструмента
├── source/       шлюзы: фабрика и настройки; finam/ — Finam Trade API
├── feed/         контракт источника, стакан, перевод цен
├── transport/    gRPC: каналы, переподключаемый стрим
└── shared/       журнал, пауза повтора, потоки
src/main/proto/   контракт Finam Trade API (.proto без изменений)
scripts/          install-bookmap.sh — установка модуля в Bookmap на macOS
```

Импорты — только сверху вниз, Bookmap — только в `provider`; проверяется
тестом. Подробности — [docs/architecture.md](./docs/architecture.md).

## Документы

- [docs/architecture.md](./docs/architecture.md) — слои, путь данных, потоки, решения сборки
- [docs/finam.md](./docs/finam.md) — шлюз Finam: авторизация, справочник, стримы, что не проверено
- [AGENTS.md](./AGENTS.md) — правила работы в репозитории

## Лицензия

Apache 2.0 — [LICENSE](./LICENSE).
