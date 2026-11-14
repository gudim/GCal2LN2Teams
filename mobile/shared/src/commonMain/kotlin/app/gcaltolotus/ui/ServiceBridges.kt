package app.gcaltolotus.ui

import app.gcaltolotus.app.ConnectionCheck
import app.gcaltolotus.app.ConnectionChecker
import app.gcaltolotus.app.RouteCounts
import app.gcaltolotus.app.SettingsStore
import app.gcaltolotus.app.SyncService
import app.gcaltolotus.sync.System
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import app.gcaltolotus.adapters.google.GoogleCalendarInfo as ApiCalendarInfo

/*
 * Реализации интерфейсов экранов поверх общего кода (app/): они не зависят от платформы, поэтому
 * живут здесь и годятся и для Android, и для iOS. Платформенные (вход, выбор файла, хранилище
 * истории, планировщик) — в androidApp.
 */

/** Итог проверки из [ConnectionChecker] в вид для экрана. */
fun ConnectionCheck.toCheckResult(): CheckResult = when (this) {
    is ConnectionCheck.Ok -> CheckResult.Ok(details.ifBlank { null })
    is ConnectionCheck.AuthFailed -> CheckResult.AuthFailed(message)
    is ConnectionCheck.Unreachable -> CheckResult.Unreachable(message)
    is ConnectionCheck.NotConfigured -> CheckResult.NotConfigured(message)
}

/**
 * Кнопка «Проверить» через [ConnectionChecker]. Lotus проверяется с сохранёнными адресом,
 * пользователем и паролем: экран подключений сохраняет поля перед проверкой.
 */
class CheckerConnectionChecks(
    private val settings: SettingsStore,
    private val checker: () -> ConnectionChecker,
) : ConnectionChecks {
    override suspend fun check(system: System): CheckResult = when (system) {
        System.GOOGLE -> checker().checkGoogle()
        System.TEAMS -> checker().checkTeams()
        System.DOMINO -> settings.load().let { checker().checkDomino(it.dominoUrl, it.dominoUser, password = null) }
    }.toCheckResult()
}

/** Календарь Google из адаптера в вид для экрана выбора. */
fun ApiCalendarInfo.toUi(): GoogleCalendarInfo =
    GoogleCalendarInfo(id = id, name = summary.ifBlank { id }, primary = primary, writable = writable)

/** Список и создание календарей Google через [SyncService]. */
class ServiceGoogleCalendars(private val sync: () -> SyncService) : GoogleCalendars {
    override suspend fun list(): List<GoogleCalendarInfo> = sync().googleCalendars().calendars.map { it.toUi() }

    override suspend fun create(name: String): GoogleCalendarInfo = sync().createGoogleCalendar(name).toUi()
}

/**
 * [LastRun] в JSON и обратно (для хранилища истории на платформе). Негодный или устаревший
 * текст даёт `null`: экран состояния тогда покажет «ещё не запускалась», а не упадёт.
 */
object LastRunCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(run: LastRun): String = json.encodeToString(LastRunJson.serializer(), LastRunJson.of(run))

    fun decode(text: String?): LastRun? {
        if (text.isNullOrBlank()) return null
        return try {
            json.decodeFromString(LastRunJson.serializer(), text).toLastRun()
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

@Serializable
private data class LastRunJson(
    val finishedAt: String,
    val background: Boolean,
    val created: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
    val linked: Int = 0,
    val errors: Int = 0,
    val openQuestions: Int = 0,
    val dominoDisabledNow: Boolean = false,
    val unavailable: List<String> = emptyList(),
    val failure: String? = null,
    val dominoUnavailable: String? = null,
) {
    fun toLastRun(): LastRun = LastRun(
        finishedAt = Instant.parse(finishedAt),
        background = background,
        totals = RouteCounts(created, updated, deleted, linked),
        errors = errors,
        openQuestions = openQuestions,
        dominoDisabledNow = dominoDisabledNow,
        // Неизвестная система (из будущей версии) пропускается.
        unavailable = unavailable.mapNotNull { v -> System.entries.firstOrNull { it.value == v } }.toSet(),
        failure = failure,
        dominoUnavailable = dominoUnavailable,
    )

    companion object {
        fun of(run: LastRun) = LastRunJson(
            finishedAt = run.finishedAt.toString(),
            background = run.background,
            created = run.totals.created,
            updated = run.totals.updated,
            deleted = run.totals.deleted,
            linked = run.totals.linked,
            errors = run.errors,
            openQuestions = run.openQuestions,
            dominoDisabledNow = run.dominoDisabledNow,
            unavailable = System.ORDER.filter { it in run.unavailable }.map { it.value },
            failure = run.failure,
            dominoUnavailable = run.dominoUnavailable,
        )
    }
}
