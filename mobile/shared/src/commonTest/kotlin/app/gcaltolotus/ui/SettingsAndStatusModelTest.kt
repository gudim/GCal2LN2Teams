package app.gcaltolotus.ui

import app.gcaltolotus.adapters.teams.GraphException
import app.gcaltolotus.app.AppRelease
import app.gcaltolotus.app.FixedClock
import app.gcaltolotus.app.InMemorySettingsStore
import app.gcaltolotus.app.InMemoryUpdateStore
import app.gcaltolotus.app.NOW
import app.gcaltolotus.app.SyncSettings
import app.gcaltolotus.app.UpdateCheck
import app.gcaltolotus.sync.System
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class SettingsModelTest {
    private val settings = InMemorySettingsStore(SyncSettings(daysBack = 3, daysForward = 60, backgroundIntervalMinutes = 15))
    private fun CoroutineScope.model(controls: SystemControls = SystemControls.None) = SettingsModel(this, settings, controls)

    @Test
    fun loadShowsSystemStatus() = runTest {
        val status = SystemStatus(backgroundUnrestricted = false, backgroundRestricted = true, notificationsEnabled = true)
        val m = model(InMemorySystemControls(status, isSamsung = true))
        assertTrue(m.state.value.isSamsung)
        m.load().join()
        assertEquals(status, m.state.value.system)
        assertFalse(status.backgroundOk)
    }

    @Test
    fun showsVersionAndAvailableUpdate() = runTest {
        val release = AppRelease("0.2.0", "https://example.com/v0.2.0")
        val updates = UpdateCheck({ release }, InMemoryUpdateStore(), "0.1.0", FixedClock)
        val m = SettingsModel(this, settings, SystemControls.None, updates)
        assertEquals("0.1.0", m.state.value.appVersion)
        m.load().join()
        assertNull(m.state.value.update) // ещё не проверяли
        updates.runIfDue()
        m.load().join()
        assertEquals(release, m.state.value.update)
    }

    @Test
    fun platformWithoutStatusShowsNone() = runTest {
        val m = model()
        m.load().join()
        assertNull(m.state.value.system)
        assertFalse(m.state.value.summaryBlocked)
    }

    @Test
    fun statusIsReadAgainOnResume() = runTest {
        val controls = InMemorySystemControls(SystemStatus(backgroundUnrestricted = false, notificationsEnabled = true))
        val m = model(controls)
        m.load().join()
        backgroundScope.launch { m.followSystemChanges() }
        testScheduler.runCurrent()

        m.allowBackground()
        assertEquals(listOf("background"), controls.opened)
        controls.current = SystemStatus(backgroundUnrestricted = true, notificationsEnabled = true)
        controls.resume()
        testScheduler.runCurrent()
        assertTrue(m.state.value.system!!.backgroundOk)
    }

    @Test
    fun notificationsAreRequestedWhileTheSystemStillAsks() = runTest {
        val controls = InMemorySystemControls(SystemStatus(backgroundUnrestricted = true, notificationsEnabled = false, canRequestNotifications = true))
        val m = model(controls)
        m.load().join()
        m.enableNotifications()!!.join()
        assertEquals(listOf("permission"), controls.opened)
        assertTrue(m.state.value.system!!.notificationsEnabled)

        // Больше спросить нельзя: только системные настройки.
        controls.current = SystemStatus(backgroundUnrestricted = true, notificationsEnabled = false)
        m.refreshSystem().join()
        assertNull(m.enableNotifications())
        assertEquals(listOf("permission", "notifications"), controls.opened)
    }

    @Test
    fun summaryToggleIsPersisted() = runTest {
        val controls = InMemorySystemControls(SystemStatus(backgroundUnrestricted = true, notificationsEnabled = true))
        val m = model(controls)
        m.load().join()
        assertFalse(m.state.value.summaryShown) // по умолчанию выключен

        m.setNotifySummary(true).join()
        assertTrue(settings.load().notifyBackgroundSummary)
        assertTrue(m.state.value.summaryShown)
        assertEquals(emptyList(), controls.opened)

        m.setNotifySummary(false).join()
        assertFalse(settings.load().notifyBackgroundSummary)
        val reopened = model(controls)
        reopened.load().join()
        assertFalse(reopened.state.value.notifySummary)
    }

    @Test
    fun summaryBlockedByChannelOpensChannelSettings() = runTest {
        val controls = InMemorySystemControls(
            SystemStatus(backgroundUnrestricted = true, notificationsEnabled = true, summaryChannelEnabled = false),
        )
        settings.save(settings.load().copy(notifyBackgroundSummary = true))
        val m = model(controls)
        m.load().join()
        assertTrue(m.state.value.summaryBlocked)
        assertFalse(m.state.value.summaryShown) // включён в приложении, но канал выключен в системе

        m.setNotifySummary(true).join()
        assertEquals(listOf("summary"), controls.opened)

        // Пользователь включил канал и вернулся.
        controls.current = controls.current.copy(summaryChannelEnabled = true)
        m.refreshSystem().join()
        assertTrue(m.state.value.summaryShown)
    }

    @Test
    fun summaryBlockedByAppNotificationsAsksForThem() = runTest {
        val controls = InMemorySystemControls(
            SystemStatus(backgroundUnrestricted = true, notificationsEnabled = false, canRequestNotifications = true),
        )
        val m = model(controls)
        m.load().join()
        m.setNotifySummary(true).join()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("permission"), controls.opened)
        assertTrue(m.state.value.summaryShown)
    }

    @Test
    fun loadShowsSavedValues() = runTest {
        val m = model()
        m.load().join()
        assertEquals(SettingsState(daysBack = "3", daysForward = "60", intervalMinutes = 15), m.state.value)
    }

    @Test
    fun windowIsSavedOnlyWhenValid() = runTest {
        val m = model()
        m.editDaysForward("90")!!.join()
        assertEquals(90, settings.load().daysForward)

        assertNull(m.editDaysBack(""))
        assertNull(m.editDaysBack("400"))
        assertFalse(m.state.value.daysBackValid)
        assertEquals(3, settings.load().daysBack)

        m.editDaysBack("0")!!.join()
        assertEquals(0, settings.load().daysBack)
        assertTrue(m.state.value.daysBackValid)
    }

    @Test
    fun intervalIsSavedAndScheduled() = runTest {
        val m = model()
        m.setInterval(0).join()
        assertEquals(0, settings.load().backgroundIntervalMinutes)
        assertTrue(settings.load().backgroundConfigured) // явный выбор «выключена» запоминается
        assertFailsWith<IllegalArgumentException> { m.setInterval(7) }
    }
}

class StatusModelTest {
    @Test
    fun showsLastRunAndDominoState() = runTest {
        val settings = InMemorySettingsStore(SyncSettings(dominoDisabled = true, backgroundIntervalMinutes = 240))
        val history = InMemoryRunHistory(LastRun(NOW, background = true, openQuestions = 2))
        val m = StatusModel(this, settings, history)
        m.load().join()

        val s = m.state.value
        assertEquals(2, s.openQuestions)
        assertTrue(s.dominoDisabled)
        assertFalse(s.dominoUnavailable)
        assertEquals(240, s.backgroundMinutes)
    }

    @Test
    fun freshInstallWaitsForFirstManualApply() = runTest {
        val m = StatusModel(this, InMemorySettingsStore(), InMemoryRunHistory())
        m.load().join()
        assertEquals(0, m.state.value.backgroundMinutes)
        assertTrue(m.state.value.backgroundPending)
        assertNull(m.state.value.nextBackgroundRun)
    }

    @Test
    fun showsNextBackgroundRunFromScheduler() = runTest {
        val next = NOW + 1.hours
        val scheduler = object : BackgroundScheduler {
            override suspend fun nextRun() = next
        }
        val settings = InMemorySettingsStore(SyncSettings(backgroundIntervalMinutes = 60, backgroundConfigured = true))
        val m = StatusModel(this, settings, InMemoryRunHistory(), scheduler, FixedClock)
        m.load().join()
        assertFalse(m.state.value.backgroundPending)
        assertEquals(next, m.state.value.nextBackgroundRun)
        assertFalse(m.state.value.nextBackgroundSoon)

        // Фон выключен пользователем: время следующего запуска не показывается.
        settings.save(settings.load().copy(backgroundIntervalMinutes = 0))
        m.load().join()
        assertNull(m.state.value.nextBackgroundRun)
        assertFalse(m.state.value.backgroundPending)
    }

    @Test
    fun pastNextRunIsShownAsSoon() = runTest {
        // WorkManager ещё не запустил задачу (ждёт сеть или повторяет): время уже прошло.
        val scheduler = object : BackgroundScheduler {
            override suspend fun nextRun() = NOW - 5.minutes
        }
        val settings = InMemorySettingsStore(SyncSettings(backgroundIntervalMinutes = 15, backgroundConfigured = true))
        val m = StatusModel(this, settings, InMemoryRunHistory(), scheduler, FixedClock)
        m.load().join()
        assertTrue(m.state.value.nextBackgroundSoon)
    }

    @Test
    fun keepFreshRereadsEveryMinuteAndOnResume() = runTest {
        var next = NOW + 10.minutes
        val scheduler = object : BackgroundScheduler {
            override suspend fun nextRun() = next
        }
        val settings = InMemorySettingsStore(SyncSettings(backgroundIntervalMinutes = 15, backgroundConfigured = true))
        val history = InMemoryRunHistory()
        val controls = InMemorySystemControls()
        val m = StatusModel(backgroundScope, settings, history, scheduler, FixedClock, controls.resumes)
        val job = backgroundScope.launch { m.keepFresh() }
        runCurrent()
        assertEquals(NOW + 10.minutes, m.state.value.nextBackgroundRun)
        assertNull(m.state.value.lastRun)

        // Прошёл фоновый запуск: через минуту экран показывает новый итог и новое время.
        history.record(LastRun(NOW, background = true))
        next = NOW + 25.minutes
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(NOW + 25.minutes, m.state.value.nextBackgroundRun)
        assertEquals(true, m.state.value.lastRun?.background)

        // Возврат в приложение — сразу, не дожидаясь минуты.
        next = NOW + 40.minutes
        controls.resume()
        runCurrent()
        assertEquals(NOW + 40.minutes, m.state.value.nextBackgroundRun)
        job.cancel()
    }

    @Test
    fun failedRunNamesTheUnavailableSystem() = runTest {
        val history = InMemoryRunHistory()
        history.record(LastRun.failed(GraphException(503, ""), NOW, background = true))
        val m = StatusModel(this, InMemorySettingsStore(), history)
        m.load().join()
        assertEquals(setOf(System.TEAMS), m.state.value.lastRun?.unavailable)
        assertEquals("MS Graph: HTTP 503", m.state.value.lastRun?.failure)
        assertFalse(m.state.value.dominoUnavailable)
        assertEquals(0, m.state.value.openQuestions)
    }
}

class NavigatorTest {
    @Test
    fun backLeavesNestedScreenOnly() {
        val n = Navigator()
        assertEquals(Screen.STATUS, n.screen.value)
        assertFalse(n.back())
        n.open(Screen.GOOGLE_CALENDAR)
        assertEquals(Screen.CONNECTIONS, n.screen.value.tab)
        assertTrue(n.back())
        assertEquals(Screen.CONNECTIONS, n.screen.value)
    }

    @Test
    fun backFromOtherTabsGoesToStatus() {
        for (tab in Screen.TABS - Screen.STATUS) {
            val n = Navigator(tab)
            assertTrue(n.back(), "$tab")
            assertEquals(Screen.STATUS, n.screen.value)
        }
    }

    @Test
    fun backOnStatusIsLeftToThePlatform() {
        val n = Navigator()
        n.open(Screen.SETTINGS)
        n.open(Screen.STATUS)
        assertFalse(n.back())
        assertEquals(Screen.STATUS, n.screen.value)
    }

    @Test
    fun calendarPickerBacksToConnectionsThenStatus() {
        val n = Navigator()
        n.open(Screen.GOOGLE_CALENDAR)
        assertTrue(n.back())
        assertEquals(Screen.CONNECTIONS, n.screen.value)
        assertTrue(n.back())
        assertEquals(Screen.STATUS, n.screen.value)
        assertFalse(n.back())
    }
}
