package app.gcaltolotus.adapters.teams

import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.When
import app.gcaltolotus.sync.Window
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Текст фикстуры из tests/fixtures (путь передаёт Gradle, свойство gcaltolotus.fixturesDir). */
private fun fixtureText(name: String): String {
    val dir = java.lang.System.getProperty("gcaltolotus.fixturesDir")
        ?: error("Нет системного свойства gcaltolotus.fixturesDir (задаётся в shared/build.gradle.kts)")
    return File(dir, name).readText()
}

/** Порт tests/test_teams_adapter.py: тесты на фикстурах graph_calendarview_page*.json. */
class TeamsCalendarFixturesTest {
    private val page1 = fixtureText("graph_calendarview_page1.json")
    private val page2 = fixtureText("graph_calendarview_page2.json")
    private val pages = listOf(page1, page2).map { Json.parseToJsonElement(it).jsonObject }
    private val raws = pages.flatMap { page -> page["value"]!!.jsonArray.map { it.jsonObject } }
    private val rawById = raws.associateBy { it["id"]!!.jsonPrimitive.content }
    private val window = Window(Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"))

    private fun at(text: String) = When.Timed(Instant.parse(text))

    private fun calendar(http: MockHttp) =
        TeamsCalendar(http.client, accessToken = { "T" }, refreshAccessToken = { "T" })

    @Test
    fun timedEventIsConverted() {
        val event = eventFromGraph(raws[0])

        assertEquals("Design review", event.title)
        assertEquals(at("2026-11-13T22:00:00Z"), event.start)
        assertEquals(at("2026-11-13T22:45:00Z"), event.end)
        assertEquals(Triple("Teams Meeting", "Agenda", null), Triple(event.location, event.description, event.source))
    }

    @Test
    fun allDayEventKeepsDates() {
        val event = eventFromGraph(raws[1])

        assertTrue(event.allDay)
        assertEquals(When.AllDay(LocalDate(2026, 11, 20)), event.start)
        assertEquals(When.AllDay(LocalDate(2026, 11, 22)), event.end)
    }

    @Test
    fun nonUtcTimeIsRejected() {
        val raw = JsonObject(
            raws[0] + ("start" to buildJsonObject {
                put("dateTime", "2026-11-13T10:00:00.0000000")
                put("timeZone", "Russian Standard Time")
            }),
        )

        assertFailsWith<IllegalArgumentException> { eventFromGraph(raw) }
    }

    @Test
    fun listFollowsNextLinkAndMarksCancelledIgnored() = runTest {
        val http = MockHttp(ok(page1), ok(page2))

        val listed = calendar(http).list(window)

        assertEquals(
            listOf(
                "AAMkAGI1-timed", "AAMkAGI1-allday", "AAMkAGI1-occurrence",
                "AAMkAGI1-copy", "AAMkAGI1-cancelled", "AAMkAGI1-hidden",
            ),
            listed.map { it.id },
        )
        assertEquals(System.GOOGLE, listed[3].source)
        assertEquals(listOf("AAMkAGI1-occurrence", "AAMkAGI1-cancelled"), listed.filter { it.ignored }.map { it.id })

        val (first, second) = http.requests
        assertEquals("/v1.0/me/calendarView", first.url.encodedPath)
        assertEquals("graph.microsoft.com", first.url.host)
        assertEquals("2026-11-01T00:00:00Z", first.url.parameters["startDateTime"])
        val expand = first.url.parameters["\$expand"]!!
        assertTrue(ORIGIN_PROP in expand && ORIGIN_ID_PROP in expand)
        assertTrue("outlook.timezone=\"UTC\"" in first.headers["Prefer"]!!)
        assertTrue("IdType=\"ImmutableId\"" in first.headers["Prefer"]!!)

        // Вторая страница — ровно nextLink, без добавленных параметров.
        assertEquals("/v1.0/me/calendarView", second.url.encodedPath)
        assertEquals("100", second.url.parameters["\$skip"])
        assertEquals("id,subject", second.url.parameters["\$select"])
        assertEquals("2026-11-01T00:00:00Z", second.url.parameters["startDateTime"])
        assertNull(second.url.parameters["\$expand"])
        assertTrue("IdType=\"ImmutableId\"" in second.headers["Prefer"]!!)
    }

    @Test
    fun listSkipsEventsThatCannotBeConverted() = runTest {
        val broken = JsonObject(raws[0] + ("start" to buildJsonObject { put("dateTime", "x"); put("timeZone", "Pacific") }))
        val page = buildJsonObject { put("value", kotlinx.serialization.json.JsonArray(listOf(broken, raws[1]))) }
        val log = mutableListOf<String>()
        val http = MockHttp(ok(page.toString()))

        val listed = TeamsCalendar(http.client, { "T" }, { "T" }, log = { log += it }).list(window)

        assertEquals(listOf("AAMkAGI1-allday"), listed.map { it.id })
        assertTrue(log.single().contains("AAMkAGI1-timed"))
    }

    @Test
    fun nativeEventMetadata() {
        val event = eventFromGraph(rawById.getValue("AAMkAGI1-timed"))

        assertEquals("040000008200E00074C5B7101A82E00800000000timed", event.uid)
        assertEquals(Instant.parse("2026-11-10T08:15:30.123456Z"), event.modified)
        assertTrue(event.organizerIsMe)
        assertFalse(event.hasAttendees)
        assertFalse(event.isPrivate || event.ignored)
        assertNull(event.originalStart)
    }

    @Test
    fun sensitivityIsPrivate() {
        assertTrue(eventFromGraph(rawById.getValue("AAMkAGI1-allday")).isPrivate)
        assertTrue(eventFromGraph(rawById.getValue("AAMkAGI1-occurrence")).isPrivate) // confidential
    }

    @Test
    fun declinedForeignOccurrence() {
        val event = eventFromGraph(rawById.getValue("AAMkAGI1-occurrence"))

        assertTrue(event.ignored && !event.organizerIsMe && event.hasAttendees)
        assertEquals(at("2026-11-17T07:00:00Z"), event.originalStart)
        assertEquals("040000008200E00074C5B7101A82E00807EA0B11series", event.uid)
    }

    @Test
    fun htmlBodyBecomesPlainText() {
        assertEquals("Hello team\nLine&two\nThird", eventFromGraph(rawById.getValue("AAMkAGI1-occurrence")).description)
    }

    @Test
    fun hiddenMarkerWinsOverTitle() {
        val event = eventFromGraph(rawById.getValue("AAMkAGI1-hidden"))

        assertEquals("Renamed by user" to System.DOMINO, event.title to event.source)
    }

    @Test
    fun getReturnsEvent() = runTest {
        val http = MockHttp(ok(rawById.getValue("AAMkAGI1-timed").toString()))

        val event = calendar(http).get("AAMkAGI1-timed")

        assertEquals("Design review", event?.title)
        val request = http.requests.single()
        assertEquals("GET", request.method.value)
        assertEquals("/v1.0/me/events/AAMkAGI1-timed", request.url.encodedPath)
        assertTrue(ORIGIN_PROP in request.url.parameters["\$expand"]!!)
        assertTrue("IdType=\"ImmutableId\"" in request.headers["Prefer"]!!)
    }

    @Test
    fun getMissingOrCancelledIsNull() = runTest {
        val cancelled = ok(rawById.getValue("AAMkAGI1-cancelled").toString())
        for (reply in listOf(Reply(404), Reply(410), cancelled)) {
            assertNull(calendar(MockHttp(reply)).get("x"), "ответ $reply")
        }
    }

    @Test
    fun fixtureBodyWithCrLfIsStripped() {
        // "Agenda\r\n" -> "Agenda", как Python str.strip().
        assertEquals(JsonPrimitive("Agenda\r\n"), rawById.getValue("AAMkAGI1-timed")["body"]!!.jsonObject["content"])
        assertEquals("Agenda", eventFromGraph(rawById.getValue("AAMkAGI1-timed")).description)
    }
}
