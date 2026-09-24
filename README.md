# NekoFlash Pro — начало переписывания

Новый проект отделён от legacy NekoFlash 6.0.0-alpha11. Исходный код legacy не
включён в этот репозиторий: он служит источником проверенных протокольных правил
и сценариев для будущей проверки на устройствах. Контракт команд сверяется с
официальными Android SDK Platform-Tools 37.0.1.

## Что уже есть

- `:engine` — независимый от Android разбор терминальных команд в типизированные
  намерения. `format`, `-w`, `wipe-super`, `update`, `flashall` и сборка
  `vendor_boot:RAMDISK` не превращаются в обычные Fastboot wire-строки. Опции
  сохраняются. Неизвестная команда `adb` не заменяется на `shell`.
- `:core:transport` и `:core:fastboot` — порт транспорта и первая проверяемая
  часть Fastboot wire-протокола: ответы `INFO`, `TEXT`, `OKAY`, `FAIL`, `DATA`;
  неподдержанная DATA-фаза закрывает сессию. Android USB-адаптер пока отсутствует.
- `:app` — устанавливаемая **техническая сборка** с новым `applicationId`.
  Она сообщает, что выполнение команд ещё не подключено; никаких действий
  над устройством не выполняет.
- `.github/workflows/build.yml` — JVM-тесты, lint Android и debug APK
  на push, pull request и ручной запуск. APK доступен как artifact запуска.

**Прошивка, Android USB-транспорт, план исполнения, Preview, Sync v2 и большие
образы ещё не реализованы.** Успех CI подтверждает сборку технического APK и
тесты парсера, а не совместимость с устройствами или готовность P0.

Критерии P0 и точное состояние команд указаны в
[`docs/00_Command_Parity_P0.md`](docs/00_Command_Parity_P0.md). Обязательные
архитектурные решения собраны в [`docs/01_Decisions.md`](docs/01_Decisions.md).

## Сборка

На машине с JDK 17, Android SDK 36 и доступом к Gradle и Maven:

```sh
./gradlew :engine:test :core:fastboot:test :app:lintDebug :app:assembleDebug
```

Debug APK окажется в `app/build/outputs/apk/debug/`. Подписанный релиз и
публикация APK пока не настроены. На GitHub тот же набор задач запускается
автоматически; APK скачивается с вкладки **Actions → Pro prototype build →
Artifacts** после успешного запуска. Секреты и release-ключ от legacy
сюда не переносятся.

## Работа с репозиторием через Termux

Приватный репозиторий: `https://github.com/Ncorror/NekoFlash-Pro`.
На телефоне с авторизованным GitHub CLI можно получить исходники:

```sh
pkg install git gh
gh auth status
gh repo clone Ncorror/NekoFlash-Pro
cd NekoFlash-Pro
```

Если `gh auth status` показывает отсутствие авторизации, выполните
`gh auth login` в Termux. Сборки и отчёты тестов доступны во вкладке
**Actions** репозитория. Для отправки собственных изменений используйте
обычные `git add`, `git commit` и `git push` после настройки `git user.name`
и `git user.email`.
