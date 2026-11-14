package app.gcaltolotus.app

import app.gcaltolotus.adapters.domino.DominoCalendar
import app.gcaltolotus.adapters.google.GoogleAccessToken
import app.gcaltolotus.adapters.google.GoogleCalendarAdapter
import app.gcaltolotus.adapters.teams.TeamsAuth
import app.gcaltolotus.adapters.teams.TeamsCalendar
import app.gcaltolotus.adapters.teams.TeamsOAuthConfig
import app.gcaltolotus.sync.Calendar
import app.gcaltolotus.sync.System
import io.ktor.client.HttpClient

/** Почему система не участвует в запуске. */
enum class SkipReason {
    /** Нет входа или не заполнены настройки. */
    NOT_CONFIGURED,

    /** Lotus выключен после 401 до повторного ввода пароля. */
    DOMINO_DISABLED,
}

/** Система, пропущенная в запуске, и причина. */
data class SkippedSystem(val system: System, val reason: SkipReason)

/** Календари для запуска (в каноническом порядке систем) и пропущенные системы. */
data class OpenedCalendars(val calendars: Map<System, Calendar>, val skipped: List<SkippedSystem>)

/** Откуда оркестрация берёт календари. В приложении — [AdapterCalendarSource], в тестах — подделка. */
interface CalendarSource {
    /**
     * Календари систем [systems] по настройкам [settings] (календарь Google —
     * [SyncSettings.googleCalendarId]). Система без входа или настроек и выключенный Lotus
     * пропускаются и попадают в [OpenedCalendars.skipped]. В сеть не ходит.
     */
    suspend fun open(settings: SyncSettings, systems: Set<System>): OpenedCalendars
}

/**
 * Зависимости адаптеров, которые даёт платформа.
 *
 * @param http клиент Ktor для Google и Teams.
 * @param dominoHttp клиент для Domino (свои TLS-настройки, без повторов запросов).
 * @param teamsConfig регистрация приложения в Azure: на телефоне публичный клиент с PKCE,
 *   без секрета (ADR 0002); `null` — Teams недоступен.
 * @param googleAccessToken слой входа Google: отдаёт access-токен, обновляя его при
 *   `forceRefresh`; `null` — Google недоступен.
 * @param log журнал адаптеров (по-русски).
 */
class CalendarDeps(
    val http: HttpClient,
    val dominoHttp: HttpClient = http,
    val teamsConfig: TeamsOAuthConfig? = null,
    val googleAccessToken: GoogleAccessToken? = null,
    val log: (String) -> Unit = {},
)

/**
 * Календари из настроек и секретов — то, что `/api/sync` делал с `GoogleCalendarAdapter`,
 * `DominoRestAdapter` и `MSTeamsCalendarAdapter`. Отличие от веб-версии: система без
 * настроек не ошибка всего запуска, а пропуск.
 *
 * - Google: есть [GoogleTokens] в [secrets] и [CalendarDeps.googleAccessToken];
 * - Domino: заполнены адрес, пользователь и пароль и Lotus не выключен после 401;
 * - Teams: есть [CalendarDeps.teamsConfig] и токены Teams в [secrets].
 */
class AdapterCalendarSource(
    private val secrets: SecretStore,
    private val deps: CalendarDeps,
) : CalendarSource {
    override suspend fun open(settings: SyncSettings, systems: Set<System>): OpenedCalendars {
        val calendars = LinkedHashMap<System, Calendar>()
        val skipped = mutableListOf<SkippedSystem>()
        for (system in System.ORDER.filter { it in systems }) {
            if (system == System.DOMINO && settings.dominoDisabled) {
                skipped += SkippedSystem(system, SkipReason.DOMINO_DISABLED)
                continue
            }
            val calendar = when (system) {
                System.GOOGLE -> google(settings.googleCalendarId)
                System.DOMINO -> domino(settings)
                System.TEAMS -> teams()
            }
            if (calendar == null) skipped += SkippedSystem(system, SkipReason.NOT_CONFIGURED) else calendars[system] = calendar
        }
        return OpenedCalendars(calendars, skipped)
    }

    private suspend fun google(calendarId: String): Calendar? {
        val token = deps.googleAccessToken ?: return null
        secrets.googleTokens() ?: return null
        return GoogleCalendarAdapter(deps.http, token, calendarId, log = deps.log)
    }

    private suspend fun domino(settings: SyncSettings): Calendar? {
        if (settings.dominoUrl.isBlank() || settings.dominoUser.isBlank()) return null
        val password = secrets.dominoPassword()?.takeIf { it.isNotEmpty() } ?: return null
        return DominoCalendar(deps.dominoHttp, settings.dominoUrl, settings.dominoUser, password) { _, e ->
            deps.log("Domino: событие пропущено: ${e.message}")
        }
    }

    private suspend fun teams(): Calendar? {
        val config = deps.teamsConfig ?: return null
        secrets.teamsTokens() ?: return null
        return TeamsCalendar(deps.http, TeamsAuth(deps.http, config, secrets.teamsTokenStore()), deps.log)
    }
}
