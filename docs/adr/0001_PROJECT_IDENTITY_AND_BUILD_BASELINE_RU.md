# ADR-0001 — Project identity и build baseline

> Перенесено из архива `reference/archives/NekoFlash-main-2026-09-14.zip` без изменения текста решения. Ссылки вида «`07` §6.N», «`09`», «`16` §3», «`docs/…`» ведут в документы архива `NekoFlash-main/docs/`. Действующие правила проекта — [`docs/`](../README.md).

Статус: **ACCEPTED**, частично заменён [ADR-0007](0007_MERGE_WITH_REFACTORED_ARCHIVE_RU.md): идентичность приложения и сборочная база
Дата: **2026-08-28**

## Контекст

Новый NekoFlash строится как clean repository и не должен автоматически
наследовать application identity или build baseline Legacy/A2. Сборки и тесты
должны быть воспроизводимыми в authoritative CI, а Gradle-модули должны
появляться только при наличии реальной ownership boundary.

## Решение

### Application identity

- repository: `https://github.com/Ncorror/NekoFlash`;
- `applicationId`: `io.github.ncorror.nekoflash`;
- Android `namespace`: `io.github.ncorror.nekoflash`.

Legacy package `ru.forum.adbfastboottool` и A2 package
`io.github.ncorror.nekoflash` не определяют архитектуру нового проекта; совпадение
с A2 package name не означает наследование A2 codebase или истории.

### Android baseline

- `minSdk = 26`;
- `targetSdk = 36`;
- `compileSdk = 37`.

`compileSdk` следует текущему Compose/Android toolchain. `targetSdk` повышается
отдельным осознанным changeset после проверки target-specific behavior changes.

### Build toolchain

- Android Gradle Plugin `9.3.2` (patch update from the initial `9.3.0` baseline);
- Gradle `9.5.0`;
- checked-in official Gradle Wrapper `9.5.0`;
- Kotlin `2.4.10`;
- JDK `17` в CI;
- Compose BOM `2026.08.00`;
- Material 3 Adaptive `1.3.0`.

AGP 9 built-in Kotlin является основным Android Kotlin integration path; старый
`kotlin-android` plugin не используется. Patch-level AGP update до `9.3.2` принят
без смены minor line; Gradle `9.5.0` сохраняется как официальный minimum/default
для AGP 9.3 и обновляется только отдельным проверяемым changeset.

### Initial modules

Создаются только модули с реальным содержимым и ownership contract:

- `:app`;
- `:core:model`;
- `:core:diagnostics`;
- `:core:operation`.

USB/ADB/Fastboot/Recovery/vendor modules не создаются пустыми и появляются в
соответствующих vertical slices roadmap.

### CI authority

GitHub Actions выполняет `test`, `lint`, `assembleDebug` и публикует APK/reports.
Termux остаётся Git/worktree/edit/commit/push environment и не является
обязательной Android build machine.

Первый CI bootstrap подтвердил baseline 2026-08-28: core unit tests PASS, Android Lint — 0 errors, debug APK собран. Официально сгенерированный Gradle Wrapper `9.5.0` принят в repository после проверки SHA-256 wrapper JAR против опубликованного Gradle checksum. Distribution checksum также фиксируется в wrapper properties.

## Последствия

- package identity зафиксирован до появления production Android components;
- новый проект не зависит от локальной Android toolchain в Termux;
- core contracts тестируются как pure JVM modules;
- `TargetId` и `SessionGeneration` разделены с первого production changeset;
- `UNKNOWN` и mutation boundary существуют до появления mutating operations;
- architecture не обрастает пустыми модулями ради схемы на бумаге.
