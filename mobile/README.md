# Мобильное приложение (Kotlin Multiplatform)

Синхронизатор календарей для телефона: сначала Android, потом iOS. Решение и его причины описаны
в [docs/adr/0002-mobile-kmp-on-device.md](../docs/adr/0002-mobile-kmp-on-device.md), термины в
[GLOSSARY.md](../GLOSSARY.md).

## Модули

```
mobile/
├── gradle/libs.versions.toml   версии всех библиотек и плагинов
├── shared/                     общий код: движок и адаптеры (позже), общий UI на Compose
│   └── src/
│       ├── commonMain/kotlin           код без Android- и JVM-зависимостей
│       ├── commonMain/composeResources строки интерфейса: values/ (русский, по умолчанию), values-en/
│       └── commonTest/kotlin           тесты общего кода (kotlin.test)
└── androidApp/                 Android-приложение: MainActivity показывает App() из shared
```

Цели `shared`: `jvm` (быстрые тесты на Linux), `android`, `iosArm64`, `iosSimulatorArm64`.
Цели iOS на Linux не собираются, их задачи пропускаются; они объявлены, чтобы в `commonMain`
не попали Android- и JVM-only API. `iosX64` не объявлен: Compose Multiplatform его больше не
поддерживает.

В `shared` уже подключены Ktor (OkHttp на Android/JVM, Darwin на iOS), kotlinx-serialization,
kotlinx-datetime, kotlinx-coroutines и SQLDelight (база `SyncDatabase`, пакет `app.gcaltolotus.db`,
файлы `.sq` кладутся в `shared/src/commonMain/sqldelight/`).

## Экраны (`shared/.../ui/`)

Общий UI на Compose Multiplatform (Material 3), тот же на iOS: «Состояние» (последний запуск,
открытые вопросы, Lotus выключен или недоступен), «Синхронизация» (маршруты → предпросмотр →
применить → отчёт), «Подключения» (Google, Lotus, Teams; вложенный экран выбора календаря Google),
«Настройки» (окно, период фоновой синхронизации, фон и уведомления, импорт связей). Строки — в `composeResources`:
`values/` (русский), `values-en/` (английский), язык берётся из системы.

Тема (`ui/Theme.kt`, `GcalTheme`) следует системной светлой/тёмной настройке. На Android 12+
androidApp передаёт в `App(dynamicColorScheme = …)` цвета Material You из обоев; без них
(Android 10–11, iOS) — серо-синяя палитра `GcalLightColors`/`GcalDarkColors`. Фон окна и
заставки в `androidApp/src/main/res/values*/colors.xml` совпадает с фоном палитры.

Состояние экранов — простые классы со `StateFlow` (`*Model`), навигация — enum `Screen`.
«Назад» с вложенного экрана ведёт в его раздел, с предпросмотра или отчёта — к выбору маршрутов,
с остальных разделов — на «Состояние»; на «Состоянии» приложение закрывается. Фоновая
синхронизация на новой установке выключена до первого успешного ручного применения, затем
включается раз в час (если пользователь сам не выбрал период); первый фоновый запуск — через
период (ADR 0003).
Платформа передаёт в `App(AppDependencies)` реализации интерфейсов из `ui/Dependencies.kt`:
`AccountActions` (вход в Google и Teams), `ConnectionChecks`, `GoogleCalendars` (список и
создание календарей), `TextFilePicker` (файл выгрузки связей), `RunHistory` (итог последнего
запуска; фоновый обработчик пишет его через `LastRun.background`), `BackgroundScheduler`,
`SystemControls` (раздел «Фон и уведомления»: оптимизация батареи, разрешение и каналы
уведомлений; `resumes` — возврат в приложение, после него состояние перечитывается;
`InMemorySystemControls` — для тестов и демо).
Платформонезависимые реализации (`CheckerConnectionChecks`, `ServiceGoogleCalendars`,
`LastRunCodec`) лежат в `ui/ServiceBridges.kt`; androidApp собирает зависимости в
`AppGraph.appDependencies`. `demoDependencies()` (всё в памяти, без сети) — только для тестов и
предпросмотра UI. Ручной запуск держит `AppGraph.syncLock` только пока строит предпросмотр и пока
применяет его; открытый предпросмотр фон не задерживает. Если за время показа предпросмотра
закончился другой запуск (`RunHistory`), «Применить» под блокировкой строит предпросмотр заново
с прежними галочками и показывает баннер. Фоновый запуск при занятой блокировке возвращает
`Result.retry()` (повтор через минуту, линейно). «Состояние» перечитывается раз в минуту и при
возврате в приложение; прошедшее время следующего запуска показывается как «скоро».

## Сборка и тесты

Нужны JDK 21 и Android SDK 36. Путь к SDK задаётся в `local.properties` (файл не в git):

```properties
sdk.dir=/путь/к/android/sdk
```

Команды запускаются из каталога `mobile/`:

```bash
./gradlew :shared:jvmTest              # тесты общего кода на JVM
./gradlew :androidApp:assembleDebug    # APK: androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :androidApp:installDebug     # установить на подключённое устройство или эмулятор
./gradlew :androidApp:testDebugUnitTest  # JVM-тесты платформенного слоя Android
```

## Android: настройки сборки и платформенный слой

Gradle-свойство `teams.clientId` (в `mobile/local.properties` или `~/.gradle/gradle.properties`)
переопределяет client id регистрации Teams; по умолчанию берётся client id из
`androidApp/build.gradle.kts`, пустое значение — Teams «не настроен». Teams на телефоне —
публичный клиент (redirect `http://localhost`), секрета в приложении нет.

Google входит через Authorization API Google Play services: client id в коде не нужен, Play
services находит OAuth-клиент типа Android по имени пакета `app.gcaltolotus` и SHA-1 ключа
подписи (для отладочной сборки — `~/.android/debug.keystore`).

Код `androidApp` (пакет `app.gcaltolotus`):

- `AppGraph` — граф объектов процесса (DataStore, Keystore, SQLite `sync.db`, Ktor, `SyncService`);
- `platform/` — `DataStoreSettingsStore`, `KeystoreSecretStore` (AES-GCM, ключ в Android Keystore),
  `createHttpClient` (OkHttp, без повторов запросов);
- `auth/` — вход в Google (`GoogleAuthorizer`, `GoogleSignInLauncher`) и Teams (`TeamsAccount`:
  loopback-сервер на свободном порту 127.0.0.1 и Custom Tab);
- `background/` — `SyncWorker` (WorkManager, период из настроек, только при сети; пишет итог в
  `RunHistory`) и уведомления; касание уведомления ведёт на экран через `openRequestFor`
  (вопросы — синхронизация с предпросмотром, Lotus и Google — подключения, итог — состояние).
  Каналы (`NotificationChannels.kt`): «Вопросы» (обычная важность), «Проблемы с подключением»
  (высокая), «Итог фоновой синхронизации» (низкая, без звука; уведомление только при включённом
  в настройках переключателе). Прежний общий канал `sync` удаляется при старте;
- `platform/AndroidSystemControls` — `SystemControls`: снятие оптимизации батареи
  (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, приложение не из Play), экран приложения, если фон
  запрещён, настройки уведомлений и канала итога;
- `auth/AndroidAccountActions` — `AccountActions` поверх входа Google и Teams; `platform/` также
  содержит `DataStoreRunHistory` и `DocumentTextPicker` (системный выбор файла).
