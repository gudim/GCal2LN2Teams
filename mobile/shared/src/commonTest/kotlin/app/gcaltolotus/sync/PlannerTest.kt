package app.gcaltolotus.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** То, что эталоны не закрепляют напрямую; остальное поведение — в GoldenPlansTest. */
class PlannerTest {
    private val start = When.parse("2026-11-13T10:00:00Z")
    private val end = When.parse("2026-11-13T11:00:00Z")
    private val event = CalendarEvent("Обзор", start, end, id = "g1")

    @Test
    fun actionKeysAreFormattedLikePython() {
        assertEquals("google:g1>domino", Action(ActionKind.CREATE, System.DOMINO, event, System.GOOGLE).key)
        assertEquals(
            "google:Обзор@2026-11-13T10:00:00+00:00>teams",
            Action(ActionKind.CREATE, System.TEAMS, event.copy(id = null), System.GOOGLE).key,
        )
        assertEquals("delete:google:g1", Action(ActionKind.DELETE, System.GOOGLE, event, System.DOMINO).key)
        val twin = Link(System.GOOGLE, "g1", System.DOMINO, "d1", LinkKind.TWIN)
        assertEquals("twin:twin:google:g1->domino:d1", Action(ActionKind.TWIN, System.DOMINO, event, System.GOOGLE, link = twin).key)
        assertEquals("unlink:twin:google:g1->domino:d1", Action.bookkeeping(ActionKind.UNLINK, twin).key)
    }

    @Test
    fun bookkeepingActionsAreSilentAndHaveNoEventInJson() {
        val link = Link(System.GOOGLE, "g1", System.DOMINO, "d1")
        val json = SyncJson.actionToJson(Action.bookkeeping(ActionKind.LINK, link))
        assertEquals(JsonNull, json["event"])
        assertEquals(JsonPrimitive(true), json["silent"])
        assertEquals(JsonPrimitive("domino"), json["target"])
        assertEquals(JsonPrimitive(""), json["subject"])
    }

    @Test
    fun directionsExpandToPairs() {
        assertEquals(3, DIRECTIONS.getValue("all").size)
        assertEquals(listOf(setOf(System.DOMINO, System.TEAMS)), DIRECTIONS.getValue("domino-teams"))
    }

    @Test
    fun selectWithoutKeysSkipsUntickedAndDoesNotRunFallback() {
        val kept = Action.bookkeeping(ActionKind.LINK, Link(System.GOOGLE, "g1", System.DOMINO, "d1", kept = true))
        val question = Action(ActionKind.DELETE, System.GOOGLE, event, System.DOMINO, selected = false, fallback = listOf(kept))
        assertEquals(emptyList(), select(listOf(question)))
        assertEquals(listOf(kept), select(listOf(question), keys = emptyList()))
        assertEquals(listOf(question), select(listOf(question), keys = listOf(question.key)))
    }

    @Test
    fun applySkipsActionsWhoseDependencyDidNotRunAndDoesNotCountThem() = runTest {
        val calendars = mapOf(System.DOMINO to InMemoryCalendar(System.DOMINO))
        val store = MemoryLinkStore()
        val create = Action(ActionKind.CREATE, System.DOMINO, event.copy(source = System.GOOGLE), System.GOOGLE)
        val blocked = create.copy(event = event.copy(id = "g2", source = System.GOOGLE), dependsOn = listOf("нет:такого"))

        val report = apply(listOf(create, blocked), calendars, store)

        assertEquals(1, report.pair(System.GOOGLE, System.DOMINO).created)
        assertEquals(listOf("google:g1->domino"), store.links.map { it.key() })
        assertEquals(event.fields(), store.links.single().sourceFields)
    }

    @Test
    fun applyReportsErrorsPerRouteAndContinues() = runTest {
        val calendars = mapOf(System.TEAMS to GoldenCalendar(System.TEAMS, failures = listOf("create" to "Обзор")))
        val other = event.copy(id = "g2", title = "Другое", source = System.GOOGLE)
        val report = apply(
            listOf(
                Action(ActionKind.CREATE, System.TEAMS, event.copy(source = System.GOOGLE), System.GOOGLE),
                Action(ActionKind.CREATE, System.TEAMS, other, System.GOOGLE),
            ),
            calendars,
            MemoryLinkStore(),
        )
        val result = report.pair(System.GOOGLE, System.TEAMS)
        assertEquals(listOf("Обзор: $GOLDEN_FAILURE"), result.errors)
        assertEquals(1, result.created)
    }

    @Test
    fun stringsCompareByCodePointLikePython() {
        // U+FFFD (BMP) < U+1F600 (суррогатная пара), хотя по UTF-16 0xFFFD > 0xD83D.
        assertTrue(pyCompare("�", "😀") < 0)
        assertTrue(pyCompare("a", "ab") < 0)
        assertEquals(0, pyCompare("g1", "g1"))
    }
}
