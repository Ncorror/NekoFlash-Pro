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

### Удаление неиспользуемого локального shell staging

Ранее в проекте существовал `StarshipInstaller`, рассчитанный на будущий
локальный Android shell. В текущем продукте такого локального PTY нет: терминал
NekoFlash Pro работает через удалённый `adb shell,v2,pty`.

После удаления `assets/starship` установщик стал недостижимой продуктовой
концепцией и на каждом запуске приложения выполнял только бесполезный поиск
отсутствующего asset. Поэтому из production-кода удалены:

- `app/.../terminal/StarshipInstaller.kt`;
- startup-hook `starshipInstaller.install()` из `NekoFlashApplication`;
- сам объект `starshipInstaller` и его импорт.

Это не ограничивает ADB-терминал и не меняет shell-v2 протокол: на удалённое
устройство Starship из APK никогда не отправлялся и рабочему терминалу не был
нужен.

`TerminalFont.kt` загружает `fonts/NerdFontMono-Regular.ttf` через Compose
`Font(path, assetManager, ...)` и применяет его к VT-экрану и EditText. При
отсутствии файла используется `FontFamily.Monospace`. В репозитории остаётся
`app/src/main/assets/fonts/README.md`; сторонний шрифт намеренно не подменяется
заглушкой.

## Проверки

- `rg` по исходнику: native terminal symbols (`forkpty`, `openpty`, `SIGWINCH`,
  JNI terminal methods) отсутствуют; resize остаётся shell-v2 операцией;
- старый remote-shell profile и его тесты удалены как недостижимые;
- Starship staging удалён после удаления соответствующих assets и отсутствия
  локального PTY-пути;
- unit tests покрывают VT resize/scrollback, input routing и shell-v2 поведение;
- CI выполняет repository hygiene, localization contract, Kotlin style,
  complexity/module-boundary checks, Compose wiring, unit tests, lint и сборку
  debug/release APK.
