package app.gcaltolotus.sync

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Окно синхронизации `[start, end)`. Порт `DateRange` из `src/sync/event.py`.
 * Границы — моменты (UTC).
 */
data class Window(val start: Instant, val end: Instant) {
    /**
     * Пересекается ли событие с окном: `event.start < end && event.end > start`.
     * Для событий на весь день границы окна берутся как даты в UTC (`end` события исключён,
     * поэтому событие, кончающееся в день начала окна, не пересекается).
     */
    fun overlaps(event: CalendarEvent): Boolean {
        val s = event.start
        val e = event.end
        return when {
            s is When.AllDay && e is When.AllDay ->
                s.date < end.toLocalDateTime(TimeZone.UTC).date &&
                    e.date > start.toLocalDateTime(TimeZone.UTC).date
            s is When.Timed && e is When.Timed -> s.instant < end && e.instant > start
            else -> error("start и end разного вида: $event")
        }
    }
}

/**
 * Календарь одной системы. Порт протокола `Calendar` из `src/sync/calendar.py`; методы
 * `suspend`, потому что адаптеры ходят в сеть.
 *
 * - [list] возвращает все события, пересекающие окно (со всеми страницами), с заполненными
 *   `description` и `modified`. Копии распознаются по скрытой метке или маркеру в названии и
 *   возвращаются с `source`.
 * - [create] создаёт событие (маркер и скрытую метку добавляет адаптер по `event.source`) и
 *   возвращает его id.
 * - [get] возвращает событие по id или `null`, если оно удалено/отменено (404, cancelled).
 * - [update] переписывает синхронизируемые поля (название, время, место, описание) без
 *   рассылки писем участникам, где API это позволяет.
 * - [delete] удаляет событие; уже удалённое — не ошибка.
 */
interface Calendar {
    val system: System

    suspend fun list(window: Window): List<CalendarEvent>

    suspend fun create(event: CalendarEvent): String

    suspend fun get(eventId: String): CalendarEvent?

    suspend fun update(eventId: String, event: CalendarEvent)

    suspend fun delete(eventId: String)
}

/**
 * Календарь в памяти: для тестов и экспериментов. Порт `InMemoryCalendar`.
 *
 * - [list] — события, пересекающие окно, в порядке хранения;
 * - [create] — сохраняет событие с его `id` или, если `id` нет, с id `<система>-<n>`
 *   (n — номер вызова `create` с 1, начальные события тоже считаются); добавляет в конец;
 * - [update] — заменяет title/start/end/location/description, остальное не трогает;
 *   неизвестный id — [NoSuchElementException] (`KeyError` в Python);
 * - [delete] — убирает событие, несуществующее — не ошибка.
 *
 * Класс открыт: эталонный прогон (`GoldenCalendar` в `src/sync/golden.py`) переопределяет
 * [create]/[update]/[delete] (id `<система>-new-<n>`, сбои из `failures`) и пишет прямо в
 * [stored].
 */
open class InMemoryCalendar(
    override val system: System,
    events: Iterable<CalendarEvent> = emptyList(),
) : Calendar {
    /** Хранилище событий; изменяемо для подклассов. */
    protected val stored: MutableList<CalendarEvent> = mutableListOf()
    private var next = 0

    /** Содержимое календаря в порядке хранения (все события, в том числе вне окна). */
    val events: List<CalendarEvent> get() = stored.toList()

    init {
        // Как в Python: начальные события проходят через create (и получают id).
        for (event in events) add(event)
    }

    private fun add(event: CalendarEvent): String {
        next += 1
        val eventId = event.id ?: "${system.value}-$next"
        stored += event.copy(id = eventId)
        return eventId
    }

    override suspend fun list(window: Window): List<CalendarEvent> =
        stored.filter { window.overlaps(it) }

    override suspend fun create(event: CalendarEvent): String = add(event)

    override suspend fun get(eventId: String): CalendarEvent? = stored.firstOrNull { it.id == eventId }

    override suspend fun update(eventId: String, event: CalendarEvent) {
        val i = stored.indexOfFirst { it.id == eventId }
        if (i < 0) throw NoSuchElementException(eventId)
        stored[i] = stored[i].copy(
            title = event.title,
            start = event.start,
            end = event.end,
            location = event.location,
            description = event.description,
        )
    }

    override suspend fun delete(eventId: String) {
        stored.removeAll { it.id == eventId }
    }
}
