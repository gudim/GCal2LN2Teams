package app.gcaltolotus.ui

import app.gcaltolotus.app.PRIMARY_CALENDAR_ID
import app.gcaltolotus.app.SettingsStore
import app.gcaltolotus.app.SyncService
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Итог действия на экране выбора календаря. */
sealed interface CalendarNotice {
    data class Created(val name: String) : CalendarNotice

    /** Календарь сменён; [deleted] копий удалено из прежнего, [errors] — не удалось удалить. */
    data class Switched(val name: String, val deleted: Int, val errors: List<String>) : CalendarNotice

    data object NameRequired : CalendarNotice

    data class Failed(val message: String) : CalendarNotice
}

/** Смена календаря ждёт ответа: удалять ли [oldCopies] копий из прежнего календаря. */
data class PendingSwitch(val target: GoogleCalendarInfo, val oldCopies: Int)

/**
 * @property current id календаря, куда пишутся копии.
 * @property loadError список не загрузился (например, нет входа в Google).
 */
data class GoogleCalendarState(
    val loading: Boolean = false,
    val calendars: List<GoogleCalendarInfo> = emptyList(),
    val current: String = PRIMARY_CALENDAR_ID,
    val loadError: String? = null,
    val busy: Boolean = false,
    val pending: PendingSwitch? = null,
    val notice: CalendarNotice? = null,
) {
    /** Текущий календарь есть в списке (`primary` совпадает с основным календарём). */
    fun isCurrent(calendar: GoogleCalendarInfo): Boolean =
        calendar.id == current || (current == PRIMARY_CALENDAR_ID && calendar.primary)
}

/** Выбор календаря Google для копий: список, создание, смена с удалением старых копий. */
class GoogleCalendarModel(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val calendars: GoogleCalendars,
    private val sync: SyncService,
) {
    private val mutable = MutableStateFlow(GoogleCalendarState())
    val state: StateFlow<GoogleCalendarState> = mutable.asStateFlow()

    fun load(): Job {
        mutable.update { it.copy(loading = true, loadError = null) }
        return scope.launch {
            val current = settings.load().googleCalendarId
            try {
                val list = calendars.list()
                mutable.update { it.copy(loading = false, calendars = list, current = current) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(loading = false, current = current, loadError = e.message ?: e.toString()) }
            }
        }
    }

    /** Создать календарь и сразу перейти на него (с вопросом о старых копиях, если они есть). */
    fun create(name: String): Job? {
        val summary = name.trim()
        if (summary.isEmpty()) {
            mutable.update { it.copy(notice = CalendarNotice.NameRequired) }
            return null
        }
        return work {
            val created = calendars.create(summary)
            mutable.update { it.copy(calendars = it.calendars + created, notice = CalendarNotice.Created(created.name)) }
            requestSwitch(created)
        }
    }

    /** Выбрать календарь: если в текущем есть наши копии, сначала спросить, удалять ли их. */
    fun select(calendar: GoogleCalendarInfo): Job? {
        if (mutable.value.isCurrent(calendar) || !calendar.writable) return null
        return work { requestSwitch(calendar) }
    }

    fun confirmSwitch(deleteOld: Boolean): Job? {
        val pending = mutable.value.pending ?: return null
        mutable.update { it.copy(pending = null) }
        return work { switch(pending.target, deleteOld) }
    }

    fun cancelSwitch() = mutable.update { it.copy(pending = null) }

    fun dismissNotice() = mutable.update { it.copy(notice = null) }

    private suspend fun requestSwitch(target: GoogleCalendarInfo) {
        val preview = sync.googleSwitchPreview(target.id)
        if (preview.oldCopies == 0) {
            switch(target, deleteOld = false)
        } else {
            mutable.update { it.copy(pending = PendingSwitch(target, preview.oldCopies)) }
        }
    }

    private suspend fun switch(target: GoogleCalendarInfo, deleteOld: Boolean) {
        val result = sync.switchGoogleCalendar(target.id, deleteOld)
        mutable.update {
            it.copy(current = target.id, notice = CalendarNotice.Switched(target.name, result.deleted, result.errors))
        }
    }

    private fun work(action: suspend () -> Unit): Job {
        mutable.update { it.copy(busy = true, notice = null) }
        return scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(notice = CalendarNotice.Failed(e.message ?: e.toString())) }
            }
            mutable.update { it.copy(busy = false) }
        }
    }
}
