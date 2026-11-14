package app.gcaltolotus

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.gcaltolotus.app.AdapterCalendarSource
import app.gcaltolotus.app.CalendarDeps
import app.gcaltolotus.app.ConnectionChecker
import app.gcaltolotus.app.DbLinkRepository
import app.gcaltolotus.app.GitHubReleases
import app.gcaltolotus.app.SyncService
import app.gcaltolotus.app.UpdateCheck
import app.gcaltolotus.app.saveDominoCredentials
import app.gcaltolotus.auth.GoogleAuthorizer
import app.gcaltolotus.auth.TeamsAccount
import app.gcaltolotus.auth.isGoogleSignInRequired
import app.gcaltolotus.auth.teamsConfigFromBuild
import app.gcaltolotus.background.SyncNotifications
import app.gcaltolotus.background.nextBackgroundRun
import app.gcaltolotus.background.scheduleBackgroundSync
import app.gcaltolotus.background.scheduleUpdateCheck
import app.gcaltolotus.db.SyncDatabase
import app.gcaltolotus.platform.DataStoreSettingsStore
import app.gcaltolotus.platform.DataStoreUpdateStore
import app.gcaltolotus.platform.KeystoreSecretCipher
import app.gcaltolotus.platform.KeystoreSecretStore
import app.gcaltolotus.platform.DataStoreRunHistory
import app.gcaltolotus.platform.HttpConnections
import app.gcaltolotus.ui.AccountActions
import app.gcaltolotus.ui.AppDependencies
import app.gcaltolotus.ui.BackgroundScheduler
import app.gcaltolotus.ui.CheckerConnectionChecks
import app.gcaltolotus.ui.RunHistory
import app.gcaltolotus.ui.SystemControls
import app.gcaltolotus.ui.ServiceGoogleCalendars
import app.gcaltolotus.ui.SyncRuns
import app.gcaltolotus.ui.noticeChanges
import io.ktor.client.HttpClient
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Граф объектов приложения (без DI-фреймворка): один на процесс, создаётся в [GcalApp].
 * Им пользуются экраны и [app.gcaltolotus.background.SyncWorker].
 */
class AppGraph(context: Context) {
    private val app: Context = context.applicationContext

    /** Фоновые задачи уровня приложения (перепланирование фона по настройкам). */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settingsData: DataStore<Preferences> =
        PreferenceDataStoreFactory.create { app.preferencesDataStoreFile("settings") }
    private val secretsData: DataStore<Preferences> =
        PreferenceDataStoreFactory.create { app.preferencesDataStoreFile("secrets") }
    private val historyData: DataStore<Preferences> =
        PreferenceDataStoreFactory.create { app.preferencesDataStoreFile("history") }
    private val updatesData: DataStore<Preferences> =
        PreferenceDataStoreFactory.create { app.preferencesDataStoreFile("updates") }

    val settings = DataStoreSettingsStore(settingsData)
    val secrets = KeystoreSecretStore(secretsData, KeystoreSecretCipher())

    /** Итог последнего запуска: пишут экран синхронизации и [app.gcaltolotus.background.SyncWorker]. */
    val history: RunHistory = DataStoreRunHistory(historyData)

    val database: SyncDatabase by lazy { SyncDatabase(AndroidSqliteDriver(SyncDatabase.Schema, app, "sync.db")) }

    /** Клиент для Google и Teams. */
    /** Общий пул соединений: перед каждым запуском синхронизации — [HttpConnections.dropIdle]. */
    val httpConnections = HttpConnections()

    val http: HttpClient by lazy { httpConnections.createClient() }

    /** Отдельный клиент для Domino (без повторов, как и [http]). */
    val dominoHttp: HttpClient by lazy { httpConnections.createClient() }

    val google = GoogleAuthorizer(app, secrets, settingsData)
    val teams: TeamsAccount by lazy { TeamsAccount(http, teamsConfigFromBuild(), secrets) }

    val notifications = SyncNotifications(app)


    /** Платформенные зависимости адаптеров. */
    val calendarDeps: CalendarDeps by lazy {
        CalendarDeps(
            http = http,
            dominoHttp = dominoHttp,
            teamsConfig = teams.config,
            googleAccessToken = google.accessTokenProvider,
            log = { Log.i("gcaltolotus", it) },
        )
    }

    val syncService: SyncService by lazy {
        SyncService(settings, AdapterCalendarSource(secrets, calendarDeps), DbLinkRepository(database))
    }

    /**
     * Запуски синхронизации: общие для экранов и фонового обработчика (один запуск за раз,
     * приоритет фона). Перед каждым запуском сбрасываются простаивающие соединения
     * ([HttpConnections.dropIdle]); закрытие TLS-сокета — сетевая операция, поэтому в IO.
     * После каждого запуска, ручного и фонового, обновляются уведомления ([noticeChanges]).
     */
    val syncRuns: SyncRuns by lazy {
        SyncRuns(
            syncService,
            history,
            beforeNetwork = { withContext(Dispatchers.IO) { httpConnections.dropIdle() } },
            afterRun = { outcome ->
                val changes = noticeChanges(outcome, settings.load(), Clock.System.now()) { it.isGoogleSignInRequired() }
                notifications.apply(changes)
            },
        )
    }

    /** Проверка новой версии в GitHub Releases (раз в неделю, [app.gcaltolotus.background.UpdateWorker]). */
    val updates: UpdateCheck by lazy {
        UpdateCheck(GitHubReleases(http), DataStoreUpdateStore(updatesData), BuildConfig.VERSION_NAME)
    }

    /** Проверки подключения для экрана настроек. */
    val connectionChecker: ConnectionChecker by lazy { ConnectionChecker(settings, secrets, calendarDeps) }

    /** Сохранить адрес, пользователя и пароль Domino; ввод пароля снимает выключение Lotus. */
    suspend fun saveDomino(url: String, user: String, password: String?) =
        saveDominoCredentials(settings, secrets, url, user, password)

    /**
     * Зависимости экранов для Activity: вход привязан к ней, остальное — общее для процесса.
     * [accounts] создаётся Activity (регистрирует ActivityResultLauncher), [systemControls] тоже
     * (запрос разрешения на уведомления).
     */
    fun appDependencies(
        accounts: AccountActions,
        systemControls: SystemControls,
    ): AppDependencies = AppDependencies(
        settings = settings,
        secrets = secrets,
        syncService = { syncService },
        accounts = accounts,
        checks = CheckerConnectionChecks(settings) { connectionChecker },
        googleCalendars = ServiceGoogleCalendars { syncService },
        history = history,
        // Сам фон планирует start() по настройкам; экрану нужно только время следующего запуска.
        scheduler = BackgroundScheduler { nextBackgroundRun(app) },
        systemControls = systemControls,
        syncRuns = { syncRuns },
        updates = updates,
    )

    /**
     * Создать каналы уведомлений; следить за настройками: перепланировать фон при смене периода,
     * убрать устаревшие уведомления.
     */
    internal fun start() {
        notifications.ensureChannels()
        scheduleUpdateCheck(app)
        settings.settings.map { it.backgroundIntervalMinutes }.distinctUntilChanged()
            .onEach { scheduleBackgroundSync(app, it) }
            .launchIn(scope)
        settings.settings.map { it.dominoDisabled }.distinctUntilChanged()
            .onEach { disabled -> if (!disabled) notifications.clearLotusDisabled() }
            .launchIn(scope)
        // Итог выключен в настройках: показанное уведомление с итогом убирается.
        settings.settings.map { it.notifyBackgroundSummary }.distinctUntilChanged()
            .onEach { on -> if (!on) notifications.clearSummary() }
            .launchIn(scope)
    }

    companion object {
        /** Граф процесса; [context] — любой контекст приложения. */
        fun get(context: Context): AppGraph = (context.applicationContext as GcalApp).graph
    }
}
