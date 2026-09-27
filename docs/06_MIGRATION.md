# Перенос кода из архива

Как код рефакторинга из [архива](../reference/README.md) становится кодом этого репозитория. Решение о переносе — [ADR-0007](adr/0007_MERGE_WITH_REFACTORED_ARCHIVE_RU.md); порядок и статус шагов — [05_ROADMAP](05_ROADMAP.md).

Источник: `reference/archives/NekoFlash-main-2026-09-14.zip` — репозиторий `Ncorror/NekoFlash`, коммит `0be9ca786a756c5243be812096fe52b558af6113`. В архиве 9 модулей, ~22,9 тыс. строк кода и 850 тестов; в этом репозитории до переноса — 6 модулей, ~4,6 тыс. строк и 87 тестов.

## 1. Принципы

1. **Цель — модульная структура архива** ([03_ARCHITECTURE](03_ARCHITECTURE.md#5-модули)). Переносим по одному модулю снизу вверх вместе с его тестами.
2. **Дословно.** Пакеты `io.github.ncorror.nekoflash.*` не переименовываются. Код правится только там, где встречается с кодом репозитория; каждое отклонение записывается в [раздел 5](#5-отклонения-от-архива).
3. **Тесты архива переносятся и проходят без изменения ожиданий.** Изменённое ожидание — отклонение, записывается с причиной.
4. **Один production-путь.** Модуль или класс репозитория удаляется тем же этапом, который его заменил. Никаких `Old/New`, «оставим на всякий случай» и переключателей между старым и новым.
5. **Готово — это вызывается.** Пункт становится `в репо`, когда production-путь реально вызывается из приложения, а не когда класс скопирован.
6. **Доказательства архива на железе — довод о коде, а не проверка нашего APK.** После этапа с железом — свой ручной прогон ([07](07_BUILD_AND_TESTING.md#ручная-проверка-на-телефоне)).
7. **Документы архива не копируются.** Всё действующее из них уже сведено в `docs/`; ADR перенесены в [`adr/`](README.md#adr--развёрнутые-решения). За обоснованием конкретного кода и историей прогонов идём в архив: `NekoFlash-main/docs/` (журнал прогонов — `07_TESTING_CI_HARDWARE_EVIDENCE_RU.md`, чеклисты фаз — `09_IMPLEMENTATION_ROADMAP_RU.md`).
8. **Уникальное из репозитория сохраняется:** разбор команд настольных `adb`/`fastboot` (`:engine`), путь записи образа из выбранного файла, обезличивание журнала.

## 2. Карта: архив → репозиторий

| Из архива | Этап | Что заменяет здесь | Заметки |
| --- | --- | --- | --- |
| `gradle/libs.versions.toml`, `gradle/wrapper/`, `config/detekt/`, `scripts/ci/*`, `scripts/dev/provision_android_toolchain.sh`, `.claude/`, `.github/workflows/android-ci.yml`, `app/src/main/res/xml/{backup_rules,data_extraction_rules}.xml`, `.editorconfig`, `.gitattributes`, `.gitignore` | 1 | части `build.yml`, `build.gradle.kts` | `scripts/ci/check_docs_consistency.py` архива **не** переносится: он проверяет документы архива; у нас свой `scripts/ci/check_docs.py` |
| `:core:model` | 2 | — | |
| `:core:diagnostics` | 2 | `:core:diagnostics` (`DiagnosticZipWriter`) | `DiagnosticBundle` сам не обезличивает — правила обезличивания текущего `DiagnosticJournal` переходят в слой, собирающий события |
| `:core:operation` | 2 | — | |
| `:core:artifact` | 2 | — | |
| `:usb:api` | 2 | `:core:transport` | `UsbInterfaceClassifier`, `UsbAutoConnectPlanner`, `UsbSessionGate`, порты транспорта |
| `:usb:android` + USB-часть `app` (`NekoFlashApplication`, приёмники) | 2 | `app/.../platform/usb/UsbHostController`, `UsbInterfaceScanner` | |
| `:protocol:adb` + `app/adb/*` | 2 | `:core:adb`, `AdbKeystoreHostKey`, `AdbProbeController`, `AdbUsbByteTransport` | ключ хоста — `AdbKeyStore` (D042) |
| `:protocol:fastboot` + `app/fastboot/*` | 2 | `:core:fastboot`, `FastbootProbeController`, `FastbootFlashController`, `FastbootUsbPacketTransport` | запись из выбранного файла пересобирается на `FastbootLane`/`FastbootDownload`/`FastbootMutation` и `:core:artifact` |
| — | 3 | — | `:engine` остаётся и начинает компилировать планы в операции движков архива |
| `app/ui/*`, `app/operation/*`, `app/artifact/*`, `MainActivity`, ресурсы, иконка | 2 | `ui/UsbScreen`, `ui/TerminalPanel`, `MainActivity`, `NekoFlashTheme` | Android `namespace` меняется на `io.github.ncorror.nekoflash` (D041) |

Модули ядра, USB и протоколов переносятся по одному, а начинают вызываться, когда приложение архива заменяет старое, — промежуточной склейки со старым экраном нет (D049). Проверка на телефоне — одна, в конце этапа 2.

## 3. Расхождения, которые решаются при переносе

| Тема | Здесь | В архиве | Решение |
| --- | --- | --- | --- |
| Предпроверка записи | `FastbootFlashExecutor` отказывает при неизвестном или недостаточном `max-download-size`/`partition-size` | предупреждения класса C | как в архиве ([02](02_INVARIANTS.md#1-четыре-класса-оснований)). Отказ до отправки — только если запрос нельзя закодировать (образ больше `0xFFFFFFFF` байт одним `download:`) |
| Длина Fastboot-команды | до 4096 байт по AOSP README, есть тест против лимита 64 | не больше 64 байт (legacy, ADR-0006) | **открыто** (этап 4). Сверить AOSP `fastboot/README.md` и `protocol.cpp` с поведением загрузчиков `vayu`/`marble`; до решения переносится вариант архива дословно, тест репозитория удаляется вместе с `:core:fastboot` |
| Блок DATA OUT | 64 КиБ (16 КиБ до Android 9) | 16 КиБ | переносится архив; размер блока меняется только по измерению скорости (этап 9) |
| Тайм-ауты | фиксированные 4 с и 300 с | по бездействию: 7 с, 30 с для OEM/`flashing`, 120 с для приёма | архив |
| Ключ хоста ADB | Android Keystore, неэкспортируемый | файлы `adbkey.pk8`/`adbkey.pub` в приватной папке, формат platform-tools | архив (D042); один раз потребуется заново разрешить отладку на устройстве |
| Модель USB | один захват `UsbSessionGate` | поколения сессий, координатор, политика разрешений | архив |
| Диагностика | текстовый журнал + ZIP | структурированные события + детерминированный архив | архив + правила обезличивания репозитория |
| Долгие операции | контроллер живёт с Activity, сворачивание прерывает передачу | движок операций и foreground-сервис | архив |
| Терминал | `:engine`: команды настольных клиентов, план и Preview | сырая консоль и кнопки | оба: [04](04_CAPABILITIES.md#2-три-способа-сказать-одно-и-то-же) |
| Инструменты | AGP 9.2.1, Kotlin 2.4.20, Compose BOM 2026.05.01, `compileSdk` 36 | AGP 9.3.2, Kotlin 2.4.10, Compose BOM 2026.08.00, `compileSdk` 37 | архив (D048): на нём проверены 850 тестов архива |
| Потоки | голые `Thread` в контроллерах | корутины, scope на поколение сессии | архив (ADR-0003) |

## 4. Как выполняется шаг переноса

1. Распаковать архив во временную папку ([reference/README](../reference/README.md)) и открыть модуль вместе с его тестами.
2. Скопировать `src/main`, `src/test`, `build.gradle.kts`; добавить модуль в `settings.gradle.kts` и в `TEST_MODULES` сводки тестов (`scripts/ci-test-summary.py`); CI запускает все тесты общей задачей `test`.
3. Собрать и прогнать тесты модуля той же командой, что в CI ([07](07_BUILD_AND_TESTING.md#сборка)).
4. Переключить вызывающих в `:app` на новый API и удалить заменённое.
5. Обновить в том же коммите: список текущих модулей ([03](03_ARCHITECTURE.md#текущие-модули)), статусы ([05](05_ROADMAP.md)), команду CI ([07](07_BUILD_AND_TESTING.md#сборка)), отклонения (раздел 5). `scripts/ci/check_docs.py` проверит, что ничего не забыто.
6. Если этап требует железа — ручной прогон и запись в [журнал проверок](07_BUILD_AND_TESTING.md#журнал-проверок-этого-apk).

## 5. Отклонения от архива

Каждое место, где перенесённый код отличается от архива, с причиной.

| Файл | Отличие | Причина |
| --- | --- | --- |
| `gradle/libs.versions.toml` | добавлен `kotlin-test-junit` | тесты `:engine` написаны на `kotlin.test`; в архиве такого модуля нет |
| `.github/workflows/build.yml` | один workflow вместо `android-ci.yml`: версии actions этого репозитория, `setup-android` с платформой 37, сводка тестов, имена артефактов `NekoFlash-Pro-*`; вместо `check_docs_consistency.py` — `check_docs.py` | проверка документов архива сверяет его собственные файлы |
| `scripts/ci/check_repository_hygiene.sh` | убрана сверка иконки с `docs/brand-reference/` | эталон иконки теперь внутри неизменяемого архива; иконки в приложении ещё нет |
| `scripts/ci/check_kotlin_style.sh` | старый код репозитория (`legacy_inputs`) не проверяется, пока его не заменит модуль архива; `:engine` проверяется с baseline и своей границей `engine-boundaries.yml` | гейт включён сразу, а замечания старого кода не переносятся в новый |
| `scripts/dev/provision_android_toolchain.sh` | добавлено локальное зеркало Maven Central | из облачной среды Maven Central отвечает 429 на параллельные запросы Gradle |
| `gradle/wrapper/gradle-wrapper.jar` | взят из архива вместо прежнего jar репозитория | jar архива совпадает с тем, что генерирует сам Gradle 9.5.0; прежний — нет |

## 6. Где смотреть поведение перед реализацией

Правило архива: поведение протокола **открывают в исходнике**, а не восстанавливают по памяти. Legacy и A2 лежат внутри архива: `NekoFlash-main/reference/archives/`.

| Реализуешь | Открыть |
| --- | --- |
| USB: обнаружение, разрешение, поколения | A2 `usb/session/UsbSessionCoordinator.kt` (поведение, не структуру), `UsbPermissionPolicy.kt`, `usb/discovery/UsbInterfaceSelector.kt` и тесты рядом |
| ADB-транспорт и входящая рамка | A2 `adb/transport/AdbUsbTransport.kt`, `docs/ADB_INBOUND_USB_FRAMING.md` |
| ADB: авторизация, сервисы, shell, sync | legacy `AdbProtocol.kt`, A2 `adb/transport/*` |
| Fastboot DATA OUT и нативный USBFS | A2 `fastboot/transport/FastbootUsbTransport.kt`, `NativeUsbfsBackend.kt`; legacy `NativeUsbfsBackend.kt`, `native_usbfs.cpp` |
| Fastboot-команды, `getvar`, OEM, разделы | legacy `FastbootProtocol.kt`, `FastbootGetVarAllParser.kt`, `FastbootPartitionInventory.kt` |
| Sideload и Recovery | A2 `adb/sideload/SideloadStreamSession.kt`, `RecoveryInstallVerifier.kt`, `docs/SIDELOAD_*.md` |
| Синтаксис команд терминала | legacy `MainActivity.kt` → `parseAdbCommand`/`parseFastbootCommand`/`tokenizeCommandLine` (сам разбор; класс вокруг — нет) |
| Mi Unlock | legacy `MiUnlockClient.kt`, `MiAccountClient.kt`, `MiAccountSecurityPolicy.kt`, `MiLoginActivity.kt` |
| Особенности устройств | A2 `docs/KNOWN_USB_DEVICE_QUIRKS.md`, `docs/LEGACY_BEHAVIOR_BASELINE.md`. Особенность описывает устройство в конкретной роли (цель или хост) и на другую роль без своего наблюдения не переносится |

Тесты A2 (36 файлов) описывают проверенные краевые случаи: переносишь механизм — сверяй набор случаев, а не только реализацию. В legacy тестов нет, его доказательства — аппаратные прогоны.

## 7. Что не переносить из legacy и A2

- широкую политику авторизации хоста Fastboot по смеси `unlocked`/`max-download-size`/`partition-size` (A2 `FastbootUsbTransport.kt`, `FastbootFlashPolicy.authorize`);
- read-only ADB без общих сервисов, один логический поток, общую очередь пакетов, закрывающую чужие потоки;
- экран-шлагбаум при входе и «прими риски»;
- «god»-классы: A2 `UsbSessionCoordinator` (~2,5 тыс. строк), legacy `MainActivity` (~3,9 тыс.), `AdbProtocol`, `FastbootProtocol`, `DeviceViewModel`;
- глобальный `OperationCoordinator` A2 с одним `Stage` на разные операции;
- владение долгими операциями экраном, буферизацию файла целиком, неявные повторы и тайм-ауты;
- протокольный код, смешанный с UI или vendor-логикой;
- устаревшие стадийные документы и промпты, `.orig` и прочие хвосты.
