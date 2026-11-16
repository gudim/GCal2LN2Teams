package app.gcaltolotus.app

import app.gcaltolotus.adapters.teams.TeamsTokenStore
import app.gcaltolotus.adapters.teams.TeamsTokens
import app.gcaltolotus.sync.DIRECTIONS
import app.gcaltolotus.sync.System
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Основной календарь Google. */
const val PRIMARY_CALENDAR_ID: String = "primary"

/**
 * Маршрут: копирование из [origin] в [target] (`"domino>google"` в веб-версии).
 * Пара систем синхронизируется в обе стороны двумя маршрутами.
 */
data class Route(val origin: System, val target: System) {
    val pair: Pair<System, System> get() = origin to target

    /** Текст как в веб-версии: `google>domino`. */
    override fun toString(): String = "${origin.value}>${target.value}"

    companion object {
        /** Разобрать `google>domino`; чужой текст — [IllegalArgumentException]. */
        fun parse(text: String): Route {
            val parts = text.split(">")
            require(parts.size == 2) { "Неизвестный маршрут: '$text'" }
            return Route(System.fromValue(parts[0]), System.fromValue(parts[1]))
        }
    }
}

/**
 * Несекретные настройки (на Android — DataStore). Порт строк `Config` веб-версии плюс то,
 * что веб-версия держала в браузере (маршруты, окно).
 *
 * @property direction ключ [DIRECTIONS]: какие пары систем синхронизируются.
 * @property routes маршруты, выбранные пользователем; `null` — все маршруты пар [direction]
 *   (веб-версия /v2 шлёт `direction = "all"` и свои маршруты).
 * @property daysBack окно синхронизации: дней назад от текущего момента.
 * @property daysForward окно синхронизации: дней вперёд.
 * @property backgroundIntervalMinutes период фоновой синхронизации; 0 — выключена.
 *   Планирует её платформа (WorkManager, BGTaskScheduler). На новой установке выключена до
 *   первого ручного применения (ADR 0003), см. [afterManualApply].
 * @property backgroundConfigured период уже выбран: пользователем в настройках или
 *   автоматически после первого ручного применения. Пока `false`, фон включится сам
 *   ([DEFAULT_BACKGROUND_INTERVAL_MINUTES]) после первого ручного применения.
 * @property dominoDisabled Lotus выключен после 401 от Domino (защита от блокировки учётной
 *   записи, ADR 0002). Снимается только вводом пароля ([SyncService.saveDominoCredentials]).
 * @property lastDominoSuccess когда Domino последний раз прочитан без ошибок (в любом запуске);
 *   `null` — ещё ни разу. По нему фоновый обработчик решает, пора ли сообщить, что Lotus
 *   недоступен ([shouldNotifyDominoUnreachable]).
 * @property notifyBackgroundSummary после каждой фоновой синхронизации показывать тихое
 *   уведомление с итогом («Создано 3, обновлено 1»). По умолчанию выключено.
 * @property confirmEachAction ручная синхронизация всегда показывает предпросмотр всех действий
 *   и ждёт «Применить» (как до ADR 0004). По умолчанию выключено: см. [autoApply].
 * @property manualApplied ручное применение уже было хотя бы раз ([afterManualApply]).
 * @property attendeeAnswers запомненные ответы на вопрос «правка встречи с участниками» по ключу
 *   действия `update:<система>:<id>` (ADR 0004).
 */
data class SyncSettings(
    val dominoUrl: String = "",
    val dominoUser: String = "",
    val googleCalendarId: String = PRIMARY_CALENDAR_ID,
    val direction: String = "all",
    val routes: Set<Route>? = null,
    val daysBack: Int = 1,
    val daysForward: Int = 30,
    val backgroundIntervalMinutes: Int = 0,
    val backgroundConfigured: Boolean = false,
    val dominoDisabled: Boolean = false,
    val lastDominoSuccess: Instant? = null,
    val notifyBackgroundSummary: Boolean = false,
    val confirmEachAction: Boolean = false,
    val manualApplied: Boolean = false,
    val attendeeAnswers: Map<String, AttendeeAnswer> = emptyMap(),
) {
    init {
        require(direction in DIRECTIONS) { "Неизвестное направление: '$direction'" }
        require(daysBack >= 0 && daysForward >= 0) { "Окно синхронизации не может быть отрицательным" }
    }

    /** Маршруты запуска: оба направления каждой пары [direction], суженные до [routes]. */
    fun activeRoutes(): List<Route> {
        val all = DIRECTIONS.getValue(direction).flatMap { pair ->
            val (a, b) = System.ORDER.filter { it in pair }
            listOf(Route(a, b), Route(b, a))
        }
        return if (routes == null) all else all.filter { it in routes }
    }

    /** Системы, нужные для [activeRoutes]. */
    /**
     * Ручная синхронизация применяет всё, кроме вопросов, сразу, а показывает только вопросы
     * (ADR 0004). Только после первого ручного применения: на нём пользователь отказывается от
     * лишних копий (ADR 0003).
     */
    val autoApply: Boolean get() = manualApplied && !confirmEachAction

    fun activeSystems(): Set<System> = activeRoutes().flatMap { listOf(it.origin, it.target) }.toSet()

    /**
     * Настройки после успешного ручного применения: при первом включить фон с периодом по
     * умолчанию. Выбор пользователя (в том числе «выключена») не трогается.
     */
    fun afterManualApply(): SyncSettings =
        if (backgroundConfigured) copy(manualApplied = true)
        else copy(
            backgroundIntervalMinutes = DEFAULT_BACKGROUND_INTERVAL_MINUTES,
            backgroundConfigured = true,
            manualApplied = true,
        )

    /**
     * Пользователь выбрал период фона ([BACKGROUND_INTERVALS]; 0 — выключить). Выбор считается
     * явным: первое ручное применение его уже не меняет (ADR 0003).
     */
    fun withInterval(minutes: Int): SyncSettings {
        require(minutes in BACKGROUND_INTERVALS) { "Неизвестный период: $minutes" }
        return copy(backgroundIntervalMinutes = minutes, backgroundConfigured = true)
    }
}

/**
 * Ответ на вопрос «правка встречи с участниками» (ADR 0004): [allow] — обновлять встречу, хотя
 * участники получат письмо. Действует, пока у встречи название [title] и правка его не меняет.
 */
data class AttendeeAnswer(val title: String, val allow: Boolean)

/** Периоды фоновой синхронизации в минутах, из которых выбирает пользователь; 0 — выключена. */
val BACKGROUND_INTERVALS: List<Int> = listOf(15, 60, 240, 0)

/** Период фоновой синхронизации, который включается после первого ручного применения. */
const val DEFAULT_BACKGROUND_INTERVAL_MINUTES: Int = 60

/** Хранилище несекретных настроек (DataStore на Android, позже NSUserDefaults). */
interface SettingsStore {
    suspend fun load(): SyncSettings

    /**
     * Изменить настройки атомарно: прочитать, применить [change], записать; возвращает новые.
     * Фон и экран пишут одновременно (например, `dominoDisabled` и маршрут), поэтому чтение и
     * запись — один шаг: параллельное изменение другого поля не теряется.
     */
    suspend fun update(change: (SyncSettings) -> SyncSettings): SyncSettings
}

/** Настройки в памяти: для тестов и демо. */
class InMemorySettingsStore(private var settings: SyncSettings = SyncSettings()) : SettingsStore {
    private val lock = Mutex()

    override suspend fun load(): SyncSettings = lock.withLock { settings }

    override suspend fun update(change: (SyncSettings) -> SyncSettings): SyncSettings =
        lock.withLock { change(settings).also { settings = it } }

    /** Заменить настройки целиком (подготовка теста). */
    suspend fun save(settings: SyncSettings) {
        update { settings }
    }
}

/**
 * Токены Google. Как их получать и обновлять, решает платформенный слой входа (клиент Android,
 * ADR 0002); здесь они только хранятся. Наличие токенов значит «вход в Google выполнен».
 */
data class GoogleTokens(val accessToken: String, val refreshToken: String?, val expiresAt: Instant?) {
    override fun toString(): String =
        "GoogleTokens(accessToken=***, refreshToken=${if (refreshToken == null) "null" else "***"}, " +
            "expiresAt=$expiresAt)"
}

/**
 * Секреты (на Android — Keystore/EncryptedSharedPreferences, на iOS — Keychain).
 * `null` — секрета нет; запись `null` удаляет секрет.
 */
interface SecretStore {
    suspend fun dominoPassword(): String?

    suspend fun setDominoPassword(password: String?)

    suspend fun teamsTokens(): TeamsTokens?

    suspend fun setTeamsTokens(tokens: TeamsTokens?)

    suspend fun googleTokens(): GoogleTokens?

    suspend fun setGoogleTokens(tokens: GoogleTokens?)
}

/** Токены Teams из [SecretStore] для `TeamsAuth`. */
fun SecretStore.teamsTokenStore(): TeamsTokenStore = object : TeamsTokenStore {
    override suspend fun load(): TeamsTokens? = teamsTokens()

    override suspend fun save(tokens: TeamsTokens) = setTeamsTokens(tokens)

    override suspend fun clear() = setTeamsTokens(null)
}

/** Секреты в памяти: для тестов. */
class InMemorySecretStore : SecretStore {
    private var domino: String? = null
    private var teams: TeamsTokens? = null
    private var google: GoogleTokens? = null

    override suspend fun dominoPassword(): String? = domino

    override suspend fun setDominoPassword(password: String?) {
        domino = password
    }

    override suspend fun teamsTokens(): TeamsTokens? = teams

    override suspend fun setTeamsTokens(tokens: TeamsTokens?) {
        teams = tokens
    }

    override suspend fun googleTokens(): GoogleTokens? = google

    override suspend fun setGoogleTokens(tokens: GoogleTokens?) {
        google = tokens
    }
}
