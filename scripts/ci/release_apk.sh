#!/usr/bin/env bash
# Выпуск APK: воспроизводимость, затем подпись (D074).
#
# 1. `assembleRelease` в рабочем дереве и второй раз — в отдельном рабочем
#    дереве того же коммита без кеша сборки. Неподписанные APK обязаны совпасть
#    побайтно; иначе выпуск останавливается и печатает различающиеся записи.
# 2. Если задан `NEKOFLASH_RELEASE_KEYSTORE`, неподписанный APK подписывается
#    `apksigner` и проверяется. Ключ в Gradle не попадает: так любой может
#    собрать тот же неподписанный APK и сравнить его с выпущенным.
#
# Переменные подписи: NEKOFLASH_RELEASE_KEYSTORE (путь к хранилищу),
# NEKOFLASH_RELEASE_KEYSTORE_PASSWORD, NEKOFLASH_RELEASE_KEY_ALIAS,
# NEKOFLASH_RELEASE_KEY_PASSWORD (если не задан — пароль хранилища).
#
# Итог — в build/release/: неподписанный APK, подписанный (если был ключ),
# SHA256SUMS и COMMIT.
set -euo pipefail

cd "$(dirname "$0")/../.."
root="$(pwd)"
out="$root/build/release"

if ! git diff --quiet HEAD --; then
    echo "release: рабочее дерево отличается от HEAD — сравнивать не с чем" >&2
    exit 1
fi

commit="$(git rev-parse HEAD)"
version="$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts)"
name="NekoFlash-Pro-$version"
apk="app/build/outputs/apk/release/app-release-unsigned.apk"

./gradlew assembleRelease --no-daemon

second="$(mktemp -d)/second"
cleanup() {
    git -C "$root" worktree remove --force "$second" 2>/dev/null || true
}
trap cleanup EXIT
git worktree add --detach "$second" "$commit"
[ -f local.properties ] && cp local.properties "$second/"
(cd "$second" && ./gradlew assembleRelease --no-daemon --no-build-cache)

if ! cmp -s "$apk" "$second/$apk"; then
    echo "release: две сборки $commit различаются" >&2
    diff <(unzip -lv "$apk" | awk '{print $7, $8}') <(unzip -lv "$second/$apk" | awk '{print $7, $8}') >&2 || true
    exit 1
fi
echo "release: две сборки $commit совпали побайтно"

rm -rf "$out"
mkdir -p "$out"
cp "$apk" "$out/$name-unsigned.apk"
echo "$commit" > "$out/COMMIT"

if [ -n "${NEKOFLASH_RELEASE_KEYSTORE:-}" ]; then
    : "${NEKOFLASH_RELEASE_KEYSTORE_PASSWORD:?пароль хранилища не задан}"
    : "${NEKOFLASH_RELEASE_KEY_ALIAS:?имя ключа не задано}"
    export NEKOFLASH_RELEASE_KEY_PASSWORD="${NEKOFLASH_RELEASE_KEY_PASSWORD:-$NEKOFLASH_RELEASE_KEYSTORE_PASSWORD}"
    apksigner="$(ls -d "${ANDROID_HOME:?ANDROID_HOME не задан}"/build-tools/*/apksigner | sort -V | tail -1)"
    "$apksigner" sign \
        --ks "$NEKOFLASH_RELEASE_KEYSTORE" \
        --ks-pass env:NEKOFLASH_RELEASE_KEYSTORE_PASSWORD \
        --ks-key-alias "$NEKOFLASH_RELEASE_KEY_ALIAS" \
        --key-pass env:NEKOFLASH_RELEASE_KEY_PASSWORD \
        --out "$out/$name.apk" \
        "$out/$name-unsigned.apk"
    "$apksigner" verify --verbose --print-certs "$out/$name.apk"
else
    echo "release: NEKOFLASH_RELEASE_KEYSTORE не задан — выпуск без подписи"
fi

(cd "$out" && sha256sum ./*.apk > SHA256SUMS && cat SHA256SUMS)
