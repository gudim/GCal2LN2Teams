package app.gcaltolotus.ui

import app.gcaltolotus.app.AppRelease
import app.gcaltolotus.app.BACKGROUND_INTERVALS
import app.gcaltolotus.app.SettingsStore
import app.gcaltolotus.app.SyncSettings
import app.gcaltolotus.app.UpdateCheck
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Наибольшее окно синхронизации в каждую сторону, дней. */
const val MAX_WINDOW_DAYS: Int = 365

/**
 * @property daysBack поля окна в том виде, в каком их ввёл пользователь; сохраняются, когда
 *   в поле целое число от 0 до [MAX_WINDOW_DAYS].
 * @property system разрешения системы (фон, уведомления); `null` — платформа их не сообщает.
 * @property isSamsung показать подсказку про «Никогда не спящие» приложения Samsung.
 * @property appVersion установленная версия; `null` — платформа не сообщает.
 * @property update вышла версия новее установленной (по последней еженедельной проверке).
 * @property notifySummary пользователь включил уведомление с итогом фоновой синхронизации
 *   (сохранённая настройка; появится ли оно, решает ещё и система, см. [summaryShown]).
 * @property confirmEach [SyncSettings.confirmEachAction].
 * @property attendeeAnswers сколько ответов о встречах с участниками запомнено.
 */
data class SettingsState(
    val daysBack: String = SyncSettings().daysBack.toString(),
    val daysForward: String = SyncSettings().daysForward.toString(),
    val intervalMinutes: Int = SyncSettings().backgroundIntervalMinutes,
    val system: SystemStatus? = null,
    val isSamsung: Boolean = false,
    val notifySummary: Boolean = false,
    val appVersion: String? = null,
    val update: AppRelease? = null,
    val confirmEach: Boolean = false,
    val attendeeAnswers: Int = 0,
) {
    val daysBackValid: Boolean get() = parseDays(daysBack) != null
    val daysForwardValid: Boolean get() = parseDays(daysForward) != null

    /** Итог выключен в системе: уведомления приложения или его канал выключены. */
    val summaryBlocked: Boolean get() = system?.summaryVisible == false

    /** Положение переключателя итога: включён пользователем и не выключен в системе. */
    val summaryShown: Boolean get() = notifySummary && !summaryBlocked
}

internal fun parseDays(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 0..MAX_WINDOW_DAYS }

/**
 * Настройки: окно синхронизации, период фоновой синхронизации, фон и уведомления, версия.
 */
class SettingsModel(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val controls: SystemControls = SystemControls.None,
    private val updates: UpdateCheck? = null,
) {
    private val mutable = MutableStateFlow(SettingsState(isSamsung = controls.isSamsung, appVersion = updates?.currentVersion))
    val state: StateFlow<SettingsState> = mutable.asStateFlow()

    fun load(): Job = scope.launch {
        val s = settings.load()
        mutable.update {
            it.copy(
                daysBack = s.daysBack.toString(),
                daysForward = s.daysForward.toString(),
                intervalMinutes = s.backgroundIntervalMinutes,
                notifySummary = s.notifyBackgroundSummary,
                confirmEach = s.confirmEachAction,
                attendeeAnswers = s.attendeeAnswers.size,
            )
        }
        readSystem()
        val update = try {
            updates?.available()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        mutable.update { it.copy(update = update) }
    }

    /** Перечитать разрешения системы (например, после возврата из её настроек). */
    fun refreshSystem(): Job = scope.launch { readSystem() }

    /** Перечитывать разрешения при каждом возврате в приложение; работает, пока экран показан. */
    suspend fun followSystemChanges() {
        controls.resumes.collect { readSystem() }
    }

    private suspend fun readSystem() {
        val status = try {
            controls.status()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        mutable.update { it.copy(system = status) }
    }

    /** «Разрешить работу в фоне»: системный запрос или настройки батареи приложения. */
    fun allowBackground() = controls.openBackgroundSettings()

    /** «Настроить уведомления»: системные настройки уведомлений приложения. */
    fun openNotificationSettings() = controls.openNotificationSettings()

    /**
     * Включить уведомления: запросить разрешение, пока система ещё показывает диалог, иначе
     * открыть системные настройки уведомлений. Возвращает задачу запроса (или `null`).
     */
    fun enableNotifications(): Job? {
        if (mutable.value.system?.canRequestNotifications == true) {
            return scope.launch {
                controls.requestNotificationPermission()
                readSystem()
            }
        }
        controls.openNotificationSettings()
        return null
    }

    /**
     * Переключатель «Сообщать итог каждой фоновой синхронизации». Выбор сохраняется; если итог
     * выключен в системе, включение ведёт туда, где его включить: к разрешению или настройкам
     * уведомлений приложения, либо к настройкам канала итога.
     */
    fun setNotifySummary(on: Boolean): Job {
        mutable.update { it.copy(notifySummary = on) }
        val system = mutable.value.system
        if (on && system != null) {
            when {
                !system.notificationsEnabled -> enableNotifications()
                !system.summaryChannelEnabled -> controls.openSummaryChannelSettings()
            }
        }
        return scope.launch { settings.update { it.copy(notifyBackgroundSummary = on) } }
    }

    /** Подтверждать каждое действие ручной синхронизации (иначе — автоприменение, ADR 0004). */
    fun setConfirmEach(on: Boolean): Job {
        mutable.update { it.copy(confirmEach = on) }
        return scope.launch { settings.update { it.copy(confirmEachAction = on) } }
    }

    /** Забыть ответы о встречах с участниками: вопросы о них зададутся снова. */
    fun forgetAttendeeAnswers(): Job {
        mutable.update { it.copy(attendeeAnswers = 0) }
        return scope.launch { settings.update { it.copy(attendeeAnswers = emptyMap()) } }
    }

    fun editDaysBack(text: String): Job? {
        mutable.update { it.copy(daysBack = text) }
        val days = parseDays(text) ?: return null
        return scope.launch { settings.update { it.copy(daysBack = days) } }
    }

    fun editDaysForward(text: String): Job? {
        mutable.update { it.copy(daysForward = text) }
        val days = parseDays(text) ?: return null
        return scope.launch { settings.update { it.copy(daysForward = days) } }
    }

    fun setInterval(minutes: Int): Job {
        require(minutes in BACKGROUND_INTERVALS) { "Неизвестный период: $minutes" }
        mutable.update { it.copy(intervalMinutes = minutes) }
        return scope.launch {
            settings.update { it.withInterval(minutes) }
        }
    }
}
