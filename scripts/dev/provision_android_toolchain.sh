#!/usr/bin/env bash
# Ставит то, чего нет в базовом образе облачной среды, но требует сборка:
# JDK 17 и Android SDK. Идемпотентен — повторный запуск ничего не ломает.
#
# Место ему в Setup script окружения на claude.ai/code: тот выполняется один
# раз, после чего файловая система снимается снапшотом, и следующие сессии
# получают инструментарий уже на диске. Здесь скрипт живёт затем, чтобы его
# можно было прочитать, поправить и прогнать как обычный код, а не хранить
# копию в поле ввода.
#
# Требует открытого `dl.google.com` в политике сети окружения: с него идут и
# Google Maven, и репозиторий SDK. Без него сборка Android-модулей невозможна
# в принципе — см. `docs/16_AGENT_OPERATING_PROMPT_RU.md` §5.1.
set -euo pipefail

ANDROID_SDK_DIR="${ANDROID_SDK_DIR:-/opt/android-sdk}"

# Версии держим ровно те, что требует дерево. Расходиться им нельзя: toolchain
# в Gradle закреплён на 17, `compileSdk` — на 37, NDK и CMake — в
# `usb/native/build.gradle.kts` (D077).
JDK_PACKAGE='openjdk-17-jdk-headless'
CMDLINE_TOOLS='commandlinetools-linux-16111833_latest.zip'
SDK_PLATFORM='platforms;android-37.0'
SDK_BUILD_TOOLS='build-tools;37.0.0'
SDK_NDK='ndk;28.2.13676358'
SDK_CMAKE='cmake;3.22.1'

say() { printf 'provision: %s\n' "$1"; }

# --- JDK 17 -----------------------------------------------------------------
# Образ несёт JDK 21, а Gradle просит именно 17 и сам его не скачивает:
# toolchain download repositories не настроены, и сборка падает на
# «Cannot find a Java installation matching languageVersion=17».
if [[ -d /usr/lib/jvm/java-17-openjdk-amd64 ]]; then
  say 'JDK 17 уже есть'
else
  say "ставлю $JDK_PACKAGE"
  # Индекс в образе бывает устаревшим, и тогда apt отдаёт 404 на настоящий
  # пакет. Обновление здесь не перестраховка: без него установка падает.
  apt-get update -q
  apt-get install -y -q "$JDK_PACKAGE"
fi

# --- Android SDK ------------------------------------------------------------
if [[ -x "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]]; then
  say 'cmdline-tools уже на месте'
else
  say 'ставлю cmdline-tools'
  mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL --retry 3 -o "$tmp/cmdline.zip" \
    "https://dl.google.com/android/repository/$CMDLINE_TOOLS"
  unzip -q -o "$tmp/cmdline.zip" -d "$ANDROID_SDK_DIR/cmdline-tools"
  mv "$ANDROID_SDK_DIR/cmdline-tools/cmdline-tools" \
     "$ANDROID_SDK_DIR/cmdline-tools/latest"
fi

export ANDROID_HOME="$ANDROID_SDK_DIR"
export ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"
export PATH="$ANDROID_SDK_DIR/cmdline-tools/latest/bin:$PATH"

# Проверка до вызова, а не «пусть sdkmanager сам разберётся»: он и на готовом
# наборе тратит секунды, а хук при старте сессии должен стоить около нуля.
platform_dir="$ANDROID_SDK_DIR/platforms/${SDK_PLATFORM#*;}"
build_tools_dir="$ANDROID_SDK_DIR/build-tools/${SDK_BUILD_TOOLS#*;}"
ndk_dir="$ANDROID_SDK_DIR/ndk/${SDK_NDK#*;}"
cmake_dir="$ANDROID_SDK_DIR/cmake/${SDK_CMAKE#*;}"
if [[ -d "$platform_dir" && -d "$build_tools_dir" && -d "$ndk_dir" && -d "$cmake_dir" ]]; then
  say 'платформа, build-tools, NDK и CMake уже на месте'
else
  say 'принимаю лицензии SDK'
  yes 2>/dev/null | sdkmanager --licenses >/dev/null 2>&1 || true

  say 'ставлю платформу, build-tools, NDK и CMake'
  sdkmanager --install 'platform-tools' "$SDK_PLATFORM" "$SDK_BUILD_TOOLS" "$SDK_NDK" "$SDK_CMAKE" >/dev/null
fi

# --- Зеркало Maven Central (только локально) ----------------------------------
# Из облачной среды Maven Central отвечает 429 Too Many Requests на параллельные
# запросы Gradle, и сборка падает ещё на разрешении плагинов. Init-скрипт в
# домашнем каталоге Gradle направляет Maven Central на зеркало Google с тем же
# содержимым. CI этот файл не видит и ходит в Maven Central напрямую.
gradle_init_dir="${GRADLE_USER_HOME:-$HOME/.gradle}/init.d"
if [[ -f "$gradle_init_dir/central-mirror.gradle" ]]; then
  say 'зеркало Maven Central уже настроено'
else
  say 'настраиваю зеркало Maven Central'
  mkdir -p "$gradle_init_dir"
  cat > "$gradle_init_dir/central-mirror.gradle" <<'EOF'
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
def redirect = { repos ->
    repos.all { repo ->
        if (repo instanceof MavenArtifactRepository && repo.url.toString().startsWith('https://repo.maven.apache.org')) {
            repo.url = mirror
        }
    }
}
settingsEvaluated { settings ->
    redirect(settings.pluginManagement.repositories)
    redirect(settings.dependencyResolutionManagement.repositories)
}
EOF
fi

# Переменные окружения нужны каждой оболочке, а не только этой: снапшот
# сохраняет файлы, но не экспорт.
printf 'export ANDROID_HOME=%s\nexport ANDROID_SDK_ROOT=%s\n' \
  "$ANDROID_SDK_DIR" "$ANDROID_SDK_DIR" > /etc/profile.d/android-sdk.sh
chmod 0644 /etc/profile.d/android-sdk.sh

say "готово: SDK в $ANDROID_SDK_DIR, JDK 17 установлен"
