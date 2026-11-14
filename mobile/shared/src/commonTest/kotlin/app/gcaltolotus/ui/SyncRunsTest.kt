package app.gcaltolotus.ui

import app.gcaltolotus.adapters.teams.GraphException
import app.gcaltolotus.app.FakeCalendarSource
import app.gcaltolotus.app.FixedClock
import app.gcaltolotus.app.InMemoryLinkRepository
import app.gcaltolotus.app.InMemorySettingsStore
import app.gcaltolotus.app.SyncService
import app.gcaltolotus.app.SyncSettings
import app.gcaltolotus.app.event
import app.gcaltolotus.app.memory
import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.InMemoryCalendar
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class SyncRunsTest {
    // Фоновый запуск работает только после первого ручного применения (ADR 0003).
    private val settings = InMemorySettingsStore(SyncSettings(backgroundIntervalMinutes = 60, backgroundConfigured = true))
    private val history = InMemoryRunHistory()
    private val networkPrepared = mutableListOf<String>()
    private val outcomes = mutableListOf<RunOutcome>()

    private fun runs(vararg calendars: InMemoryCalendar, history: RunHistory = this.history) = SyncRuns(
        SyncService(settings, FakeCalendarSource(*calendars), InMemoryLinkRepository(), FixedClock),
        history,
        FixedClock,
        beforeNetwork = { networkPrepared += "drop" },
        afterRun = { outcomes += it },
    )

    /** Календарь Teams, чтение которого ждёт [gate]: так запуск «идёт», пока тест не отпустит. */
    private fun gatedTeams(gate: CompletableDeferred<Unit>, entered: CompletableDeferred<Unit>) =
        object : InMemoryCalendar(System.TEAMS) {
            override suspend fun list(window: Window): List<CalendarEvent> {
                entered.complete(Unit)
                gate.await()
                return super.list(window)
            }
        }

    @Test
    fun manualStepIsSkippedWhileBackgroundRuns() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val r = runs(memory(System.GOOGLE, event("g1", "Обзор")), gatedTeams(gate, entered))

        val bg = async { r.background() }
        entered.await()
        assertTrue(r.backgroundRunning.value)
        assertNull(r.preview()) // пропущен, ничего не ждёт

        gate.complete(Unit)
        bg.await()
        assertFalse(r.backgroundRunning.value)
        assertNotNull(r.preview())
    }

    @Test
    fun backgroundWaitsForManualStepThenRuns() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val r = runs(memory(System.GOOGLE, event("g1", "Обзор")), gatedTeams(gate, entered))

        val manual = async { r.preview() }
        entered.await()
        val bg = async { r.background() }
        testScheduler.runCurrent()
        assertFalse(bg.isCompleted) // ждёт ручной шаг
        assertFalse(r.backgroundRunning.value)

        gate.complete(Unit)
        assertNotNull(manual.await())
        bg.await()
        assertEquals(true, history.last()!!.background)
    }

    @Test
    fun previewBuiltBeforeBackgroundRunIsRebuiltNotApplied() = runTest {
        val teams = memory(System.TEAMS)
        val r = runs(memory(System.GOOGLE, event("g1", "Обзор")), teams)
        val built = r.preview()!!

        r.background() // фон уже скопировал «Обзор»
        val created = teams.events.size
        val outcome = assertIs<SyncRuns.Applied.Stale>(r.apply(built, built.preview.defaultKeys))
        assertEquals(created, teams.events.size) // старый план не применён: дубля нет
        assertTrue(outcome.rebuilt.preview.actions.isEmpty())

        assertIs<SyncRuns.Applied.Done>(r.apply(outcome.rebuilt, outcome.rebuilt.preview.defaultKeys))
    }

    @Test
    fun staleCheckDoesNotDependOnHistory() = runTest {
        val broken = object : RunHistory {
            override suspend fun last(): LastRun? = null

            override suspend fun record(run: LastRun) = throw IllegalStateException("диск полон")
        }
        val teams = memory(System.TEAMS)
        val r = runs(memory(System.GOOGLE, event("g1", "Обзор")), teams, history = broken)
        val built = r.preview()!!
        r.background() // запись истории упала — запуск всё равно засчитан
        assertIs<SyncRuns.Applied.Stale>(r.apply(built, built.preview.defaultKeys))
    }

    @Test
    fun failedBackgroundRunAlsoMakesPreviewStale() = runTest {
        var fail = false
        val teams = object : InMemoryCalendar(System.TEAMS) {
            override suspend fun list(window: Window): List<CalendarEvent> =
                if (fail) throw GraphException(503, "", "Teams: нет связи") else super.list(window)
        }
        val r = runs(memory(System.GOOGLE, event("g1", "Обзор")), teams)
        val built = r.preview()!!
        fail = true
        assertFailsWith<GraphException> { r.background() }
        val last = history.last()!!
        assertTrue(last.background)
        assertEquals("Teams: нет связи", last.failure)
        assertFalse(r.backgroundRunning.value)

        fail = false
        assertIs<SyncRuns.Applied.Stale>(r.apply(built, built.preview.defaultKeys))
    }

    @Test
    fun manualFailureIsRecordedAndRethrown() = runTest {
        val broken = object : InMemoryCalendar(System.TEAMS) {
            override suspend fun list(window: Window): List<CalendarEvent> = throw GraphException(503, "", "нет связи")
        }
        val r = runs(memory(System.GOOGLE), broken)
        assertFailsWith<GraphException> { r.preview() }
        assertEquals(false, history.last()!!.background)
        assertEquals(setOf(System.TEAMS), history.last()!!.unavailable)
        assertNotNull(runCatching { r.preview() }.exceptionOrNull()) // блокировка отпущена
    }

    @Test
    fun everyRunReportsItsOutcomeForNotifications() = runTest {
        val r = runs(memory(System.GOOGLE, event("g1", "Обзор")), memory(System.TEAMS))
        val built = r.preview()!!
        assertTrue(outcomes.isEmpty()) // предпросмотр — не запуск
        r.apply(built, built.preview.defaultKeys)
        val manual = assertIs<RunOutcome.Finished>(outcomes.single())
        assertFalse(manual.background)
        assertEquals(0, manual.openQuestions)

        outcomes.clear()
        r.background()
        assertTrue(assertIs<RunOutcome.Finished>(outcomes.single()).background)
    }

    @Test
    fun failedRunReportsFailureEvenIfNotificationsBreak() = runTest {
        val broken = object : InMemoryCalendar(System.TEAMS) {
            override suspend fun list(window: Window): List<CalendarEvent> = throw GraphException(503, "", "нет связи")
        }
        val r = SyncRuns(
            SyncService(settings, FakeCalendarSource(memory(System.GOOGLE), broken), InMemoryLinkRepository(), FixedClock),
            history,
            FixedClock,
            afterRun = { outcomes += it; throw IllegalStateException("уведомления недоступны") },
        )
        assertFailsWith<GraphException> { r.background() } // исходная ошибка, не ошибка уведомлений
        assertIs<RunOutcome.Failed>(outcomes.single())
        assertEquals("нет связи", history.last()!!.failure)
    }

    @Test
    fun connectionsArePreparedBeforeEveryRun() = runTest {
        val r = runs(memory(System.GOOGLE, event("g1", "Обзор")), memory(System.TEAMS))
        val built = r.preview()!!
        r.apply(built, built.preview.defaultKeys)
        r.background()
        assertEquals(3, networkPrepared.size)
    }
}
