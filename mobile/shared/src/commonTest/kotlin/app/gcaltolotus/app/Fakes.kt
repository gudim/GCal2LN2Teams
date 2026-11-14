package app.gcaltolotus.app

import app.gcaltolotus.adapters.domino.DominoAuthException
import app.gcaltolotus.adapters.google.GoogleCalendarDirectory
import app.gcaltolotus.adapters.google.GoogleCalendarInfo
import app.gcaltolotus.sync.Calendar
import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.InMemoryCalendar
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.When
import app.gcaltolotus.sync.Window
import kotlin.time.Clock
import kotlin.time.Instant

/** Сейчас для тестов: окно по умолчанию (−1 день, +30 дней) — 12.11 … 13.12.2026. */
val NOW: Instant = Instant.parse("2026-11-13T08:00:00Z")

object FixedClock : Clock {
    override fun now(): Instant = NOW
}

fun at(iso: String): When = When.parse(iso)

fun event(id: String, title: String, start: String = "2026-11-14T10:00:00Z", end: String = "2026-11-14T11:00:00Z", source: System? = null) =
    CalendarEvent(title, at(start), at(end), id = id, source = source)

/** Domino, который отвечает 401 на всё (как DominoCalendar после первого 401) и считает вызовы. */
class UnauthorizedDomino : Calendar {
    override val system: System = System.DOMINO
    var calls = 0

    private fun fail(): Nothing {
        calls += 1
        throw DominoAuthException("Domino: 401")
    }

    override suspend fun list(window: Window): List<CalendarEvent> = fail()
    override suspend fun create(event: CalendarEvent): String = fail()
    override suspend fun get(eventId: String): CalendarEvent? = fail()
    override suspend fun update(eventId: String, event: CalendarEvent) = fail()
    override suspend fun delete(eventId: String) = fail()
}

/**
 * Источник календарей для тестов: отдаёт заранее заданные календари тех систем, которые
 * просят и которые не выключены настройками (как [AdapterCalendarSource]).
 */
class FakeCalendarSource(vararg calendars: Calendar) : CalendarSource {
    val calendars: MutableMap<System, Calendar> = calendars.associateBy { it.system }.toMutableMap()
    val opened = mutableListOf<Set<System>>()

    override suspend fun open(settings: SyncSettings, systems: Set<System>): OpenedCalendars {
        opened += systems
        val result = LinkedHashMap<System, Calendar>()
        val skipped = mutableListOf<SkippedSystem>()
        for (s in System.ORDER.filter { it in systems }) {
            val calendar = calendars[s]
            when {
                s == System.DOMINO && settings.dominoDisabled -> skipped += SkippedSystem(s, SkipReason.DOMINO_DISABLED)
                calendar == null -> skipped += SkippedSystem(s, SkipReason.NOT_CONFIGURED)
                else -> result[s] = calendar
            }
        }
        return OpenedCalendars(result, skipped)
    }
}

fun memory(system: System, vararg events: CalendarEvent) = InMemoryCalendar(system, events.toList())

/**
 * Domino поверх [inner]: [fail] решает, какой ошибкой ответить на операцию (`list`, `get`, `create`,
 * `update`, `delete`; `null` — выполнить). Все обращения пишутся в [calls].
 */
class FlakyDomino(
    private val inner: Calendar = InMemoryCalendar(System.DOMINO),
    private val fail: (op: String) -> Exception? = { null },
) : Calendar {
    override val system: System = System.DOMINO
    val calls = mutableListOf<String>()

    private suspend fun <T> op(name: String, block: suspend () -> T): T {
        calls += name
        fail(name)?.let { throw it }
        return block()
    }

    override suspend fun list(window: Window): List<CalendarEvent> = op("list") { inner.list(window) }
    override suspend fun create(event: CalendarEvent): String = op("create") { inner.create(event) }
    override suspend fun get(eventId: String): CalendarEvent? = op("get") { inner.get(eventId) }
    override suspend fun update(eventId: String, event: CalendarEvent) = op("update") { inner.update(eventId, event) }
    override suspend fun delete(eventId: String) = op("delete") { inner.delete(eventId) }
}

/** Google с выбором календаря: события в памяти, календари аккаунта — [calendars]. */
class FakeGoogleWithCalendars(
    val calendars: MutableList<GoogleCalendarInfo> = mutableListOf(),
) : InMemoryCalendar(System.GOOGLE), GoogleCalendarDirectory {
    val createdSummaries = mutableListOf<String>()

    override suspend fun listCalendars(): List<GoogleCalendarInfo> = calendars.toList()

    override suspend fun createCalendar(summary: String): GoogleCalendarInfo {
        createdSummaries += summary
        val info = GoogleCalendarInfo(
            id = "new${createdSummaries.size}@group", summary = summary, description = "", timeZone = "Europe/Moscow",
            accessRole = "owner", primary = false, selected = false,
        )
        calendars += info
        return info
    }
}

fun googleCalendar(id: String, role: String, primary: Boolean = false) =
    GoogleCalendarInfo(id, id, "", "UTC", role, primary, selected = true)
