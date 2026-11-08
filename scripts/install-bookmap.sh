#!/usr/bin/env bash
# Устанавливает модуль в Bookmap 7.9 на macOS или снимает его (--uninstall).
#
# Свои L0-модули Bookmap 7.9 загружает при старте по списку
# lib/UserModules/L0/external-live-modules.txt внутри установки: строка
# «<класс> <путь к jar>», путь абсолютный или относительно этого каталога.
# Меню «Configure add-ons» принимает только L1-аддоны, «Manage plugins» —
# только модули из каталога Bookmap.
#
# Модуль, который Bookmap не смог загрузить, вешает Bookmap на старте: его
# показ ошибки для модулей из этого списка падает с NullPointerException
# (08.11.2026, модуль без @Layer1ApiVersion). Поэтому jar проверяется до
# правки списка, а откат — `./scripts/install-bookmap.sh --uninstall`.
#
# Скрипт копирует собранный jar в постоянное место вне Bookmap.app (чтобы
# пересборка и обновление Bookmap не меняли путь) и регистрирует его одной
# строкой; прежняя строка этого модуля заменяется. Исходный список Bookmap
# сохраняется рядом как .orig (один раз), каждое изменение — как .bak-<время>.
#
# Переменные:
#   BOOKMAP_LIB         каталог lib Bookmap (по умолчанию /Applications/Bookmap.app/Contents/app/lib)
#   BOOKMAP_MODULE_DIR  куда положить jar (по умолчанию ~/Library/Application Support/Bookmap/API/Custom)
#   BOOKMAP_ALLOW_RUNNING=1  не проверять, запущен ли Bookmap (для проверки скрипта на копии списка)
set -euo pipefail

cd "$(dirname "$0")/.."

BOOKMAP_LIB="${BOOKMAP_LIB:-/Applications/Bookmap.app/Contents/app/lib}"
BOOKMAP_MODULE_DIR="${BOOKMAP_MODULE_DIR:-$HOME/Library/Application Support/Bookmap/API/Custom}"
MODULES_FILE="$BOOKMAP_LIB/UserModules/L0/external-live-modules.txt"
PROVIDER_CLASS="io.github.tonymontanov.bookmap.moex.provider.MoexLiveProvider"
PROVIDER_CLASS_FILE="${PROVIDER_CLASS//.//}.class"
INSTALLED_JAR="$BOOKMAP_MODULE_DIR/bookmap-moex-connector.jar"

mode="install"
case "${1:-}" in
    "") ;;
    --uninstall) mode="uninstall" ;;
    *) echo "Использование: $0 [--uninstall]" >&2; exit 2 ;;
esac

if [[ ! -f "$MODULES_FILE" ]]; then
    echo "Не найден список модулей Bookmap: $MODULES_FILE" >&2
    echo "Укажите каталог lib установки Bookmap в BOOKMAP_LIB." >&2
    exit 1
fi

# Список читается только при старте, а jar загруженного модуля Bookmap держит
# открытым: замена под работающим Bookmap ломает догрузку классов.
if [[ "${BOOKMAP_ALLOW_RUNNING:-0}" != "1" ]] && pgrep -f "Bookmap.app/Contents/MacOS/Bookmap" >/dev/null 2>&1; then
    echo "Bookmap запущен. Закройте его (если завис — «Завершить принудительно») и повторите." >&2
    exit 1
fi

# Переписывает список без строк этого модуля; если передан аргумент, он
# дописывается последней строкой. Перед записью сохраняет копии.
rewrite_modules() {
    local entry="${1:-}"
    if [[ ! -f "$MODULES_FILE.orig" ]]; then
        cp "$MODULES_FILE" "$MODULES_FILE.orig"
    fi
    local backup="$MODULES_FILE.bak-$(date +%Y%m%d-%H%M%S)"
    cp "$MODULES_FILE" "$backup"
    local updated
    updated="$(mktemp)"
    grep -vF "$PROVIDER_CLASS " "$MODULES_FILE" > "$updated" || true
    if [[ -n "$entry" ]]; then
        # Последняя строка исходного файла может быть без перевода строки.
        if [[ -s "$updated" && -n "$(tail -c 1 "$updated")" ]]; then
            echo >> "$updated"
        fi
        echo "$entry" >> "$updated"
    fi
    if ! cat "$updated" > "$MODULES_FILE"; then
        rm -f "$updated"
        echo "Не удалось записать $MODULES_FILE." >&2
        echo "macOS защищает приложения от изменения: разрешите терминалу (или VS Code)" >&2
        echo "«Управление приложениями» в Системные настройки → Конфиденциальность и безопасность." >&2
        exit 1
    fi
    rm -f "$updated"
    echo "Список модулей обновлён: $MODULES_FILE (резервная копия: $backup)"
}

if [[ "$mode" == "uninstall" ]]; then
    if grep -qF "$PROVIDER_CLASS " "$MODULES_FILE"; then
        rewrite_modules
        echo "Модуль снят. Jar оставлен: $INSTALLED_JAR"
    else
        echo "Модуля нет в $MODULES_FILE — снимать нечего."
    fi
    exit 0
fi

shopt -s nullglob
jars=(build/libs/bookmap-moex-connector-*.jar)
module_jar=""
for jar in "${jars[@]}"; do
    [[ "$jar" == *-plain.jar ]] && continue
    module_jar="$jar"
done
if [[ -z "$module_jar" ]]; then
    echo "Модуль не собран: выполните ./gradlew build" >&2
    exit 1
fi

# Проверка до правки списка: Bookmap 7.9 читает аннотации точки входа из
# байткода и на их отсутствие отвечает зависанием, а не сообщением.
provider_bytes="$(unzip -p "$module_jar" "$PROVIDER_CLASS_FILE" 2>/dev/null | LC_ALL=C tr -c '[:print:]' '\n' || true)"
for annotation in velox/api/layer0/annotations/Layer0LiveModule velox/api/layer1/annotations/Layer1ApiVersion; do
    if ! grep -qF "L$annotation;" <<< "$provider_bytes"; then
        echo "В $module_jar у $PROVIDER_CLASS нет @${annotation##*/} — Bookmap такой модуль не загрузит и зависнет." >&2
        echo "Список модулей не изменён." >&2
        exit 1
    fi
done

mkdir -p "$BOOKMAP_MODULE_DIR"
cp "$module_jar" "$INSTALLED_JAR"
echo "Модуль: $module_jar → $INSTALLED_JAR"

entry="$PROVIDER_CLASS $INSTALLED_JAR"
if grep -qxF "$entry" "$MODULES_FILE"; then
    echo "Строка модуля уже есть в $MODULES_FILE"
else
    rewrite_modules "$entry"
fi

echo
echo "Дальше: запустите Bookmap → Connections → Configure → ADD CONNECTION, платформа «MOEX (Finam)»."
echo "Если Bookmap зависнет на старте: завершите его принудительно и выполните $0 --uninstall."
echo "После обновления Bookmap список модулей возвращается к исходному — запустите скрипт снова."
