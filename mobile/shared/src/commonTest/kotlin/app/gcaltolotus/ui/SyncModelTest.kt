package app.gcaltolotus.ui

import app.gcaltolotus.adapters.domino.DominoException
import app.gcaltolotus.adapters.teams.GraphException
import app.gcaltolotus.app.DEFAULT_BACKGROUND_INTERVAL_MINUTES
import app.gcaltolotus.app.FakeCalendarSource
import app.gcaltolotus.app.FixedClock
import app.gcaltolotus.app.InMemoryLinkRepository
import app.gcaltolotus.app.InMemorySettingsStore
import app.gcaltolotus.app.NOW
import app.gcaltolotus.app.PRIMARY_CALENDAR_ID
import app.gcaltolotus.app.Route
import app.gcaltolotus.app.SyncService
import app.gcaltolotus.app.SyncSettings
import app.gcaltolotus.app.event
import app.gcaltolotus.app.memory
import app.gcaltolotus.sync.ActionKind
import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.InMemoryCalendar
import app.gcaltolotus.sync.Link
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class SyncModelTest {
    private val settings = InMemorySettingsStore(SyncSettings())
    private val links = InMemoryLinkRepository()
    private val history = InMemoryRunHistory()
    private lateinit var runs: SyncRuns

    private fun CoroutineScope.model(vararg calendars: InMemoryCalendar): SyncModel {
        runs = SyncRuns(SyncService(settings, FakeCalendarSource(*calendars), links, FixedClock), history, FixedClock)
        return SyncModel(this, settings, runs)
    }

    private fun ready(model: SyncModel): SyncPhase.Ready = assertIs<SyncPhase.Ready>(model.state.value.phase)

    @Test
    fun previewStartsWithDefaultSelection() = runTest {
        val m = model(memory(System.GOOGLE, event("g1", "Обзор")), memory(System.TEAMS, event("t9", "Планёрка", start = "2026-11-14T12:00:00Z", end = "2026-11-14T13:00:00Z")))
        m.preview()!!.join()

        val r = ready(m)
        assertEquals(r.preview.defaultKeys, r.selected)
        assertEquals(setOf("google:g1>teams", "teams:t9>google"), r.selected)
        assertTrue(r.refusals.isEmpty())
    }

    @Test
    fun applyRunsOnlyTickedActionsAndRecordsTheRun() = runTest {
        val teams = memory(System.TEAMS)
        val m = model(memory(System.GOOGLE, event("g1", "Обзор"), event("g2", "Обед")), teams)
        m.preview()!!.join()
        m.toggle("google:g1>teams")
        m.apply()!!.join()

        assertEquals(listOf("Обед"), teams.events.map { it.title })
        val done = assertIs<SyncPhase.Done>(m.state.value.phase)
        assertEquals(1, done.report.totals.created)
        val last = history.last()!!
        assertEquals(NOW, last.finishedAt)
        assertEquals(1, last.totals.created)
        assertEquals(false, last.background)
        assertNull(last.failure)
    }

    @Test
    fun firstManualRunAlwaysShowsThePreview() = runTest {
        val teams = memory(System.TEAMS)
        val m = model(memory(System.GOOGLE, event("g1", "Обзор")), teams)
        m.preview()!!.join()
        ready(m)
        assertTrue(teams.events.isEmpty())
        m.apply()!!.join()
        assertTrue(settings.load().manualApplied)
        assertTrue(settings.load().autoApply)
    }

    @Test
    fun autoApplyRunsEverythingAndReportsWhenThereAreNoQuestions() = runTest {
        settings.update { it.copy(manualApplied = true) }
        val teams = memory(System.TEAMS)
        val m = model(memory(System.GOOGLE, event("g1", "Обзор")), teams)
        m.load().join()
        assertTrue(m.state.value.autoApply)
        m.preview()!!.join()

        val done = assertIs<SyncPhase.Done>(m.state.value.phase)
        assertEquals(1, done.report.totals.created)
        assertEquals(listOf("Обзор"), teams.events.map { it.title })
        assertEquals(false, history.last()!!.background)
    }

    @Test
    fun autoApplyShowsOnlyQuestionsAndMergesTheReport() = runTest {
        settings.update { it.copy(manualApplied = true) }
        // g1 копируется сразу; g2 и t9 совпадают по названию и началу — вопрос «одна встреча?»
        val google = memory(System.GOOGLE, event("g1", "Обзор"), event("g2", "Планёрка", start = "2026-11-14T12:00:00Z", end = "2026-11-14T13:00:00Z"))
        val teams = memory(System.TEAMS, event("t9", "Планёрка", start = "2026-11-14T12:00:00Z", end = "2026-11-14T13:00:00Z"))
        val m = model(google, teams)
        m.preview()!!.join()

        val r = ready(m)
        assertEquals(1, r.autoApplied!!.totals.created)
        assertTrue("Обзор" in teams.events.map { it.title })
        assertTrue(r.preview.actions.any { it.isQuestion })
        assertTrue(r.preview.actions.none { it.title == "Обзор" })

        m.apply()!!.join()
        val done = assertIs<SyncPhase.Done>(m.state.value.phase)
        assertEquals(1, done.report.totals.created)
        assertEquals(1, done.report.totals.linked)
    }

    @Test
    fun confirmEachActionKeepsTheFullPreview() = runTest {
        settings.update { it.copy(manualApplied = true, confirmEachAction = true) }
        val teams = memory(System.TEAMS)
        val m = model(memory(System.GOOGLE, event("g1", "Обзор")), teams)
        m.preview()!!.join()
        assertNull(ready(m).autoApplied)
        assertTrue(teams.events.isEmpty())
    }

    @Test
    fun firstManualApplyTurnsBackgroundOnOnce() = runTest {
        assertEquals(0, settings.load().backgroundIntervalMinutes)
        val m = model(memory(System.GOOGLE, event("g1", "Обзор")), memory(System.TEAMS))
        m.preview()!!.join()
        assertEquals(0, settings.load().backgroundIntervalMinutes) // предпросмотр не включает фон
        m.apply()!!.join()

        assertEquals(DEFAULT_BACKGROUND_INTERVAL_MINUTES, settings.load().backgroundIntervalMinutes)
        assertTrue(settings.load().backgroundConfigured)

        // Пользователь выключил фон; следующие применения его выбор не трогают.
        settings.save(settings.load().copy(backgroundIntervalMinutes = 0))
        m.reset()
        m.preview(full = true)!!.join()
        m.apply()!!.join()
        assertEquals(0, settings.load().backgroundIntervalMinutes)
    }

    @Test
    fun explicitChoiceBeforeFirstApplyIsKept() = runTest {
        settings.save(SyncSettings(backgroundIntervalMinutes = 0, backgroundConfigured = true))
        val m = model(memory(System.GOOGLE, event("g1", "Обзор")), memory(System.TEAMS))
        m.preview()!!.join()
        m.apply()!!.join()
        assertEquals(0, settings.load().backgroundIntervalMinutes)
    }

    @Test
    fun selectAllOfARouteTogglesOnlyThatRoute() = runTest {
        val m = model(memory(System.GOOGLE, event("g1", "Обзор"), event("g2", "Обед")), memory(System.TEAMS, event("t9", "Планёрка", start = "2026-11-14T12:00:00Z", end = "2026-11-14T13:00:00Z")))
        m.preview()!!.join()
        m.setAll(Route(System.GOOGLE, System.TEAMS), checked = false)
        assertEquals(setOf("teams:t9>google"), ready(m).selected)
        m.setAll(Route(System.GOOGLE, System.TEAMS), checked = true)
        assertEquals(3, ready(m).selected.size)
    }

    @Test
    fun untickedQuestionIsShownAsRefusalAndRemembered() = runTest {
        // Копия «Обзора» в Teams удалена: вопрос «удалить оригинал?», по умолчанию без галочки.
        val original = event("g1", "Обзор")
        links.save(
            PRIMARY_CALENDAR_ID,
            Link(System.GOOGLE, "g1", System.TEAMS, "t1", sourceFields = original.fields(), targetFields = original.copy(source = System.GOOGLE).fields()),
        )
        val google = memory(System.GOOGLE, original)
        val m = model(google, memory(System.TEAMS))
        m.preview()!!.join()

        val r = ready(m)
        val question = r.preview.actions.single()
        assertTrue(question.isQuestion)
        assertEquals(listOf(question), r.refusals)

        m.apply()!!.join()
        assertEquals(listOf("Обзор"), google.events.map { it.title })
        assertTrue(links.load(PRIMARY_CALENDAR_ID).single().kept)
        assertEquals(0, history.last()!!.openQuestions) // отказ запомнен — вопрос закрыт
    }

    @Test
    fun untickingTwinQuestionCountsAsRefusal() = runTest {
        // «Это одна встреча?» отмечен по умолчанию; снятая галочка — отказ, который запомнится.
        val m = model(memory(System.GOOGLE, event("g1", "Обзор")), memory(System.TEAMS, event("t1", "обзор")))
        m.preview()!!.join()
        val twin = ready(m).preview.actions.single()
        m.toggle(twin.key)
        assertEquals(listOf(twin), ready(m).refusals)
        m.apply()!!.join()
        assertEquals(0, history.last()!!.openQuestions)
    }

    @Test
    fun previewFailureIsShownAndRecorded() = runTest {
        // Сбой Domino (кроме 401) только пропускает Lotus в этом запуске (ADR 0002),
        // поэтому весь предпросмотр проваливает сбой другой системы.
        val broken = object : InMemoryCalendar(System.TEAMS) {
            override suspend fun list(window: Window): List<CalendarEvent> =
                throw GraphException(503, "", "Teams: нет связи")
        }
        val m = model(memory(System.GOOGLE), broken)
        m.preview()!!.join()

        val failed = assertIs<SyncPhase.Failed>(m.state.value.phase)
        assertEquals("Teams: нет связи", failed.message)
        assertEquals(false, failed.applying)
        assertEquals(setOf(System.TEAMS), history.last()!!.unavailable)
        assertFalse(settings.load().backgroundConfigured) // неудачный запуск фон не включает

        m.reset()
        assertEquals(SyncPhase.Idle, m.state.value.phase)
    }

    @Test
    fun routesAreSavedToSettings() = runTest {
        val m = model()
        m.load().join()
        assertEquals(ALL_ROUTES.toSet(), m.state.value.routes)

        m.setRoute(Route(System.DOMINO, System.TEAMS), enabled = false).join()
        val saved = settings.load()
        assertEquals("all", saved.direction)
        assertEquals(ALL_ROUTES.toSet() - Route(System.DOMINO, System.TEAMS), saved.routes)
        assertEquals(5, saved.activeRoutes().size)

        m.setRoute(Route(System.DOMINO, System.TEAMS), enabled = true).join()
        assertNull(settings.load().routes) // все маршруты — без списка
    }

    @Test
    fun applyAndToggleIgnoredOutsidePreview() = runTest {
        val m = model()
        assertNull(m.apply())
        m.toggle("google:g1>teams")
        assertEquals(SyncPhase.Idle, m.state.value.phase)
    }

    @Test
    fun manualStepsAreSkippedWhileBackgroundRuns() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        settings.save(SyncSettings(backgroundIntervalMinutes = 60, backgroundConfigured = true))
        val teams = object : InMemoryCalendar(System.TEAMS) {
            var hold = false

            override suspend fun list(window: Window): List<CalendarEvent> {
                if (hold) {
                    entered.complete(Unit)
                    gate.await()
                }
                return super.list(window)
            }
        }
        val m = model(memory(System.GOOGLE, event("g1", "Обзор")), teams)
        m.preview()!!.join()
        val shown = ready(m)

        teams.hold = true
        val bg = async { runs.background() }
        entered.await()
        assertTrue(m.backgroundRunning.value)
        m.apply()!!.join()
        assertEquals(shown, m.state.value.phase) // нажатие пропущено, предпросмотр как был
        m.reset()
        m.preview()!!.join()
        assertEquals(SyncPhase.Idle, m.state.value.phase)

        gate.complete(Unit)
        bg.await()
        assertFalse(m.backgroundRunning.value)
    }

    @Test
    fun stalePreviewIsRebuiltInsteadOfAppliedAndKeepsTicks() = runTest {
        settings.save(SyncSettings(backgroundIntervalMinutes = 60, backgroundConfigured = true))
        val google = memory(System.GOOGLE, event("g1", "Обзор"), event("g2", "Обед"))
        var down = false
        val teams = object : InMemoryCalendar(System.TEAMS) {
            override suspend fun list(window: Window): List<CalendarEvent> =
                if (down) throw GraphException(503, "", "нет связи") else super.list(window)
        }
        val m = model(google, teams)
        m.preview()!!.join()
        m.toggle("google:g1>teams") // пользователь снял галочку
        assertFalse(ready(m).rebuiltAfterRun)

        // Пока предпросмотр открыт: в Google новое событие, прошёл фоновый запуск (неудачный).
        google.create(event("g3", "Новое", start = "2026-11-14T15:00:00Z", end = "2026-11-14T16:00:00Z"))
        down = true
        runCatching { runs.background() }
        down = false
        m.apply()!!.join()

        val r = ready(m) // не применено: предпросмотр построен заново
        assertTrue(r.rebuiltAfterRun)
        assertTrue(teams.events.isEmpty())
        val added = r.preview.actions.single { it.title == "Новое" }.key
        assertEquals(setOf("google:g2>teams", added), r.selected) // снятая галочка осталась снятой

        // Второе «Применить» по свежему предпросмотру выполняется.
        m.apply()!!.join()
        assertIs<SyncPhase.Done>(m.state.value.phase)
        assertEquals(setOf("Обед", "Новое"), teams.events.map { it.title }.toSet())
    }

    /** Снять галочку с создания «Обзора» в Teams и применить: запоминается отказ. Модель снова на маршрутах. */
    private suspend fun refuseCopy(m: SyncModel) {
        m.preview()!!.join()
        m.toggle(REFUSED)
        m.apply()!!.join()
        m.reset()
    }

    @Test
    fun untickedCreateCountsAsRefusalButRefusedRowsDoNot() = runTest {
        val m = model(memory(System.GOOGLE, event("g1", "Обзор"), event("g2", "Обед")), memory(System.TEAMS))
        m.preview()!!.join()
        m.toggle(REFUSED)
        val r = ready(m)
        assertEquals(listOf(REFUSED), r.refusals.map { it.key }) // «Снятые галочки запомнятся как отказ: 1»
        assertEquals(1, r.selectedActions)
        assertTrue(r.copyNow.isEmpty())

        m.apply()!!.join()
        m.reset()
        m.preview(full = true)!!.join()
        val again = ready(m)
        assertEquals(listOf(REFUSED), again.preview.refused.map { it.key })
        assertTrue(again.refusals.isEmpty()) // строка «Не копируется» без галочки — не новый отказ
        m.toggle(REFUSED)
        assertEquals(listOf(REFUSED), ready(m).copyNow.map { it.key })
        assertTrue(ready(m).refusals.isEmpty())
        assertEquals(0, ready(m).selectedActions)
    }

    @Test
    fun tickingRefusedRowCopiesItNow() = runTest {
        val teams = memory(System.TEAMS)
        val m = model(memory(System.GOOGLE, event("g1", "Обзор")), teams)
        refuseCopy(m)
        assertTrue(teams.events.isEmpty())

        m.preview(full = true)!!.join()
        assertTrue(ready(m).preview.actions.isEmpty())
        m.toggle(REFUSED)
        assertTrue(REFUSED in ready(m).selected) // ключ уходит в applySelected
        m.apply()!!.join()

        assertEquals(listOf("Обзор"), teams.events.map { it.title })
        m.reset()
        m.preview(full = true)!!.join()
        assertTrue(ready(m).preview.refused.isEmpty())
    }

    @Test
    fun undoRefusalRebuildsPreviewWithTheCopyTicked() = runTest {
        val m = model(memory(System.GOOGLE, event("g1", "Обзор"), event("g2", "Обед")), memory(System.TEAMS))
        refuseCopy(m)
        m.preview(full = true)!!.join()
        val other = "google:g2>teams"
        m.toggle(other) // выбор пользователя переносится в новый предпросмотр
        val before = ready(m).preview

        m.undoRefusal(REFUSED)!!.join()
        val r = ready(m)
        assertTrue(r.preview !== before) // предпросмотр построен заново
        assertTrue(r.preview.refused.isEmpty())
        assertTrue(r.preview.actions.any { it.key == REFUSED })
        assertEquals(setOf(REFUSED), r.selected)
        assertFalse(r.rebuiltAfterRun)
    }

    @Test
    fun timeTwinShowsBothTitlesAndStartsUnticked() = runTest {
        val m = model(memory(System.GOOGLE, event("g1", "Обзор")), memory(System.TEAMS, event("t1", "Weekly review")))
        m.preview()!!.join()
        val twin = ready(m).preview.actions.single { it.kind == ActionKind.TWIN }
        assertFalse(twin.selected)
        assertTrue(twin.isQuestion)
        assertEquals(setOf("Обзор", "Weekly review"), setOf(twin.title, twin.partnerTitle))
    }

    private companion object {
        const val REFUSED = "google:g1>teams"
    }
}
