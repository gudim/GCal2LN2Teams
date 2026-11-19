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

    // --- Копии без связи (созданы прежней установкой): «усыновление» и отмена оригинала ---

    private val pair = listOf(setOf(System.DOMINO, System.GOOGLE))
    private val lotus = CalendarEvent("Статус", start, end, id = "d1/20261113T100000Z")
    private val orphan = lotus.copy(id = "gc1", source = System.DOMINO)

    @Test
    fun unlinkedCopyIsAdoptedWithALinkInsteadOfCopiedAgain() {
        val plan = plan(mapOf(System.DOMINO to listOf(lotus), System.GOOGLE to listOf(orphan)), pair)
        val adopt = plan.actions.single()
        assertEquals(ActionKind.LINK, adopt.kind)
        assertTrue(adopt.silent)
        assertEquals(
            Link(System.DOMINO, "d1/20261113T100000Z", System.GOOGLE, "gc1", sourceFields = lotus.fields(), targetFields = orphan.fields()),
            adopt.link,
        )
        // со связью копия — обычная: в следующем плане ничего не происходит
        assertTrue(plan(mapOf(System.DOMINO to listOf(lotus), System.GOOGLE to listOf(orphan)), pair, listOf(adopt.link!!)).actions.isEmpty())
    }

    @Test
    fun linkedCopyIsNotAdoptedTwice() {
        val other = Link(System.DOMINO, "d9", System.GOOGLE, "gc1")
        val plan = plan(mapOf(System.DOMINO to listOf(lotus), System.GOOGLE to listOf(orphan)), pair, listOf(other))
        assertTrue(plan.actions.none { it.kind == ActionKind.LINK && it.link?.targetId == "gc1" && it.link?.sourceId == lotus.id })
    }

    @Test
    fun unlinkedCopyOfCancelledOriginalIsDeleted() {
        val cancelled = lotus.copy(ignored = true)
        val plan = plan(mapOf(System.DOMINO to listOf(cancelled), System.GOOGLE to listOf(orphan)), pair)
        val delete = plan.actions.single()
        assertEquals(ActionKind.DELETE, delete.kind)
        assertEquals(System.GOOGLE, delete.target)
        assertEquals("gc1", delete.event.id)
        assertEquals(DeleteSubject.COPY, delete.subject)
        assertTrue(delete.silent)
    }

    @Test
    fun unlinkedCopyStaysWhileALiveOriginalMatchesToo() {
        val cancelled = lotus.copy(id = "d2", ignored = true)
        val plan = plan(mapOf(System.DOMINO to listOf(lotus, cancelled), System.GOOGLE to listOf(orphan)), pair)
        assertTrue(plan.actions.none { it.kind == ActionKind.DELETE })
    }
}
