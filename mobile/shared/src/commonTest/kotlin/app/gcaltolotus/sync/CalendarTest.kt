package app.gcaltolotus.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

private val WINDOW = Window(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"))

class WindowTest {
    @Test
    fun timedOverlapIsHalfOpen() {
        assertEquals(true, WINDOW.overlaps(timed("a", "2025-12-31T23:30:00Z")))
        assertEquals(false, WINDOW.overlaps(timed("b", "2025-12-31T23:00:00Z"))) // кончается в начале окна
        assertEquals(true, WINDOW.overlaps(timed("c", "2026-12-31T23:59:00Z")))
        assertEquals(false, WINDOW.overlaps(timed("d", "2027-01-01T00:00:00Z"))) // начинается в конце окна
    }

    @Test
    fun allDayOverlapUsesUtcDatesAndExclusiveEnd() {
        fun allDay(s: String, e: String) = CalendarEvent("x", day(s), day(e))
        assertEquals(false, WINDOW.overlaps(allDay("2025-12-31", "2026-01-01")))
        assertEquals(true, WINDOW.overlaps(allDay("2025-12-31", "2026-01-02")))
        assertEquals(true, WINDOW.overlaps(allDay("2026-12-31", "2027-01-01")))
        assertEquals(false, WINDOW.overlaps(allDay("2027-01-01", "2027-01-02")))
    }
}

class InMemoryCalendarTest {
    @Test
    fun createAssignsIdOrKeepsGivenOne() = runTest {
        val cal = InMemoryCalendar(System.GOOGLE, listOf(timed("A", "2026-11-13T10:00:00Z")))
        assertEquals("google-2", cal.create(timed("B", "2026-11-13T11:00:00Z")))
        assertEquals("own", cal.create(timed("C", "2026-11-13T12:00:00Z", id = "own")))
        assertEquals(listOf("google-1", "google-2", "own"), cal.events.map { it.id })
    }

    @Test
    fun listReturnsOnlyEventsInWindowInStorageOrder() = runTest {
        val cal = InMemoryCalendar(
            System.DOMINO,
            listOf(
                timed("late", "2026-12-01T10:00:00Z", id = "d2"),
                timed("outside", "2025-06-01T10:00:00Z", id = "d0"),
                timed("early", "2026-02-01T10:00:00Z", id = "d1"),
            ),
        )
        assertEquals(listOf("d2", "d1"), cal.list(WINDOW).map { it.id })
        // get видит и события вне окна
        assertEquals("outside", cal.get("d0")?.title)
        assertNull(cal.get("nope"))
    }

    @Test
    fun updateReplacesOnlySyncedFields() = runTest {
        val original = timed("A", "2026-11-13T10:00:00Z", id = "t1", source = System.GOOGLE)
            .copy(uid = "U", isPrivate = true, hasAttendees = true)
        val cal = InMemoryCalendar(System.TEAMS, listOf(original))
        val change = CalendarEvent(
            "B", day("2026-11-14"), day("2026-11-15"), location = "L", description = "D", id = "other",
        )
        cal.update("t1", change)
        assertEquals(
            original.copy(
                title = "B", start = day("2026-11-14"), end = day("2026-11-15"),
                location = "L", description = "D",
            ),
            cal.get("t1"),
        )
        assertFailsWith<NoSuchElementException> { cal.update("missing", change) }
    }

    @Test
    fun deleteIsIdempotent() = runTest {
        val cal = InMemoryCalendar(System.GOOGLE, listOf(timed("A", "2026-11-13T10:00:00Z", id = "g1")))
        cal.delete("g1")
        cal.delete("g1")
        assertEquals(emptyList(), cal.events)
    }

    @Test
    fun subclassCanAssignItsOwnIds() = runTest {
        // Как GoldenCalendar в src/sync/golden.py: id копии назначает «сервер».
        class Golden(system: System, events: List<CalendarEvent>) : InMemoryCalendar(system, events) {
            var created = 0

            override suspend fun create(event: CalendarEvent): String {
                created += 1
                val id = "${system.value}-new-$created"
                stored += event.copy(id = id)
                return id
            }
        }
        val cal = Golden(System.DOMINO, listOf(timed("A", "2026-11-13T10:00:00Z", id = "d1")))
        assertEquals("domino-new-1", cal.create(timed("B", "2026-11-13T10:00:00Z", id = "g1")))
        assertEquals(listOf("d1", "domino-new-1"), cal.events.map { it.id })
    }
}

class LinkTest {
    @Test
    fun keys() {
        assertEquals("google:g1->domino", Link(System.GOOGLE, "g1", System.DOMINO, "d1").key())
        assertEquals("google:g1->domino", Link(System.GOOGLE, "g1", System.DOMINO).key())
        assertEquals(
            "twin:google:g1->domino:d1",
            Link(System.GOOGLE, "g1", System.DOMINO, "d1", kind = LinkKind.TWIN).key(),
        )
        assertEquals(
            "distinct:domino:d1->teams:t1",
            Link(System.DOMINO, "d1", System.TEAMS, "t1", kind = LinkKind.DISTINCT).key(),
        )
        // Python пишет None через f-строку
        assertEquals(
            "twin:google:g1->domino:None",
            Link(System.GOOGLE, "g1", System.DOMINO, null, kind = LinkKind.TWIN).key(),
        )
    }

    @Test
    fun kindFromValue() {
        assertEquals(LinkKind.DISTINCT, LinkKind.fromValue("distinct"))
        assertFailsWith<IllegalArgumentException> { LinkKind.fromValue("mirror") }
    }
}
