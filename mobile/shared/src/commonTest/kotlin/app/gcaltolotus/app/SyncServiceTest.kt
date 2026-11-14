package app.gcaltolotus.app

import app.gcaltolotus.sync.ActionKind
import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.DeleteSubject
import app.gcaltolotus.sync.InMemoryCalendar
import app.gcaltolotus.sync.Link
import app.gcaltolotus.sync.LinkKind
import app.gcaltolotus.sync.System
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SyncServiceTest {
    private val settings = InMemorySettingsStore(SyncSettings(direction = "all"))
    private val links = InMemoryLinkRepository()

    private fun service(source: CalendarSource) = SyncService(settings, source, links, FixedClock)

    /** Google-оригинал «Обзор», удалённый в Teams копией: вопрос «удалить оригинал?». */
    private fun copyGoneQuestion(google: InMemoryCalendar): Link {
        val original = event("g1", "Обзор")
        val link = Link(System.GOOGLE, "g1", System.TEAMS, "t1", sourceFields = original.fields(), targetFields = original.copy(source = System.GOOGLE).fields())
        links.save(PRIMARY_CALENDAR_ID, link)
        return link
    }

    @Test
    fun previewGroupsActionsByRouteWithDefaultSelection() = runTest {
        val google = memory(System.GOOGLE, event("g1", "Обзор"))
        // другое время: одинаковые начало и конец дали бы вопрос «одна встреча?» по времени
        val teams = memory(System.TEAMS, event("t9", "Планёрка", start = "2026-11-14T12:00:00Z", end = "2026-11-14T13:00:00Z"))
        val preview = service(FakeCalendarSource(google, teams)).preview()

        assertEquals(listOf(Route(System.GOOGLE, System.TEAMS), Route(System.TEAMS, System.GOOGLE)), preview.routes.map { it.route })
        assertEquals(listOf("google:g1>teams"), preview.routes[0].actions.map { it.key })
        assertEquals(setOf("google:g1>teams", "teams:t9>google"), preview.defaultKeys)
        assertEquals(RouteCounts(created = 1), preview.routes[0].defaultCounts)
        val a = preview.actions.first()
        assertEquals(ActionKind.CREATE, a.kind)
        assertEquals("Обзор", a.title)
        assertFalse(a.isQuestion)
        assertEquals(listOf(SkippedSystem(System.DOMINO, SkipReason.NOT_CONFIGURED)), preview.skipped)
        // предпросмотр ничего не пишет
        assertEquals(1, teams.events.size)
        assertTrue(links.load(PRIMARY_CALENDAR_ID).isEmpty())
    }

    @Test
    fun previewKeysRoundTripIntoApply() = runTest {
        val google = memory(System.GOOGLE, event("g1", "Обзор"), event("g2", "Обед"))
        val teams = memory(System.TEAMS)
        val svc = service(FakeCalendarSource(google, teams))
        val preview = svc.preview()

        val report = svc.applySelected(preview, listOf("google:g2>teams"))

        assertEquals(listOf("Обед"), teams.events.map { it.title })
        assertEquals(1, report.totals.created)
        assertEquals(RouteCounts(created = 1), report.routes[Route(System.GOOGLE, System.TEAMS)]?.counts)
        // Снятая галочка у g1 — отказ от копирования (ADR 0003)
        assertEquals(
            listOf(Link.refusal(System.GOOGLE, "g1", System.TEAMS), Link(System.GOOGLE, "g2", System.TEAMS, teams.events.single().id)),
            links.load(PRIMARY_CALENDAR_ID).map { it.copy(sourceFields = null, targetFields = null) },
        )
    }

    @Test
    fun refusedCreateIsListedAndNotProposedByAnyRun() = runTest {
        val google = memory(System.GOOGLE, event("g1", "Обзор"))
        val teams = memory(System.TEAMS)
        val svc = service(FakeCalendarSource(google, teams))
        val first = svc.preview()
        val create = first.actions.single()
        assertTrue(create.remembersRefusal) // без галочки запомнится отказ
        assertFalse(create.isQuestion) // но это не вопрос: фон создание выполняет
        assertEquals(0, first.openQuestions)
        assertTrue(first.refused.isEmpty())

        svc.applySelected(first, emptyList())

        assertTrue(teams.events.isEmpty())
        assertEquals(listOf(Link.refusal(System.GOOGLE, "g1", System.TEAMS)), links.load(PRIMARY_CALENDAR_ID))
        val background = svc.runBackground()
        assertEquals(0, background.run.totals.created)
        val again = svc.preview()
        assertTrue(again.actions.isEmpty())
        val refused = again.refused.single()
        assertEquals("google:g1>teams", refused.key)
        assertEquals(ActionKind.CREATE, refused.kind)
        assertEquals("Обзор", refused.title)
        assertFalse(refused.selected)
        assertFalse(refused.key in again.defaultKeys)
    }

    @Test
    fun refusalTickedInPreviewCreatesTheCopy() = runTest {
        links.save(PRIMARY_CALENDAR_ID, Link.refusal(System.GOOGLE, "g1", System.TEAMS))
        val teams = memory(System.TEAMS)
        val svc = service(FakeCalendarSource(memory(System.GOOGLE, event("g1", "Обзор")), teams))
        val preview = svc.preview()

        val report = svc.applySelected(preview, listOf(preview.refused.single().key))

        assertEquals(1, report.totals.created)
        assertEquals(listOf("Обзор"), teams.events.map { it.title })
        val link = links.load(PRIMARY_CALENDAR_ID).single()
        assertEquals(teams.events.single().id, link.targetId)
        assertFalse(link.kept)
        assertTrue(svc.preview().refused.isEmpty())
    }

    @Test
    fun undoRefusalBringsTheCreateBack() = runTest {
        links.save(PRIMARY_CALENDAR_ID, Link.refusal(System.GOOGLE, "g1", System.TEAMS))
        val teams = memory(System.TEAMS)
        val svc = service(FakeCalendarSource(memory(System.GOOGLE, event("g1", "Обзор")), teams))
        val preview = svc.preview()

        assertFalse(svc.undoRefusal(preview, "google:g9>teams"))
        assertTrue(svc.undoRefusal(preview, "google:g1>teams"))

        assertTrue(links.load(PRIMARY_CALENDAR_ID).isEmpty())
        assertTrue(teams.events.isEmpty()) // копия — только в следующем запуске
        assertEquals(listOf("google:g1>teams"), svc.preview().defaultKeys.toList())
        svc.runBackground()
        assertEquals(listOf("Обзор"), teams.events.map { it.title })
    }

    @Test
    fun timeTwinQuestionIsUntickedAndBackgroundLeavesIt() = runTest {
        val lotus = event("d1", "AI Agent Platform: status update (incl. demo of progress)")
        val foreign = event("t1", "AI Agent Platform: status update (дублирую в тимсе ...)")
        val domino = memory(System.DOMINO, lotus)
        val teams = memory(System.TEAMS, foreign)
        settings.save(settings.load().copy(direction = "domino-teams"))
        val svc = service(FakeCalendarSource(memory(System.GOOGLE), domino, teams))

        val preview = svc.preview()
        val twin = preview.actions.single()
        assertEquals(ActionKind.TWIN, twin.kind)
        assertFalse(twin.selected)
        assertTrue(twin.isQuestion)
        assertTrue(twin.key !in preview.defaultKeys)

        val background = svc.runBackground()
        assertEquals(1, background.openQuestions)
        assertEquals(1, domino.events.size)
        assertEquals(1, teams.events.size)
        assertTrue(links.load(PRIMARY_CALENDAR_ID).isEmpty())

        // Ручное «да»: двойники, копий нет
        val manual = svc.preview()
        svc.applySelected(manual, listOf(manual.actions.single().key))
        assertEquals(LinkKind.TWIN, links.load(PRIMARY_CALENDAR_ID).single().kind)
        assertTrue(svc.preview().actions.isEmpty())
    }

    @Test
    fun aPreviewIsAppliedOnlyOnce() = runTest {
        val svc = service(FakeCalendarSource(memory(System.GOOGLE, event("g1", "Обзор")), memory(System.TEAMS)))
        val preview = svc.preview()
        svc.applySelected(preview, preview.defaultKeys)
        assertFailsWith<IllegalStateException> { svc.applySelected(preview, preview.defaultKeys) }
    }

    @Test
    fun manualUntickedQuestionIsRememberedAsKept() = runTest {
        val google = memory(System.GOOGLE, event("g1", "Обзор"))
        copyGoneQuestion(google)
        val svc = service(FakeCalendarSource(google, memory(System.TEAMS)))
        val preview = svc.preview()

        val q = preview.actions.single()
        assertEquals(ActionKind.DELETE, q.kind)
        assertEquals(DeleteSubject.ORIGINAL, q.subject)
        assertTrue(q.isQuestion)
        assertTrue(q.remembersRefusal)
        assertFalse(q.key in preview.defaultKeys)
        assertEquals(1, preview.openQuestions)

        svc.applySelected(preview, emptyList())

        assertEquals(listOf("Обзор"), google.events.map { it.title }) // оригинал оставлен
        assertTrue(links.load(PRIMARY_CALENDAR_ID).single().kept)
    }

    @Test
    fun manualTickedQuestionDeletesOriginal() = runTest {
        val google = memory(System.GOOGLE, event("g1", "Обзор"))
        copyGoneQuestion(google)
        val svc = service(FakeCalendarSource(google, memory(System.TEAMS)))
        val preview = svc.preview()
        svc.applySelected(preview, listOf(preview.actions.single().key))
        assertTrue(google.events.isEmpty())
        assertTrue(links.load(PRIMARY_CALENDAR_ID).isEmpty())
    }

    @Test
    fun manualUntickedTwinQuestionRemembersDistinctAndCopies() = runTest {
        val google = memory(System.GOOGLE, event("g1", "Обзор"))
        val teams = memory(System.TEAMS, event("t1", "обзор"))
        val svc = service(FakeCalendarSource(google, teams))
        val preview = svc.preview()
        val twin = preview.actions.single()
        assertEquals(ActionKind.TWIN, twin.kind)
        assertTrue(twin.isQuestion)
        assertTrue(twin.key in preview.defaultKeys) // по умолчанию «да», как в веб-версии

        svc.applySelected(preview, emptyList())

        assertEquals(LinkKind.DISTINCT, links.load(PRIMARY_CALENDAR_ID).first { it.kind != LinkKind.COPY }.kind)
        assertEquals(2, teams.events.size) // скопировали как обычно
    }

    @Test
    fun backgroundNeverRemembersKeptOrDistinct() = runTest {
        val google = memory(System.GOOGLE, event("g1", "Обзор"), event("g5", "Обед"))
        copyGoneQuestion(google)
        val teams = memory(System.TEAMS, event("t7", "ОБЕД"))
        val svc = service(FakeCalendarSource(google, teams))

        val report = svc.runBackground()

        // вопросы: удалить оригинал g1 и «это одна встреча?» Обед/ОБЕД
        assertEquals(2, report.openQuestions)
        assertEquals(listOf("Обзор", "Обед"), google.events.map { it.title })
        assertEquals(listOf("ОБЕД"), teams.events.map { it.title })
        val stored = links.load(PRIMARY_CALENDAR_ID)
        assertTrue(stored.none { it.kept || it.kind == LinkKind.DISTINCT || it.kind == LinkKind.TWIN }, "$stored")

        // вопросы остаются открытыми для ручного запуска
        val preview = svc.preview()
        assertEquals(2, preview.openQuestions)
    }

    @Test
    fun backgroundAppliesDefaultSelectedNonQuestions() = runTest {
        val google = memory(System.GOOGLE, event("g1", "Обзор"))
        val teams = memory(System.TEAMS)
        val report = service(FakeCalendarSource(google, teams)).runBackground()
        assertEquals(0, report.openQuestions)
        assertEquals(1, report.run.totals.created)
        assertEquals(listOf("Обзор"), teams.events.map { it.title })
    }

    @Test
    fun backgroundSkipsUpdatesThatWouldMailAttendees() = runTest {
        val old = CalendarEvent("Обзор", at("2026-11-14T10:00:00Z"), at("2026-11-14T11:00:00Z"), id = "g1")
        val google = memory(System.GOOGLE, old.copy(hasAttendees = true))
        val teams = memory(System.TEAMS, old.copy(id = "t1", source = System.GOOGLE, title = "Обзор 2"))
        links.save(PRIMARY_CALENDAR_ID, Link(System.GOOGLE, "g1", System.TEAMS, "t1", sourceFields = old.fields(), targetFields = old.fields()))
        val report = service(FakeCalendarSource(google, teams)).runBackground()
        assertEquals(1, report.openQuestions)
        assertEquals("Обзор", google.events.single().title)
    }

    @Test
    fun unauthorizedDominoDisablesLotusAndTheRestContinues() = runTest {
        val domino = UnauthorizedDomino()
        val google = memory(System.GOOGLE, event("g1", "Обзор"))
        val teams = memory(System.TEAMS)
        val source = FakeCalendarSource(google, domino, teams)

        val report = service(source).runBackground()

        assertTrue(report.run.dominoDisabledNow)
        assertTrue(settings.load().dominoDisabled)
        assertEquals(1, domino.calls) // без повторов
        assertEquals(listOf("Обзор"), teams.events.map { it.title })
        assertEquals(1, report.run.totals.created)

        // следующий запуск Lotus не трогает и сообщает об этом
        val next = service(source).runBackground()
        assertEquals(1, domino.calls)
        assertFalse(next.run.dominoDisabledNow)
        assertTrue(SkippedSystem(System.DOMINO, SkipReason.DOMINO_DISABLED) in next.run.skipped)
    }

    @Test
    fun unauthorizedDominoInPreviewIsReportedToo() = runTest {
        val domino = UnauthorizedDomino()
        val preview = service(FakeCalendarSource(memory(System.GOOGLE, event("g1", "Обзор")), domino, memory(System.TEAMS))).preview()
        assertTrue(preview.dominoDisabledNow)
        assertTrue(settings.load().dominoDisabled)
        assertEquals(setOf(System.GOOGLE, System.TEAMS), preview.systems)
        assertEquals(1, domino.calls)
    }

    @Test
    fun enteringDominoPasswordEnablesLotusAgain() = runTest {
        val secrets = InMemorySecretStore()
        settings.save(settings.load().copy(dominoDisabled = true))
        saveDominoCredentials(settings, secrets, "https://mail.example", "user", password = null)
        assertTrue(settings.load().dominoDisabled) // без пароля — по-прежнему выключен
        saveDominoCredentials(settings, secrets, "https://mail.example", "user", password = "secret")
        assertFalse(settings.load().dominoDisabled)
        assertEquals("secret", secrets.dominoPassword())
        assertEquals("user", settings.load().dominoUser)
    }

    @Test
    fun routesLimitSystemsAndActions() = runTest {
        settings.save(settings.load().copy(routes = setOf(Route(System.GOOGLE, System.TEAMS))))
        val source = FakeCalendarSource(memory(System.GOOGLE, event("g1", "Обзор")), memory(System.DOMINO), memory(System.TEAMS, event("t1", "Планёрка", start = "2026-11-14T12:00:00Z", end = "2026-11-14T13:00:00Z")))
        val preview = service(source).preview()
        assertEquals(setOf(System.GOOGLE, System.TEAMS), source.opened.single())
        assertEquals(listOf("google:g1>teams"), preview.actions.map { it.key })
    }

    @Test
    fun windowComesFromSettings() = runTest {
        settings.save(settings.load().copy(daysBack = 2, daysForward = 7))
        val preview = service(FakeCalendarSource(memory(System.GOOGLE), memory(System.TEAMS))).preview()
        assertEquals("2026-11-11T08:00:00Z", preview.window.start.toString())
        assertEquals("2026-11-20T08:00:00Z", preview.window.end.toString())
    }

    @Test
    fun linksAreScopedToTheCurrentGoogleCalendar() = runTest {
        settings.save(settings.load().copy(googleCalendarId = "notes@group"))
        val google = memory(System.GOOGLE, event("g1", "Обзор"))
        val teams = memory(System.TEAMS)
        val svc = service(FakeCalendarSource(google, teams))
        svc.applySelected(svc.preview(), listOf("google:g1>teams"))
        assertEquals(1, links.load("notes@group").size)
        assertTrue(links.load(PRIMARY_CALENDAR_ID).isEmpty())
    }

    @Test
    fun googleSwitchOffersToDeleteOldCopies() = runTest {
        val google = memory(System.GOOGLE, event("c1", "Обзор", source = System.DOMINO), event("g1", "Своё"))
        links.save(PRIMARY_CALENDAR_ID, Link(System.DOMINO, "d1", System.GOOGLE, "c1"))
        links.save(PRIMARY_CALENDAR_ID, Link(System.DOMINO, "d2", System.TEAMS, "t2"))
        val svc = service(FakeCalendarSource(google))

        assertEquals(GoogleSwitchPreview(current = PRIMARY_CALENDAR_ID, oldCopies = 1), svc.googleSwitchPreview("notes@group"))
        assertEquals(0, svc.googleSwitchPreview(PRIMARY_CALENDAR_ID).oldCopies)

        val result = svc.switchGoogleCalendar("notes@group", deleteOld = true)

        assertEquals(GoogleSwitchResult(deleted = 1, errors = emptyList()), result)
        assertEquals(listOf("Своё"), google.events.map { it.title })
        assertEquals("notes@group", settings.load().googleCalendarId)
        assertEquals(listOf("domino:d2->teams"), links.load(PRIMARY_CALENDAR_ID).map { it.key() })
    }

    @Test
    fun googleSwitchCanKeepOldCopies() = runTest {
        val google = memory(System.GOOGLE, event("c1", "Обзор", source = System.DOMINO))
        links.save(PRIMARY_CALENDAR_ID, Link(System.DOMINO, "d1", System.GOOGLE, "c1"))
        val result = service(FakeCalendarSource(google)).switchGoogleCalendar("notes@group", deleteOld = false)
        assertEquals(0, result.deleted)
        assertEquals(1, google.events.size)
        assertEquals(1, links.load(PRIMARY_CALENDAR_ID).size) // связи старого календаря остаются при нём
        assertEquals("notes@group", settings.load().googleCalendarId)
    }

    @Test
    fun googleSwitchWithoutGoogleSignInFailsWhenCopiesMustGo() = runTest {
        links.save(PRIMARY_CALENDAR_ID, Link(System.DOMINO, "d1", System.GOOGLE, "c1"))
        assertFailsWith<SyncSetupException> { service(FakeCalendarSource()).switchGoogleCalendar("notes@group", deleteOld = true) }
        assertEquals(PRIMARY_CALENDAR_ID, settings.load().googleCalendarId)
    }

    @Test
    fun noCalendarsMeansNothingToDo() = runTest {
        val preview = service(FakeCalendarSource()).preview()
        assertTrue(preview.actions.isEmpty())
        assertEquals(3, preview.skipped.size)
        assertNull(preview.routes.firstOrNull())
        assertNotNull(preview.window)
    }
}
