#!/usr/bin/env bash
# Release APK обычной сборки CI — рядом с debug, как в legacy.
#
# Подписывается ключом выпуска, если он задан (NEKOFLASH_RELEASE_KEYSTORE и
# пароли, как у scripts/ci/release_apk.sh), иначе отладочным ключом сборки:
# неподписанный APK не ставится, а тот же отладочный ключ позволяет поставить
# release поверх debug. Каким ключом подписано — в build-info.txt.
# Выпуск с проверкой воспроизводимости — по-прежнему release.yml (D074).
#
# Итог — в build/ci-release/: APK, apk-signature.txt, build-info.txt, SHA256SUMS.
set -euo pipefail

cd "$(dirname "$0")/../.."
out="build/ci-release"
unsigned="app/build/outputs/apk/release/app-release-unsigned.apk"
version="$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts)"
code="$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' app/build.gradle.kts)"
apk="$out/NekoFlash-Pro-$version-release.apk"

test -f "$unsigned" || { echo "sign: нет $unsigned — сначала assembleRelease" >&2; exit 1; }
apksigner="$(ls -d "${ANDROID_HOME:?ANDROID_HOME не задан}"/build-tools/*/apksigner | sort -V | tail -1)"

if [ -n "${NEKOFLASH_RELEASE_KEYSTORE:-}" ]; then
    key="release"
    store="$NEKOFLASH_RELEASE_KEYSTORE"
    store_pass="${NEKOFLASH_RELEASE_KEYSTORE_PASSWORD:?пароль хранилища выпуска не задан}"
    alias="${NEKOFLASH_RELEASE_KEY_ALIAS:?имя ключа выпуска не задано}"
    key_pass="${NEKOFLASH_RELEASE_KEY_PASSWORD:-$store_pass}"
else
    key="debug"
    store="${NEKOFLASH_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
    store_pass="${NEKOFLASH_DEBUG_KEYSTORE_PASSWORD:-android}"
    alias="${NEKOFLASH_DEBUG_KEY_ALIAS:-androiddebugkey}"
    key_pass="${NEKOFLASH_DEBUG_KEY_PASSWORD:-android}"
fi
test -f "$store" || { echo "sign: нет хранилища ключа ($key): $store" >&2; exit 1; }

rm -rf "$out"
mkdir -p "$out"
export SIGN_STORE_PASS="$store_pass" SIGN_KEY_PASS="$key_pass"
"$apksigner" sign --ks "$store" --ks-pass env:SIGN_STORE_PASS --ks-key-alias "$alias" \
    --key-pass env:SIGN_KEY_PASS --v4-signing-enabled false --out "$apk" "$unsigned"
"$apksigner" verify --verbose --print-certs "$apk" > "$out/apk-signature.txt"

cat > "$out/build-info.txt" <<INFO
NekoFlash Pro CI release
versionName=$version
versionCode=$code
gitCommit=${GITHUB_SHA:-$(git rev-parse HEAD)}
runNumber=${GITHUB_RUN_NUMBER:-local}
signedWith=$key
INFO
(cd "$out" && sha256sum ./*.apk > SHA256SUMS && cat build-info.txt SHA256SUMS)
