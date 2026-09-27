#!/usr/bin/env bash
# Статический анализ Kotlin: стиль, сложность и границы модулей.
#
# Используется detekt CLI как самостоятельный jar, а не Gradle-плагин.
# Причина: гейт не должен зависеть от совместимости плагина с текущими
# версиями AGP/Gradle/Kotlin и не должен ломать сборку продукта при
# обновлении toolchain. Версия и SHA-256 дистрибутива закреплены здесь так
# же, как закреплён Gradle Wrapper.
#
# Java 17+ обязательна. В Termux: pkg install openjdk-17
# Скачанный jar кэшируется в build/tools и не попадает в git.
set -euo pipefail

cd "$(dirname "$0")/../.."

DETEKT_VERSION="1.23.8"
DETEKT_SHA256="2ce2ff952e150baf28a29cda70a363b0340b3e81a55f43e51ec5edffc3d066c1"
DETEKT_URL="https://github.com/detekt/detekt/releases/download/v${DETEKT_VERSION}/detekt-cli-${DETEKT_VERSION}-all.jar"
DETEKT_JAR="${DETEKT_JAR:-build/tools/detekt-cli-${DETEKT_VERSION}-all.jar}"

if ! command -v java >/dev/null 2>&1; then
    echo "kotlin style: SKIPPED (java не найдена)"
    echo "  установить: pkg install openjdk-17"
    echo "  в CI этот шаг обязателен и пропущен не будет"
    exit 0
fi

if [ ! -f "$DETEKT_JAR" ]; then
    mkdir -p "$(dirname "$DETEKT_JAR")"
    echo "загружаю detekt ${DETEKT_VERSION}..."
    curl -fsSL -o "$DETEKT_JAR" "$DETEKT_URL"
fi

actual="$(sha256sum "$DETEKT_JAR" | cut -d' ' -f1)"
if [ "$actual" != "$DETEKT_SHA256" ]; then
    rm -f "$DETEKT_JAR"
    echo "kotlin style: FAIL — checksum detekt не совпал" >&2
    echo "  ожидалось $DETEKT_SHA256" >&2
    echo "  получено  $actual" >&2
    exit 1
fi

failures=0

echo "== style and complexity =="
if java -jar "$DETEKT_JAR" \
    --input app/src/main,core/model/src/main,core/diagnostics/src/main,core/operation/src/main,core/artifact/src/main,usb/api/src/main,usb/android/src/main,protocol/adb/src/main \
    --config config/detekt/detekt.yml \
    --build-upon-default-config; then
    echo "style: PASS"
else
    failures=$((failures + 1))
fi

# :engine — разбор команд этого репозитория: тот же конфиг, без baseline.
echo
echo "== engine =="
if java -jar "$DETEKT_JAR" \
    --input engine/src/main \
    --config config/detekt/detekt.yml \
    --build-upon-default-config; then
    echo "engine: PASS"
else
    failures=$((failures + 1))
fi

echo
echo "== module boundaries =="
boundary_log="$(mktemp)"
trap 'rm -f "$boundary_log"' EXIT
check_boundary() {
    local label="$1"
    local input="$2"
    local config="$3"
    [ -d "$input" ] || return 0
    if java -jar "$DETEKT_JAR" --input "$input" --config "$config" > "$boundary_log" 2>&1; then
        echo "  $label: PASS"
    else
        echo "  $label: FAIL - imports something it must not depend on" >&2
        grep ForbiddenImport "$boundary_log" >&2 || cat "$boundary_log" >&2
        failures=$((failures + 1))
    fi
}

check_boundary "core" "core" "config/detekt/core-boundaries.yml"
check_boundary "usb:api" "usb/api" "config/detekt/usb-api-boundaries.yml"
check_boundary "usb:android" "usb/android" "config/detekt/usb-android-boundaries.yml"
check_boundary "protocol:adb" "protocol/adb" "config/detekt/protocol-adb-boundaries.yml"
check_boundary "protocol:fastboot" "protocol/fastboot" "config/detekt/protocol-fastboot-boundaries.yml"
check_boundary "engine" "engine" "config/detekt/engine-boundaries.yml"
echo
if [ "$failures" -gt 0 ]; then
    echo "kotlin style: FAIL ($failures)" >&2
    exit 1
fi

echo "kotlin style: PASS"
