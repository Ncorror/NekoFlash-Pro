# Аудит терминального слоя NekoFlash Pro

## Что найдено до рефакторинга

В исходнике нет собственного процесса оболочки: отсутствуют `forkpty`, `openpty`,
`posix_openpt`, `SIGCHLD`, `SIGWINCH` и JNI-методы терминала. Единственный C++
модуль — `usb/native/src/main/cpp/native_usbfs.cpp`, backend Fastboot USBFS.
Терминал приложения — удалённый `adb shell,v2,pty` с Kotlin VT-эмулятором.
Поэтому JNI/pty-код не удалялся и не переписывался: его в проекте нет; resize
реализуется shell-v2 frame `5` (`ROWSxCOLS,0x0\0`), а не локальным сигналом.

Следов посторонних нейросетевых конфигураций в исходнике не обнаружено. Тестовые
экраны и debug-кнопки в production UI не найдены. `scripts/termux-push.sh` —
рабочий release-helper, поэтому не удалялся.

## Исправления

### `app/.../adb/AdbTerminalController.kt`

- исключение в reader больше не оставляет вкладку навсегда в `active=true`;
- открытие, ввод, resize, ответы VT, flush и закрытие всегда уходят через
  защищённый writer executor;
- ошибки writer фиксируются в diagnostics и закрывают зависшую сессию;
- resize немедленно публикует новый экран и не ждёт следующего байта вывода;
- добавлена проверка, что UI не обновляется без визуального изменения экрана.

### `protocol/adb/.../AdbInteractiveShell.kt`

- resize принимает только значения `1..1000`;
- серия resize-сообщений схлопывается до последнего неснятого значения;
- очередь исходящих данных ограничена 1 MiB, чтобы большие вставки не росли
  бесконечно;
- ошибка WRTE переводит сессию в завершённое состояние вместо вечного ожидания ACK;
- закрытие очищает mailbox и очередь.

### `protocol/adb/.../AdbShellFrameBuffer.kt`

- накопитель незавершённого shell-v2 frame ограничен 2 MiB;
- переполнение превращается в `Broken`, а не в неограниченный рост памяти.

### `app/.../terminal/VtScreen.kt`, `VtBuffer.kt`

- исправлен SGR в colon-форме (`38:2::R:G:B`), где пустой subparameter ранее
  сдвигал RGB;
- alternate screen теперь начинает работу с `(0,0)`;
- добавлен monotonic revision, чтобы UI не собирал одинаковый snapshot повторно.

### Starship

Добавлен `app/.../terminal/StarshipInstaller.kt`:

- ищет `assets/starship/aarch64/starship`, `assets/starship/arm64-v8a/starship`
  или `assets/starship`;
- копирует ELF атомарно, проверяет `ELFCLASS64` и `EM_AARCH64`, выставляет `0755`;
- создаёт приватный `terminal/home/.bashrc` с `HOME`, `PATH` и
  `eval "$(starship init bash)"`;
- выполняется по умолчанию в `Application.onCreate` на `Dispatchers.IO`, но
  отсутствие optional asset не ломает запуск.

В удалённом ADB-профиле Starship включается только если на самом target есть
совместимый `bash` и `starship`; штатный Android `mksh` получает безопасный
fallback. Бинарник из APK нельзя автоматически считать бинарником удалённого
устройства: ABI, libc и права доступа разные.

`TerminalFont.kt` загружает `fonts/NerdFontMono-Regular.ttf` через Compose
`Font(path, assetManager, ...)` и применяет его к VT-экрану и EditText. При
отсутствии файла используется `FontFamily.Monospace`. В архиве оставлены
`app/src/main/assets/fonts/README.md` и `app/src/main/assets/starship/README.md`;
сторонние бинарные/шрифтовые файлы намеренно не подменялись заглушками.

## Ограничение Android

На Android 10+ `execve()` из writable app home запрещён платформой. Поэтому
`StarshipInstaller` сохраняет бинарник и профиль как staging-артефакты, но
`localBashCommand()` отказывается запускать их на API 29+. Для настоящего
локального PTY Starship нужно паковать как executable code в ABI-specific APK
payload либо устанавливать его на target device через ADB Sync и отдельно
проверять ABI/libc. Текущий архив реализует только безопасную часть, не создавая
ложно работающий локальный shell.

## Проверки

- `rg` по исходнику: native terminal symbols (`forkpty`, `openpty`, `SIGWINCH`,
  JNI terminal methods) отсутствуют; упоминание resize-сигнала осталось только
  в поясняющем Kotlin-комментарии. Starship binary/font до добавления обвязки
  также отсутствовали.
- Добавлены unit checks для colon-TrueColor, invalid resize и Starship fallback.
- Gradle unit tests не запустились в среде упаковки: wrapper требует скачать
  Gradle 9.5.0, а сетевой доступ к `services.gradle.org` недоступен.
