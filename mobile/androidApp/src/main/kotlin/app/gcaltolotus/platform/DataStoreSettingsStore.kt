package app.gcaltolotus.platform

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.gcaltolotus.app.AttendeeAnswer
import app.gcaltolotus.app.Route
import app.gcaltolotus.app.SettingsStore
import app.gcaltolotus.app.SyncSettings
import app.gcaltolotus.sync.DIRECTIONS
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Несекретные настройки ([SyncSettings]) в Jetpack DataStore (Preferences).
 * Секреты здесь не хранятся: они в [KeystoreSecretStore].
 */
class DataStoreSettingsStore(private val dataStore: DataStore<Preferences>) : SettingsStore {
    /** Настройки и все их изменения (например, чтобы перепланировать фоновую синхронизацию). */
    val settings: Flow<SyncSettings> = dataStore.data.map(SettingsCodec::read)

    override suspend fun load(): SyncSettings = settings.first()

    override suspend fun update(change: (SyncSettings) -> SyncSettings): SyncSettings {
        lateinit var result: SyncSettings
        dataStore.edit { prefs ->
            result = change(SettingsCodec.read(prefs))
            SettingsCodec.write(result, prefs)
        }
        return result
    }
}

/**
 * Перевод [SyncSettings] в ключи Preferences и обратно. Отсутствующий или негодный ключ даёт
 * значение по умолчанию, чтобы испорченный файл не мешал запуску приложения.
 */
internal object SettingsCodec {
    private val DOMINO_URL = stringPreferencesKey("domino_url")
    private val DOMINO_USER = stringPreferencesKey("domino_user")
    private val GOOGLE_CALENDAR_ID = stringPreferencesKey("google_calendar_id")
    private val DIRECTION = stringPreferencesKey("direction")

    /** Выбранные маршруты (`google>domino`); ключа нет — все маршруты направления. */
    private val ROUTES = stringSetPreferencesKey("routes")
    private val DAYS_BACK = intPreferencesKey("days_back")
    private val DAYS_FORWARD = intPreferencesKey("days_forward")
    private val BACKGROUND_INTERVAL = intPreferencesKey("background_interval_minutes")

    /**
     * [SyncSettings.backgroundConfigured]. Ключа нет, а период есть — установка из версии без
     * этого ключа: период там уже действовал (и был применён ручной запуск), он сохраняется.
     */
    private val BACKGROUND_CONFIGURED = booleanPreferencesKey("background_configured")
    private val DOMINO_DISABLED = booleanPreferencesKey("domino_disabled")

    /** ISO-8601 (`2026-11-14T10:00:00Z`). */
    private val LAST_DOMINO_SUCCESS = stringPreferencesKey("last_domino_success")

    /** Ключа нет (установка из прежней версии) — итог фоновой синхронизации не показывается. */
    private val NOTIFY_BACKGROUND_SUMMARY = booleanPreferencesKey("notify_background_summary")

    private val CONFIRM_EACH_ACTION = booleanPreferencesKey("confirm_each_action")

    /**
     * [SyncSettings.manualApplied]. Ключа нет — установка из версии до 0.1.2: ручное применение
     * было, если фон уже настроен (его включает первое применение).
     */
    private val MANUAL_APPLIED = booleanPreferencesKey("manual_applied")

    /** [SyncSettings.attendeeAnswers]: строки `<1|0>␟<ключ действия>␟<название>`. */
    private val ATTENDEE_ANSWERS = stringSetPreferencesKey("attendee_answers")
    private const val SEP = '\u001F'

    internal fun encodeAnswers(answers: Map<String, AttendeeAnswer>): Set<String> =
        answers.mapTo(LinkedHashSet()) { (key, a) -> "${if (a.allow) 1 else 0}$SEP$key$SEP${a.title}" }

    internal fun decodeAnswers(rows: Set<String>): Map<String, AttendeeAnswer> = rows.mapNotNull { row ->
        val parts = row.split(SEP, limit = 3)
        if (parts.size != 3 || parts[0] !in setOf("0", "1")) null else parts[1] to AttendeeAnswer(parts[2], parts[0] == "1")
    }.toMap()

    fun read(p: Preferences): SyncSettings {
        val d = SyncSettings()
        return SyncSettings(
            dominoUrl = p[DOMINO_URL] ?: d.dominoUrl,
            dominoUser = p[DOMINO_USER] ?: d.dominoUser,
            googleCalendarId = p[GOOGLE_CALENDAR_ID]?.takeIf { it.isNotBlank() } ?: d.googleCalendarId,
            direction = p[DIRECTION]?.takeIf { it in DIRECTIONS } ?: d.direction,
            routes = p[ROUTES]?.mapNotNullTo(LinkedHashSet()) { runCatching { Route.parse(it) }.getOrNull() },
            daysBack = p[DAYS_BACK]?.takeIf { it >= 0 } ?: d.daysBack,
            daysForward = p[DAYS_FORWARD]?.takeIf { it >= 0 } ?: d.daysForward,
            backgroundIntervalMinutes = p[BACKGROUND_INTERVAL]?.takeIf { it >= 0 } ?: d.backgroundIntervalMinutes,
            backgroundConfigured = p[BACKGROUND_CONFIGURED] ?: (BACKGROUND_INTERVAL in p),
            dominoDisabled = p[DOMINO_DISABLED] ?: d.dominoDisabled,
            lastDominoSuccess = p[LAST_DOMINO_SUCCESS]?.let { runCatching { Instant.parse(it) }.getOrNull() },
            notifyBackgroundSummary = p[NOTIFY_BACKGROUND_SUMMARY] ?: d.notifyBackgroundSummary,
            confirmEachAction = p[CONFIRM_EACH_ACTION] ?: d.confirmEachAction,
            manualApplied = p[MANUAL_APPLIED] ?: p[BACKGROUND_CONFIGURED] ?: (BACKGROUND_INTERVAL in p),
            attendeeAnswers = p[ATTENDEE_ANSWERS]?.let(::decodeAnswers) ?: d.attendeeAnswers,
        )
    }

    fun write(s: SyncSettings, p: MutablePreferences) {
        p[DOMINO_URL] = s.dominoUrl
        p[DOMINO_USER] = s.dominoUser
        p[GOOGLE_CALENDAR_ID] = s.googleCalendarId
        p[DIRECTION] = s.direction
        val routes = s.routes
        if (routes == null) p.remove(ROUTES) else p[ROUTES] = routes.mapTo(LinkedHashSet()) { it.toString() }
        p[DAYS_BACK] = s.daysBack
        p[DAYS_FORWARD] = s.daysForward
        p[BACKGROUND_INTERVAL] = s.backgroundIntervalMinutes
        p[BACKGROUND_CONFIGURED] = s.backgroundConfigured
        p[DOMINO_DISABLED] = s.dominoDisabled
        val last = s.lastDominoSuccess
        if (last == null) p.remove(LAST_DOMINO_SUCCESS) else p[LAST_DOMINO_SUCCESS] = last.toString()
        p[NOTIFY_BACKGROUND_SUMMARY] = s.notifyBackgroundSummary
        p[CONFIRM_EACH_ACTION] = s.confirmEachAction
        p[MANUAL_APPLIED] = s.manualApplied
        p[ATTENDEE_ANSWERS] = encodeAnswers(s.attendeeAnswers)
    }
}
