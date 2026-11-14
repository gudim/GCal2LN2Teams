package app.gcaltolotus.app

import app.gcaltolotus.sync.System
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Выбор календаря Google, как `/api/google/calendars` (GET и POST) веб-версии /v2. */
class GoogleCalendarsTest {
    private val settings = InMemorySettingsStore(SyncSettings())
    private val links = InMemoryLinkRepository()

    private fun service(source: CalendarSource) = SyncService(settings, source, links, FixedClock)

    @Test
    fun listsCalendarsWithCurrentAndWritable() = runTest {
        settings.save(settings.load().copy(googleCalendarId = "team@group"))
        val google = FakeGoogleWithCalendars(
            mutableListOf(
                googleCalendar("me@example.com", "owner", primary = true),
                googleCalendar("team@group", "reader"),
                googleCalendar("notes@group", "writer"),
                googleCalendar("holidays", "freeBusyReader"),
            ),
        )

        val list = service(FakeCalendarSource(google)).googleCalendars()

        assertEquals("team@group", list.current)
        assertEquals(4, list.calendars.size)
        assertEquals(listOf("me@example.com", "notes@group"), list.writable.map { it.id })
        // текущий календарь только для чтения остаётся в выборе, как в веб-версии
        assertEquals(listOf("me@example.com", "team@group", "notes@group"), list.selectable.map { it.id })
        assertEquals("team@group", list.currentCalendar?.id)
    }

    @Test
    fun primaryAliasMatchesThePrimaryCalendar() = runTest {
        val google = FakeGoogleWithCalendars(mutableListOf(googleCalendar("me@example.com", "owner", primary = true)))
        val list = service(FakeCalendarSource(google)).googleCalendars()
        assertEquals(PRIMARY_CALENDAR_ID, list.current)
        assertEquals("me@example.com", list.currentCalendar?.id)
        assertTrue(list.isCurrent(list.calendars.single()))
    }

    @Test
    fun createsCalendarWithTrimmedNameOrNotes() = runTest {
        val google = FakeGoogleWithCalendars()
        val svc = service(FakeCalendarSource(google))

        val created = svc.createGoogleCalendar("  Рабочий  ")
        svc.createGoogleCalendar("   ")

        assertEquals(listOf("Рабочий", "Notes"), google.createdSummaries)
        assertEquals("Рабочий", created.summary)
        assertTrue(created.writable)
        // календарь не переключается: выбор подтверждает пользователь (switchGoogleCalendar)
        assertEquals(PRIMARY_CALENDAR_ID, settings.load().googleCalendarId)
    }

    @Test
    fun withoutGoogleSignInPickerFails() = runTest {
        assertFailsWith<SyncSetupException> { service(FakeCalendarSource()).googleCalendars() }
        assertFailsWith<SyncSetupException> { service(FakeCalendarSource()).createGoogleCalendar("Notes") }
        // календарь без выбора (не адаптер Google) — тоже нет списка
        assertFailsWith<SyncSetupException> { service(FakeCalendarSource(memory(System.GOOGLE))).googleCalendars() }
    }
}
