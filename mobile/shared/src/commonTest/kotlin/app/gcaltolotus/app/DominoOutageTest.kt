package app.gcaltolotus.app

import app.gcaltolotus.adapters.domino.DominoHttpException
import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.InMemoryCalendar
import app.gcaltolotus.sync.Link
import app.gcaltolotus.sync.LinkKind
import app.gcaltolotus.sync.System
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

/**
 * Domino недоступен по причине, отличной от 401 (сеть, таймаут, 5xx): Lotus пропускается только в
 * этом запуске, к Domino в нём больше не обращаемся, Google ↔ Teams продолжаются.
 */
class DominoOutageTest {
    private val settings = InMemorySettingsStore(SyncSettings(direction = "all"))
    private val links = InMemoryLinkRepository()

    private fun service(source: CalendarSource) = SyncService(settings, source, links, FixedClock)

    private fun timeout() = IOException("connect timed out")

    @Test
    fun backgroundSkipsLotusForThisRunWhenDominoIsUnreachable() = runTest {
        val domino = FlakyDomino { timeout() }
        val google = memory(System.GOOGLE, event("g1", "Обзор"))
        val teams = memory(System.TEAMS)
        val source = FakeCalendarSource(google, domino, teams)

        val report = service(source).runBackground()

        assertEquals("connect timed out", report.run.dominoUnavailable)
        assertFalse(report.run.dominoDisabledNow)
        assertFalse(settings.load().dominoDisabled)
        assertNull(settings.load().lastDominoSuccess)
        assertEquals(listOf("list"), domino.calls) // больше в этом запуске не обращаемся
        assertEquals(listOf("Обзор"), teams.events.map { it.title })
        assertEquals(1, report.run.totals.created)
        assertTrue(report.run.errors.isEmpty(), "${report.run.errors}")

        // следующий запуск снова пробует Domino
        service(source).runBackground()
        assertEquals(listOf("list", "list"), domino.calls)
    }

    @Test
    fun previewShowsUnreachableDominoAndApplyDoesNotCallIt() = runTest {
        val domino = FlakyDomino { if (it == "list") DominoHttpException(503, "GET", "https://mail.example/api", "busy") else null }
        val google = memory(System.GOOGLE, event("g1", "Обзор"))
        val teams = memory(System.TEAMS)
        val svc = service(FakeCalendarSource(google, domino, teams))

        val preview = svc.preview()

        val text = assertNotNull(preview.dominoUnavailable)
        assertTrue("503" in text, text)
        assertFalse(preview.dominoDisabledNow)
        assertEquals(setOf(System.GOOGLE, System.TEAMS), preview.systems)
        assertEquals(listOf("google:g1>teams"), preview.actions.map { it.key })

        val report = svc.applySelected(preview, preview.defaultKeys)
        assertEquals(text, report.dominoUnavailable)
        assertEquals(listOf("list"), domino.calls)
        assertFalse(settings.load().dominoDisabled)
    }

    @Test
    fun serverErrorWhileCheckingLinksSkipsLotusAndRereadsTheRest() = runTest {
        // связь с событием Domino вне списка: collect спросит его по id
        links.save(PRIMARY_CALENDAR_ID, Link(System.GOOGLE, "g1", System.DOMINO, "d9"))
        val domino = FlakyDomino { if (it == "get") DominoHttpException(502, "GET", "https://mail.example/d9", "") else null }
        val google = memory(System.GOOGLE, event("g1", "Обзор"), event("g2", "Обед"))
        val teams = memory(System.TEAMS)

        val report = service(FakeCalendarSource(google, domino, teams)).runBackground()

        assertNotNull(report.run.dominoUnavailable)
        assertEquals(listOf("list", "get"), domino.calls)
        assertEquals(listOf("Обзор", "Обед"), google.events.map { it.title }) // ничего не удалено
        // g1 связан с Lotus: пока Domino недоступен, его группа заморожена и в Teams не копируется
        assertEquals(listOf("Обед"), teams.events.map { it.title })
        assertFalse(settings.load().dominoDisabled)
        assertNull(settings.load().lastDominoSuccess)
    }

    @Test
    fun networkErrorWhileApplyingStopsFurtherDominoCalls() = runTest {
        val domino = FlakyDomino { if (it == "create") timeout() else null }
        val google = memory(System.GOOGLE, event("g1", "Обзор"), event("g2", "Обед"))
        val teams = memory(System.TEAMS)

        val report = service(FakeCalendarSource(google, domino, teams)).runBackground()

        assertEquals(listOf("list", "create"), domino.calls)
        assertEquals("connect timed out", report.run.dominoUnavailable)
        assertEquals(2, report.run.routes.getValue(Route(System.GOOGLE, System.DOMINO)).errors.size)
        assertEquals(2, teams.events.size) // Google → Teams выполнен
        assertFalse(settings.load().dominoDisabled)
        // Domino прочитан — значит, в этом запуске он работал
        assertEquals(NOW, settings.load().lastDominoSuccess)
    }

    @Test
    fun clientErrorOnOneEventIsNotAnOutage() = runTest {
        var creates = 0
        val domino = FlakyDomino {
            if (it == "create" && ++creates == 1) DominoHttpException(400, "POST", "https://mail.example/events", "bad") else null
        }
        val google = memory(System.GOOGLE, event("g1", "Обзор"), event("g2", "Обед"))

        val report = service(FakeCalendarSource(google, domino)).runBackground()

        assertNull(report.run.dominoUnavailable)
        assertEquals(listOf("list", "create", "create"), domino.calls)
        assertEquals(RouteCounts(created = 1), report.run.routes.getValue(Route(System.GOOGLE, System.DOMINO)).counts)
        assertEquals(1, report.run.errors.size)
    }

    @Test
    fun workingDominoRecordsLastSuccess() = runTest {
        service(FakeCalendarSource(memory(System.GOOGLE), memory(System.DOMINO))).preview()
        assertEquals(NOW, settings.load().lastDominoSuccess)
    }

    @Test
    fun unauthorizedDominoDoesNotRecordSuccessNorUnavailability() = runTest {
        val report = service(FakeCalendarSource(memory(System.GOOGLE), UnauthorizedDomino())).runBackground()
        assertTrue(report.run.dominoDisabledNow)
        assertNull(report.run.dominoUnavailable)
        assertNull(settings.load().lastDominoSuccess)
    }

    @Test
    fun unreachableDominoKeepsPreviousSuccess() = runTest {
        val before = NOW - 3.hours
        settings.save(settings.load().copy(lastDominoSuccess = before))
        service(FakeCalendarSource(memory(System.GOOGLE), FlakyDomino { timeout() })).runBackground()
        assertEquals(before, settings.load().lastDominoSuccess)
    }

    /**
     * Живой случай: у двойников Lotus ↔ Teams в Google уже есть одна [LN]-копия (от Lotus). Пока
     * Domino недоступен, оригиналы Teams из таких групп не копируются в Google, иначе со следующим
     * удачным запуском они станут лишними копиями и будут удалены.
     */
    private fun twinsWithLotus(): Triple<CalendarEvent, InMemoryCalendar, InMemoryCalendar> {
        val lotus = event("d1", "AI Agent Platform: status update")
        val meeting = event("t1", "AI Agent Platform (дублирую в тимсе)")
        val lnCopy = event("g-ln", lotus.title, source = System.DOMINO)
        links.save(PRIMARY_CALENDAR_ID, Link(System.DOMINO, "d1", System.TEAMS, "t1", LinkKind.TWIN, lotus.fields(), meeting.fields()))
        links.save(PRIMARY_CALENDAR_ID, Link(System.DOMINO, "d1", System.GOOGLE, "g-ln", sourceFields = lotus.fields(), targetFields = lnCopy.fields()))
        val google = memory(System.GOOGLE, lnCopy)
        val teams = memory(System.TEAMS, meeting, event("t2", "Ретро", "2026-11-15T10:00:00Z", "2026-11-15T11:00:00Z"))
        return Triple(lotus, google, teams)
    }

    @Test
    fun backgroundWithFlakyDominoDoesNotCopyTwinsOfLotusToGoogle() = runTest {
        val (_, google, teams) = twinsWithLotus()
        val domino = FlakyDomino { IOException("Software caused connection abort") }

        val report = service(FakeCalendarSource(google, domino, teams)).runBackground()

        assertEquals("Software caused connection abort", report.run.dominoUnavailable)
        // только несвязанный t2; t1 — двойник Lotus, его группа заморожена
        assertEquals(listOf(System.DOMINO to "AI Agent Platform: status update", System.TEAMS to "Ретро"),
            google.events.map { it.source to it.title })
        assertEquals(RouteCounts(created = 1), report.run.totals)
        assertEquals(listOf("t1", "t2"), teams.events.map { it.id })
        assertEquals(0, report.openQuestions)
    }

    @Test
    fun lotusDisabledAfter401IsUnavailableNotExcluded() = runTest {
        val (_, google, teams) = twinsWithLotus()

        val report = service(FakeCalendarSource(google, UnauthorizedDomino(), teams)).runBackground()

        assertTrue(report.run.dominoDisabledNow)
        assertEquals(listOf(System.DOMINO to "AI Agent Platform: status update", System.TEAMS to "Ретро"),
            google.events.map { it.source to it.title })

        // и в следующих запусках, пока пароль не введён заново
        val next = service(FakeCalendarSource(google, UnauthorizedDomino(), teams)).runBackground()
        assertEquals(RouteCounts(), next.run.totals)
        assertEquals(2, google.events.size)
    }

    @Test
    fun directionWithoutLotusStillCopiesItsTwins() = runTest {
        // Lotus исключён направлением, а не недоступен: поведение прежнее
        settings.save(settings.load().copy(direction = "google-teams"))
        val (_, google, teams) = twinsWithLotus()

        service(FakeCalendarSource(google, FlakyDomino { timeout() }, teams)).runBackground()

        assertEquals(setOf("AI Agent Platform (дублирую в тимсе)", "Ретро"),
            google.events.filter { it.source == System.TEAMS }.map { it.title }.toSet())
    }

    @Test
    fun notifyOnlyAfterADayWithoutDomino() {
        val failed = RunReport(emptyMap(), emptyList(), dominoDisabledNow = false, warnings = emptyList(), dominoUnavailable = "timeout")
        val fine = failed.copy(dominoUnavailable = null)
        fun s(hoursAgo: Int?) = SyncSettings(lastDominoSuccess = hoursAgo?.let { NOW - it.hours })

        assertFalse(shouldNotifyDominoUnreachable(failed, s(23), NOW))
        assertFalse(shouldNotifyDominoUnreachable(failed, s(24), NOW))
        assertTrue(shouldNotifyDominoUnreachable(failed, s(25), NOW))
        assertFalse(shouldNotifyDominoUnreachable(fine, s(25), NOW)) // в этот раз Domino ответил
        assertFalse(shouldNotifyDominoUnreachable(failed, s(null), NOW)) // ни разу не работал: не с чем сравнить
        assertTrue(shouldNotifyDominoUnreachable(failed, s(3), NOW, after = 2.hours))
    }
}
