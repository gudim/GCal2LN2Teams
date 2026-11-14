package app.gcaltolotus.ui

import app.gcaltolotus.app.FakeCalendarSource
import app.gcaltolotus.app.FixedClock
import app.gcaltolotus.app.InMemoryLinkRepository
import app.gcaltolotus.app.InMemorySettingsStore
import app.gcaltolotus.app.PRIMARY_CALENDAR_ID
import app.gcaltolotus.app.SyncService
import app.gcaltolotus.app.event
import app.gcaltolotus.app.memory
import app.gcaltolotus.sync.Link
import app.gcaltolotus.sync.System
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

class FakeGoogleCalendars(vararg calendars: GoogleCalendarInfo) : GoogleCalendars {
    val calendars = calendars.toMutableList()
    var failWith: Exception? = null

    override suspend fun list(): List<GoogleCalendarInfo> {
        failWith?.let { throw it }
        return calendars.toList()
    }

    override suspend fun create(name: String): GoogleCalendarInfo =
        GoogleCalendarInfo("new@group", name).also { calendars += it }
}

class GoogleCalendarModelTest {
    private val settings = InMemorySettingsStore()
    private val links = InMemoryLinkRepository()
    private val google = memory(System.GOOGLE, event("g9", "[MT] Планёрка", source = System.TEAMS))
    private val primary = GoogleCalendarInfo("me@example.com", "me@example.com", primary = true)
    private val notes = GoogleCalendarInfo("notes@group", "Notes")
    private val holidays = GoogleCalendarInfo("ru#holiday", "Праздники", writable = false)
    private val calendars = FakeGoogleCalendars(primary, notes, holidays)

    private fun CoroutineScope.model() =
        GoogleCalendarModel(this, settings, calendars, SyncService(settings, FakeCalendarSource(google), links, FixedClock))

    private fun withOldCopy() = links.save(PRIMARY_CALENDAR_ID, Link(System.TEAMS, "t1", System.GOOGLE, "g9"))

    @Test
    fun loadListsCalendarsAndMarksPrimaryAsCurrent() = runTest {
        val m = model()
        m.load().join()
        val s = m.state.value
        assertEquals(listOf(primary, notes, holidays), s.calendars)
        assertTrue(s.isCurrent(primary))
        assertNull(s.loadError)
    }

    @Test
    fun loadFailureIsShown() = runTest {
        calendars.failWith = IllegalStateException("Требуется вход в Google")
        val m = model()
        m.load().join()
        assertEquals("Требуется вход в Google", m.state.value.loadError)
    }

    @Test
    fun switchWithoutOldCopiesHappensAtOnce() = runTest {
        val m = model()
        m.select(notes)!!.join()
        assertEquals("notes@group", settings.load().googleCalendarId)
        assertEquals(CalendarNotice.Switched("Notes", 0, emptyList()), m.state.value.notice)
        assertTrue(m.state.value.isCurrent(notes))
    }

    @Test
    fun switchAsksAboutOldCopiesAndDeletesThem() = runTest {
        withOldCopy()
        val m = model()
        m.select(notes)!!.join()
        assertEquals(PendingSwitch(notes, 1), m.state.value.pending)
        assertEquals(PRIMARY_CALENDAR_ID, settings.load().googleCalendarId) // ещё не переключили

        m.confirmSwitch(deleteOld = true)!!.join()
        assertNull(m.state.value.pending)
        assertEquals("notes@group", settings.load().googleCalendarId)
        assertTrue(google.events.isEmpty())
        assertEquals(CalendarNotice.Switched("Notes", 1, emptyList()), m.state.value.notice)
    }

    @Test
    fun cancelledSwitchChangesNothing() = runTest {
        withOldCopy()
        val m = model()
        m.select(notes)!!.join()
        m.cancelSwitch()
        assertNull(m.state.value.pending)
        assertEquals(PRIMARY_CALENDAR_ID, settings.load().googleCalendarId)
        assertEquals(1, google.events.size)
    }

    @Test
    fun readOnlyAndCurrentCalendarsCannotBeSelected() = runTest {
        val m = model()
        m.load().join()
        assertNull(m.select(holidays))
        assertNull(m.select(primary))
    }

    @Test
    fun createAddsCalendarAndSwitchesToIt() = runTest {
        val m = model()
        assertNull(m.create("  "))
        assertEquals(CalendarNotice.NameRequired, m.state.value.notice)

        m.create(" Работа ")!!.join()
        assertEquals("new@group", settings.load().googleCalendarId)
        assertEquals("Работа", m.state.value.calendars.last().name)
        assertEquals(CalendarNotice.Switched("Работа", 0, emptyList()), m.state.value.notice)
    }
}
