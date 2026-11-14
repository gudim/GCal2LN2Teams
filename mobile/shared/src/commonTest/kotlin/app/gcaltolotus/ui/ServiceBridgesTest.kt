package app.gcaltolotus.ui

import app.gcaltolotus.app.BackgroundReport
import app.gcaltolotus.app.ConnectionCheck
import app.gcaltolotus.app.NOW
import app.gcaltolotus.app.Route
import app.gcaltolotus.app.RouteCounts
import app.gcaltolotus.app.RouteResult
import app.gcaltolotus.app.RunReport
import app.gcaltolotus.sync.System
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import app.gcaltolotus.adapters.google.GoogleCalendarInfo as ApiCalendarInfo

class ServiceBridgesTest {
    @Test
    fun connectionChecksMapToScreenResults() {
        assertEquals(CheckResult.Ok("Google: Работа"), ConnectionCheck.Ok("Google: Работа").toCheckResult())
        assertEquals(CheckResult.Ok(null), ConnectionCheck.Ok(" ").toCheckResult())
        assertEquals(CheckResult.AuthFailed("401"), ConnectionCheck.AuthFailed("401").toCheckResult())
        assertEquals(CheckResult.Unreachable("timeout"), ConnectionCheck.Unreachable("timeout").toCheckResult())
        assertEquals(
            CheckResult.NotConfigured("Вход в Teams не выполнен"),
            ConnectionCheck.NotConfigured("Вход в Teams не выполнен").toCheckResult(),
        )
    }

    @Test
    fun googleCalendarMapsToScreenInfo() {
        val reader = ApiCalendarInfo("c1", "", "", null, "reader", primary = false, selected = true)
        assertEquals(GoogleCalendarInfo("c1", "c1", primary = false, writable = false), reader.toUi())
        val owner = ApiCalendarInfo("me@x", "Я", "", "Europe/Moscow", "owner", primary = true, selected = true)
        assertEquals(GoogleCalendarInfo("me@x", "Я", primary = true, writable = true), owner.toUi())
    }

    private val outageReport = RunReport(
        routes = mapOf(Route(System.GOOGLE, System.TEAMS) to RouteResult(RouteCounts(created = 2), listOf("Обзор: HTTP 500"))),
        skipped = emptyList(),
        dominoDisabledNow = false,
        warnings = emptyList(),
        dominoUnavailable = "Domino: timeout",
    )

    @Test
    fun runsKeepDominoOutage() {
        val manual = LastRun.manual(outageReport, NOW, openQuestions = 1)
        assertEquals("Domino: timeout", manual.dominoUnavailable)
        assertEquals(setOf(System.DOMINO), manual.unavailable)

        val background = LastRun.background(BackgroundReport(outageReport, openQuestions = 3), NOW)
        assertEquals("Domino: timeout", background.dominoUnavailable)
        assertEquals(setOf(System.DOMINO), background.unavailable)
        assertEquals(3, background.openQuestions)

        val fine = LastRun.manual(outageReport.copy(dominoUnavailable = null), NOW, 0)
        assertEquals(emptySet(), fine.unavailable)
        assertNull(fine.dominoUnavailable)
    }

    @Test
    fun lastRunRoundTrips() {
        val runs = listOf(
            LastRun.background(BackgroundReport(outageReport, openQuestions = 3), NOW),
            LastRun(NOW, background = false),
            LastRun(
                NOW,
                background = true,
                totals = RouteCounts(1, 2, 3, 4),
                errors = 5,
                openQuestions = 6,
                dominoDisabledNow = true,
                unavailable = setOf(System.TEAMS, System.GOOGLE),
                failure = "Ошибка \"сети\"",
            ),
        )
        for (run in runs) assertEquals(run, LastRunCodec.decode(LastRunCodec.encode(run)))
    }

    @Test
    fun badOrForeignTextDecodesToNothingOrSkipsUnknown() {
        assertNull(LastRunCodec.decode(null))
        assertNull(LastRunCodec.decode(""))
        assertNull(LastRunCodec.decode("{не json"))
        assertNull(LastRunCodec.decode("""{"finishedAt":"вчера","background":true}"""))
        val future = LastRunCodec.decode(
            """{"finishedAt":"2026-11-14T10:00:00Z","background":true,"unavailable":["domino","outlook"],"newField":1}""",
        )
        assertEquals(setOf(System.DOMINO), future?.unavailable)
    }
}
