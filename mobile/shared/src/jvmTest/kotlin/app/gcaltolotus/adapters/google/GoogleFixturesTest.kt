package app.gcaltolotus.adapters.google

import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.Window
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Ответ API из tests/fixtures (путь передаёт Gradle, свойство gcaltolotus.fixturesDir). */
private fun googleFixture(name: String): String {
    val dir = java.lang.System.getProperty("gcaltolotus.fixturesDir")
        ?: error("Нет системного свойства gcaltolotus.fixturesDir (задаётся в shared/build.gradle.kts)")
    return File(dir, name).readText()
}

private val PAGE1 = googleFixture("google_events_page1.json")
private val PAGE2 = googleFixture("google_events_page2.json")

/** События обеих страниц по id. */
private val items: Map<String, JsonObject> = listOf(PAGE1, PAGE2)
    .flatMap { obj(it)["items"]!!.jsonArray }
    .map { it.jsonObject }
    .associateBy { it["id"]!!.jsonPrimitive.content }

private fun item(id: String) = eventFromGoogle(items.getValue(id))

/** Порт tests/test_google_adapter.py на фикстурах google_events_page*.json. */
class GoogleFixturesTest {
    @Test
    fun timedEventIsConvertedToUtc() {
        val event = item("g-late")

        assertEquals("Release night", event.title)
        assertEquals(at("2026-11-13T22:00:00Z"), event.start)
        assertEquals(at("2026-11-13T23:30:00Z"), event.end)
        assertEquals(listOf("Room 1", "Deploy", null, "g-late"), listOf(event.location, event.description, event.source, event.id))
    }

    @Test
    fun allDayEventKeepsDates() {
        val event = item("g-allday")

        assertTrue(event.allDay)
        assertEquals(day("2026-11-13") to day("2026-11-14"), event.start to event.end)
    }

    @Test
    fun markerBecomesSource() {
        val event = item("g-copy")

        assertEquals("Budget review" to System.DOMINO, event.title to event.source)
    }

    @Test
    fun nativeEventMetadata() {
        val event = item("g-late")

        assertEquals("g-late@google.com", event.uid)
        assertEquals(Instant.parse("2026-11-10T08:15:30.123Z"), event.modified)
        assertTrue(event.organizerIsMe)
        assertFalse(event.hasAttendees)
        assertFalse(event.isPrivate)
        assertFalse(event.ignored)
        assertNull(event.originalStart)
    }

    @Test
    fun privateVisibility() {
        assertTrue(item("g-allday").isPrivate)
        assertTrue(item("g-meeting_20261117T070000Z").isPrivate) // confidential
    }

    @Test
    fun serviceEventTypesAreIgnored() {
        assertTrue(item("g-birthday").ignored)
        assertTrue(item("g-wl").ignored)
        assertFalse(item("g-team").ignored) // focusTime — обычное событие
    }

    @Test
    fun declinedForeignInstance() {
        val event = item("g-meeting_20261117T070000Z")

        assertTrue(event.ignored)
        assertFalse(event.organizerIsMe)
        assertTrue(event.hasAttendees)
        assertEquals("g-meeting@google.com", event.uid)
        assertEquals(at("2026-11-17T07:00:00Z"), event.originalStart)
    }

    @Test
    fun ownMeetingWithGuestsHasAttendees() {
        val event = item("g-team")

        assertTrue(event.organizerIsMe)
        assertTrue(event.hasAttendees)
    }

    @Test
    fun hiddenMarkerWinsOverTitle() {
        val event = item("g-hidden-copy")

        assertEquals("Standup renamed by user" to System.TEAMS, event.title to event.source)
    }

    @Test
    fun cancelledInstanceWithoutTimesIsIgnored() {
        val event = item("g-gone")

        assertTrue(event.ignored)
        assertEquals("g-gone", event.id)
    }

    @Test
    fun listReadsAllPagesWithinWindow() = runTest {
        val google = FakeGoogle { s ->
            when (s.url.parameters["pageToken"]) {
                null -> ok(PAGE1)
                "page-2" -> ok(PAGE2)
                else -> Reply(HttpStatusCode.BadRequest)
            }
        }
        val window = Window(Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"))

        val listed = google.adapter.list(window)

        assertEquals(
            listOf(
                "g-late", "g-allday", "g-birthday", "g-meeting_20261117T070000Z",
                "g-copy", "g-hidden-copy", "g-team", "g-wl", "g-gone",
            ),
            listed.map { it.id },
        )
        assertEquals("2026-11-01T00:00:00+00:00", google.seen[0].url.parameters["timeMin"])
        assertEquals("2026-12-01T00:00:00+00:00", google.seen[0].url.parameters["timeMax"])
        assertEquals("true", google.seen[0].url.parameters["singleEvents"])
        assertEquals("page-2", google.seen[1].url.parameters["pageToken"])
    }

    @Test
    fun getReturnsFixtureEvent() = runTest {
        val google = FakeGoogle { ok(items.getValue("g-late").toString()) }

        assertEquals("Release night", google.adapter.get("g-late")?.title)
    }
}
