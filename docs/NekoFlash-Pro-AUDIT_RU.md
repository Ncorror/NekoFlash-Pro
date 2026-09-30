# Аудит и рефакторинг NekoFlash-Pro

Проверен распакованный проект `NekoFlash-Pro-main` из предоставленного архива.
В исходном дереве было 437 файлов: 242 production-файла в `src/main` и 126
тестовых файлов. После очистки осталось 433 файла; тестовые исходники сохранены.

## Удалено до рефакторинга

| Путь | Что удалено | Причина |
| --- | --- | --- |
| `CLAUDE.md` | инструкция внешнего AI-агента | не относится к сборке приложения и содержит следы генератора |
| `.claude/settings.json` | конфигурация внешнего AI-инструмента | не должна попадать в репозиторий продукта |
| `.claude/hooks/session-start.sh` | hook внешнего AI-инструмента | не используется Android/C++ сборкой |
| `reference/archives/NekoFlash-main-2026-09-14.zip` | вложенный исторический бинарный архив | мог переносить удалённые конфигурации и водяные знаки; production-сборка его не использует |
| `usb/native/.../NativeUsbfsBackend.kt` и `native_usbfs.cpp` | неиспользуемый `nativeUsbfsResetDevice`/`USBDEVFS_RESET` | JNI-метод не имел вызовов; удалён Kotlin-wrapper и C++ export целиком |

После удаления бинарного снимка `reference/SHA256SUMS` оставлен пустым, а
`reference/README.md` переписан так, чтобы не обещать отсутствующие архивы.
Старые ссылки на удалённый снимок убраны из README, ADR, migration-документации
и комментариев исходников.

В production UI не обнаружены отдельные debug-кнопки, тестовые экраны, mock/fake
данные или тестовые манипуляции состояниями. Поэтому реальные функции
`command_preview_title`, экспорт диагностики и `TerminalPlanResult.NotImplemented`
сохранены: они являются частью пользовательского поведения, а не тестовыми
заглушками. В `src/main` не осталось `TODO`, `FIXME`, `NotImplementedError` или
закомментированных фрагментов, похожих на исполняемый код.

## Финальный вариант по файлам

### Android USB и жизненный цикл Kotlin

**`usb/android/src/main/kotlin/io/github/ncorror/nekoflash/usb/android/AndroidUsbHost.kt`**

- Callback-и `BroadcastReceiver` перенесены с main thread на отдельный daemon
  executor. События старой активации отбрасываются через generation и проверку
  identity слушателя.
- Повторный `start` корректно закрывает прошлую активацию; частично
  зарегистрированный receiver откатывается при `SecurityException` или
  `IllegalArgumentException`.
- `UsbTransportHandle` считает активные platform transfers, поэтому `close()`
  больше не ждёт drain URB на UI-потоке. Освобождение интерфейса выполняется
  асинхронно после завершения передач и `NativeUsbfsShutdown.prepareClose`.
- Передача проверяет границы буфера, состояние handle и переводит любой
  отрицательный результат `bulkTransfer` в контракт транспорта. После неудачного
  OUT очищается `ENDPOINT_HALT` без автоматической повторной отправки.

**`usb/api/src/main/kotlin/io/github/ncorror/nekoflash/usb/api/UsbSessionCoordinator.kt`**

- Публикация handle сериализована с detach/release. Если устройство исчезло
  между `host.claim()` и публикацией, новый handle закрывается и наружу
  возвращается `DEVICE_GONE`.
- Ответ разрешения применяется только при успешных переходах registry; устаревший
  ответ больше не превращается в ложное `permission_granted`.

**`usb/api/src/main/kotlin/io/github/ncorror/nekoflash/usb/api/UsbTransportHandle.kt`**

- Уточнён комментарий nullable `fileSender`: отсутствие native-пути означает
  штатный Java/Kotlin DATA-путь.

### ADB framing и dispatch

**`protocol/adb/src/main/kotlin/io/github/ncorror/nekoflash/protocol/adb/AdbPacketReader.kt`**

- Добавлена проверка, что transport не сообщил отрицательное или превышающее
  запрошенное число байт.
- Короткий payload, отказ передачи и invalid transfer length получают разные
  исходы; дочитывание объявленного payload не маскирует потерю рамки.

**`protocol/adb/src/main/kotlin/io/github/ncorror/nekoflash/protocol/adb/AdbPacketWriter.kt`**

- Нулевая, отрицательная или сверхлимитная запись теперь считается
  неоднозначным прерыванием и не повторяется.

**`protocol/adb/src/main/kotlin/io/github/ncorror/nekoflash/protocol/adb/AdbPacketHeader.kt`**

- `encode` принимает только unsigned 32-bit command; исключены отрицательные и
  переполненные значения до формирования 24-байтового заголовка.

**`protocol/adb/src/main/kotlin/io/github/ncorror/nekoflash/protocol/adb/AdbDispatchLoop.kt`**

- Ошибка записи ответа dispatcher больше не игнорируется: transport закрывается
  с `TRANSPORT_CLOSED` или `FRAMING_LOST`, а mailbox-и получают единую причину.

**`protocol/adb/src/main/kotlin/io/github/ncorror/nekoflash/protocol/adb/AdbInboundFraming.kt`**

- Убрана ссылка на удалённый бинарный источник; правило неделимого входящего
  payload сохранено.

### Fastboot framing и операции

**`protocol/fastboot/src/main/kotlin/io/github/ncorror/nekoflash/protocol/fastboot/FastbootLane.kt`**

- Добавлен явный `FastbootExchange.FramingError`.
- Некорректная длина USB-результата, parse exception и ответ, который нельзя
  классифицировать, немедленно переводят lane в `STALLED`; это предотвращает
  повторную команду поверх неизвестного состояния загрузчика.
- Команда проверяется на ASCII и размер до 4096 байт; короткая/сверхдлинная
  запись не считается успешной.
- Положительные бюджеты бездействия обязательны для команд и DATA-фаз; короткие
  записи DATA дописываются, но неоднозначные результаты и ранний EOF останавливают
  lane.

**`FastbootDownload.kt`, `FastbootFetch.kt`, `FastbootGetVar.kt`,
`FastbootMutation.kt`, `FastbootXiaomiUnlock.kt`**

- Новый `FramingError` проведён через все операции: он возвращается как
  `Unknown`/`Unavailable`/`Broken`, а не теряется в `else` и не превращается в
  таймаут.

**`app/src/main/kotlin/io/github/ncorror/nekoflash/fastboot/FastbootLinkController.kt`**

- Введены `lifecycleEpoch` и lock вокруг lane/handle/state. Результат старого
  probe или команды не может восстановить состояние после disconnect или нового
  connect.
- Rejoin закрывает старые ресурсы до нового probe; stale probe сам закрывает
  claimed handle.
- Публикация console-state атомарно проверяет epoch после выполнения USB-операции,
  поэтому поздний worker не возвращает `Running/Answered` на уже отключённый
  экран.
- Mi Unlock защищён от публикации `AwaitingReconnect` и verification для старой
  USB-сессии; pending marker сравнивается по identity объекта.
- Контроллер оставляет один публичный путь для typed commands, plans и ручного
  Fastboot ввода.

### C++/JNI и native USBFS

**`usb/native/src/main/cpp/native_usbfs.cpp`**

- `pread_exact` и preflight проверяют overflow `off_t`, диапазон endpoint address,
  направление endpoint и положительные дедлайны до открытия URB.
- Удалён неиспользуемый reset JNI export.
- RAII для descriptor/URB сохранён. `PendingUrbOwnershipGuard` намеренно
  оставляет ограниченный буфер живым, если kernel не подтвердил drain: это
  fail-closed защита от use-after-free, после которой backend poison-ится до
  перезапуска процесса. Это единственный намеренный bounded leak и он безопаснее
  освобождения памяти, на которую ещё может ссылаться usbfs.
- JNI экспортов ровно три и они совпадают с Kotlin declarations:
  `nativeBulkOutUrb`, `nativeCancelTransfer`, `nativeBackendState`.

**`usb/native/src/main/kotlin/io/github/ncorror/nekoflash/fastboot/transport/NativeUsbfsBackend.kt`**

- `null`-результат JNI теперь превращается в структурированный JNI failure после
  остановки progress-thread; NPE больше не скрывает исход передачи.
- Снята мёртвая reset-обёртка; токен, progress polling и cleanup transfer не
  менялись.

**`usb/native/src/main/kotlin/io/github/ncorror/nekoflash/usb/usbfs/NativeUsbfsFileSender.kt`**

- Подготовка отправки сериализована с release: закрытый handle и параллельная
  native передача отвергаются до дублирования descriptor.
- Состояние `sending` сбрасывается и при ошибке duplicate, и при исключении JNI;
  descriptor всегда закрывается через `use`.

### Документация, конфигурация и строки UI

Изменены `README.md`, `docs/README.md`, `docs/05_ROADMAP.md`,
`docs/06_MIGRATION.md`, `docs/07_BUILD_AND_TESTING.md`, `docs/08_DECISIONS.md`,
ADR `0001`–`0006`, `app/src/main/kotlin/.../NekoFlashColors.kt`, английские и
русские `strings.xml`, `scripts/ci/check_docs.py` и
`scripts/dev/provision_android_toolchain.sh`.

Из них удалены только ссылки/комментарии на внешние AI-конфигурации и старые
бинарные снимки. Текст панели recent diagnostics больше не говорит оператору,
что он «тестирует» приложение; описывается текущая диагностическая активность.
Скрипт документации теперь проверяет реальный набор файлов после удаления
агентской инструкции.

## Проверки

Успешно выполнены:

- `scripts/ci/check_docs.py` — `PASS` (18 документов, 12 модулей, архивы сверены);
- `scripts/ci/check_localization.py` — `PASS`;
- `scripts/ci/check_compose_wiring.py` — `PASS`;
- `scripts/ci/check_kotlin_style.sh` с Detekt 1.23.8 — `PASS` (style, engine,
  module boundaries);
- `python3 -m py_compile` для CI-скриптов — `PASS`;
- `bash -n` для provisioning/release/signing scripts — `PASS`;
- проверка NUL-байтов и trailing whitespace — `PASS`;
- поиск `claude|anthropic|.claude` в исходном дереве — пустой результат;
- сверка Kotlin JNI declarations с C++ exports — `3 ↔ 3`.

Не выполнены из-за ограничений окружения:

- `./gradlew test`: wrapper требует Gradle 9.5.0, локального дистрибутива нет,
  сетевой fetch завершился `Network is unreachable`;
- C++ `-fsyntax-only`: в окружении нет `jni.h` и Android NDK headers;
- hardware USB/OTG прогон: физического устройства и Android host нет;
- `check_repository_hygiene.sh` целиком: sandbox не предоставляет `/dev/fd` и
  git metadata; его проверки NUL/trailing whitespace выполнены эквивалентно.

Очищенный исходный архив и этот отчёт переданы отдельно; перед релизом нужно
повторить `./gradlew test` и native compile на CI с Android SDK/NDK и прогнать
ADB/Fastboot на реальном OTG-устройстве.
