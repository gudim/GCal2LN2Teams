package app.gcaltolotus.ui

import app.gcaltolotus.adapters.domino.DominoException
import app.gcaltolotus.adapters.google.GoogleApiException
import app.gcaltolotus.adapters.teams.GraphException
import app.gcaltolotus.adapters.teams.TeamsAuthException
import app.gcaltolotus.app.BackgroundReport
import app.gcaltolotus.app.RouteCounts
import app.gcaltolotus.app.RunReport
import app.gcaltolotus.app.SecretStore
import app.gcaltolotus.app.SettingsStore
import app.gcaltolotus.app.SyncService
import app.gcaltolotus.app.UpdateCheck
import app.gcaltolotus.sync.System
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/*
 * Что экраны ждут от платформы. Всё, что зависит от Android или iOS (вход через браузер, выбор
 * файла, WorkManager), спрятано за этими интерфейсами; androidApp их реализует и передаёт в [App].
 */

/** Состояние входа в систему: выполнен ли вход и под каким аккаунтом (если известно). */
data class AccountStatus(val signedIn: Boolean, val account: String? = null) {
    companion object {
        val SignedOut: AccountStatus = AccountStatus(signedIn = false)
    }
}

/**
 * Вход в Google и Teams. Реализация платформенная: Google — клиент Android, Teams — OAuth через
 * браузер с приёмом redirect на телефоне (ADR 0002).
 *
 * `signIn*` возвращает состояние после входа: [AccountStatus.SignedOut], если пользователь
 * отменил вход; ошибка входа — исключение (его текст показывается пользователю).
 */
interface AccountActions {
    suspend fun googleStatus(): AccountStatus

    suspend fun signInGoogle(): AccountStatus

    suspend fun signOutGoogle()

    suspend fun teamsStatus(): AccountStatus

    suspend fun signInTeams(): AccountStatus

    suspend fun signOutTeams()
}

/** Итог проверки подключения. [message] — подробности от сервера или адаптера (как есть). */
sealed interface CheckResult {
    /** Подключение работает; [detail] — например, имя календаря или пользователя. */
    data class Ok(val detail: String? = null) : CheckResult

    /** Сервер отверг вход (401/403, отозванный токен). */
    data class AuthFailed(val message: String? = null) : CheckResult

    /** Сервер не ответил или ответил ошибкой (сеть, DNS, TLS, 5xx). */
    data class Unreachable(val message: String? = null) : CheckResult

    /** Проверять нечего: нет входа или не заполнены настройки; в сеть не ходили. */
    data class NotConfigured(val message: String? = null) : CheckResult
}

/**
 * Проверка подключения к системе (кнопка «Проверить»). Проверка Lotus не должна повторять
 * запрос после 401: это защита от блокировки учётной записи.
 */
fun interface ConnectionChecks {
    suspend fun check(system: System): CheckResult
}

/** Календарь Google в списке выбора. */
data class GoogleCalendarInfo(
    val id: String,
    val name: String,
    val primary: Boolean = false,
    val writable: Boolean = true,
)

/** Список календарей Google и создание нового (`calendarList`, `calendars.insert`). */
interface GoogleCalendars {
    suspend fun list(): List<GoogleCalendarInfo>

    suspend fun create(name: String): GoogleCalendarInfo
}

/**
 * Планировщик фоновой синхронизации (WorkManager, BGTaskScheduler). Экраны его не вызывают:
 * они меняют только настройки ([SyncSettings.withInterval], [SyncSettings.afterManualApply]), а
 * платформа сама перепланирует фон при каждой смене [SyncSettings.backgroundIntervalMinutes]
 * (на Android — `AppGraph.start`). Первый фоновый запуск — через период, а не сразу (ADR 0003).
 * Экрану состояния нужен только [nextRun].
 */
fun interface BackgroundScheduler {
    /** Когда ожидается следующий фоновый запуск; `null` — неизвестно или фон выключен. */
    suspend fun nextRun(): Instant?

    companion object {
        val None: BackgroundScheduler = BackgroundScheduler { null }
    }
}

/**
 * Итог последнего запуска для экрана состояния. Пишут его оба запуска: ручной (экран
 * синхронизации) и фоновый (обработчик платформы, см. [LastRun.background]).
 *
 * @property unavailable системы, которые не ответили (например, Lotus без сети).
 * @property failure запуск не выполнен: текст ошибки как есть.
 * @property dominoUnavailable Domino не ответил в этом запуске (сеть, таймаут, 5xx), остальные
 *   системы синхронизированы: текст ошибки как есть ([RunReport.dominoUnavailable]).
 */
data class LastRun(
    val finishedAt: Instant,
    val background: Boolean,
    val totals: RouteCounts = RouteCounts(),
    val errors: Int = 0,
    val openQuestions: Int = 0,
    val dominoDisabledNow: Boolean = false,
    val unavailable: Set<System> = emptySet(),
    val failure: String? = null,
    val dominoUnavailable: String? = null,
) {
    companion object {
        /** Ручной запуск выполнен; [openQuestions] — вопросы, оставленные без ответа. */
        fun manual(report: RunReport, at: Instant, openQuestions: Int): LastRun = LastRun(
            finishedAt = at,
            background = false,
            totals = report.totals,
            errors = report.errors.size,
            openQuestions = openQuestions,
            dominoDisabledNow = report.dominoDisabledNow,
            unavailable = report.unavailableSystems(),
            dominoUnavailable = report.dominoUnavailable,
        )

        /** Фоновый запуск выполнен. */
        fun background(report: BackgroundReport, at: Instant): LastRun = LastRun(
            finishedAt = at,
            background = true,
            totals = report.run.totals,
            errors = report.run.errors.size,
            openQuestions = report.openQuestions,
            dominoDisabledNow = report.run.dominoDisabledNow,
            unavailable = report.run.unavailableSystems(),
            dominoUnavailable = report.run.dominoUnavailable,
        )

        /** Запуск не выполнен из-за [error]; система берётся из типа исключения адаптера. */
        fun failed(error: Throwable, at: Instant, background: Boolean): LastRun = LastRun(
            finishedAt = at,
            background = background,
            unavailable = setOfNotNull(systemOf(error)),
            failure = error.message ?: error.toString(),
        )
    }
}

private fun RunReport.unavailableSystems(): Set<System> =
    if (dominoUnavailable != null) setOf(System.DOMINO) else emptySet()

/** Какая система бросила исключение: по типу исключения адаптера; `null` — неизвестно. */
fun systemOf(error: Throwable): System? = when (error) {
    is DominoException -> System.DOMINO
    is GoogleApiException -> System.GOOGLE
    is GraphException, is TeamsAuthException -> System.TEAMS
    else -> null
}

/** Где хранится итог последнего запуска (на Android — DataStore). */
interface RunHistory {
    suspend fun last(): LastRun?

    suspend fun record(run: LastRun)
}

/** История в памяти: для тестов и демо. */
class InMemoryRunHistory(private var last: LastRun? = null) : RunHistory {
    override suspend fun last(): LastRun? = last

    override suspend fun record(run: LastRun) {
        last = run
    }
}

/**
 * Что система разрешает приложению в фоне и с уведомлениями (раздел «Фон и уведомления»).
 *
 * @property backgroundUnrestricted оптимизация батареи для приложения выключена: система не
 *   откладывает и не усыпляет фоновую синхронизацию (на Android — `isIgnoringBatteryOptimizations`).
 * @property backgroundRestricted работа в фоне запрещена в настройках батареи приложения
 *   (на Android — `ActivityManager.isBackgroundRestricted`).
 * @property notificationsEnabled уведомления приложения разрешены.
 * @property canRequestNotifications разрешение на уведомления ещё можно запросить диалогом
 *   системы; иначе включить их можно только в системных настройках.
 * @property summaryChannelEnabled канал «Итог фоновой синхронизации» не выключен в системных
 *   настройках (на Android — важность канала не `IMPORTANCE_NONE`).
 */
data class SystemStatus(
    val backgroundUnrestricted: Boolean,
    val backgroundRestricted: Boolean = false,
    val notificationsEnabled: Boolean,
    val canRequestNotifications: Boolean = false,
    val summaryChannelEnabled: Boolean = true,
) {
    /** Фон работает без ограничений: можно не звать пользователя в настройки батареи. */
    val backgroundOk: Boolean get() = backgroundUnrestricted && !backgroundRestricted

    /** Уведомление с итогом фоновой синхронизации может появиться. */
    val summaryVisible: Boolean get() = notificationsEnabled && summaryChannelEnabled
}

/**
 * Системные разрешения: работа в фоне и уведомления. Реализация платформенная (androidApp:
 * `AndroidSystemControls`); `open*` уводят на системный экран, после возврата экран настроек
 * перечитывает [status] по сигналу [resumes].
 */
interface SystemControls {
    /** Телефон Samsung: показать подсказку про «Никогда не спящие» приложения. */
    val isSamsung: Boolean get() = false

    /** Приложение снова на экране (Android: `onResume`), например после системных настроек. */
    val resumes: Flow<Unit> get() = emptyFlow()

    /** Текущее состояние; `null` — платформа его не сообщает (раздел показывает только переключатель). */
    suspend fun status(): SystemStatus?

    /** Разрешить работу в фоне: запрос снятия оптимизации батареи или настройки приложения. */
    fun openBackgroundSettings()

    /** Системные настройки уведомлений приложения. */
    fun openNotificationSettings()

    /** Системные настройки канала «Итог фоновой синхронизации». */
    fun openSummaryChannelSettings()

    /** Запросить разрешение на уведомления; возвращается после ответа пользователя. */
    suspend fun requestNotificationPermission()

    companion object {
        /** Платформа без системных разрешений: состояние неизвестно, кнопки ничего не делают. */
        val None: SystemControls = object : SystemControls {
            override suspend fun status(): SystemStatus? = null

            override fun openBackgroundSettings() = Unit

            override fun openNotificationSettings() = Unit

            override fun openSummaryChannelSettings() = Unit

            override suspend fun requestNotificationPermission() = Unit
        }
    }
}

/**
 * Системные разрешения в памяти: для тестов и демо. Кнопки записывают вызовы в [opened];
 * [resume] имитирует возврат из системных настроек.
 */
class InMemorySystemControls(
    var current: SystemStatus = SystemStatus(backgroundUnrestricted = false, notificationsEnabled = false, canRequestNotifications = true),
    override val isSamsung: Boolean = false,
) : SystemControls {
    val opened: MutableList<String> = mutableListOf()
    private val resumed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val resumes: Flow<Unit> get() = resumed

    /** Пользователь вернулся в приложение. */
    fun resume() {
        resumed.tryEmit(Unit)
    }

    override suspend fun status(): SystemStatus = current

    override fun openBackgroundSettings() {
        opened += "background"
    }

    override fun openNotificationSettings() {
        opened += "notifications"
    }

    override fun openSummaryChannelSettings() {
        opened += "summary"
    }

    override suspend fun requestNotificationPermission() {
        opened += "permission"
        current = current.copy(notificationsEnabled = true, canRequestNotifications = false)
    }
}

/**
 * Всё, что нужно экранам. [syncService] вызывается один раз, при первом показе [App].
 *
 * @property syncRuns запуски синхронизации — тот же экземпляр, что у фонового обработчика
 *   (один запуск за раз, приоритет фона). Вызывается один раз, вместе с [syncService].
 */
class AppDependencies(
    val settings: SettingsStore,
    val secrets: SecretStore,
    val syncService: () -> SyncService,
    val accounts: AccountActions,
    val checks: ConnectionChecks,
    val googleCalendars: GoogleCalendars,
    val history: RunHistory,
    val scheduler: BackgroundScheduler = BackgroundScheduler.None,
    val systemControls: SystemControls = SystemControls.None,
    val clock: Clock = Clock.System,
    val syncRuns: () -> SyncRuns,
    val updates: UpdateCheck? = null,
)
