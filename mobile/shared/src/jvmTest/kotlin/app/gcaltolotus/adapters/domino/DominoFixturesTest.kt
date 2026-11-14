package app.gcaltolotus.adapters.domino

import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.System
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Фикстура из tests/fixtures (путь передаёт Gradle, свойство `gcaltolotus.fixturesDir`). */
internal fun fixture(name: String): JsonElement {
    val dir = java.lang.System.getProperty("gcaltolotus.fixturesDir")
        ?: error("Нет системного свойства gcaltolotus.fixturesDir (задаётся в shared/build.gradle.kts)")
    return Json.parseToJsonElement(File(dir, name).readText())
}

private const val A_ID = "A1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D6-Lotus_Notes_Generated"
private const val W_ID = "C1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D6-Lotus_Notes_Generated/20261117T070000Z"
private const val ME_ID = "1A2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D-Lotus_Notes_Generated"

/**
 * Тесты адаптера Domino на ответах по форме реального сервера (tests/fixtures/domino_*.json).
 * Порт tests/test_domino_adapter.py.
 */
class DominoFixturesTest {
    private val rawEvents: List<JsonObject> =
        fixture("domino_events.json").jsonObject.getValue("events").jsonArray.map { it.jsonObject }
    private val detailsFixture: JsonObject = fixture("domino_event_details.json").jsonObject

    /** Детальные ответы: из фикстуры, для остальных событий — сводка + пустое описание. */
    private fun details(): MutableMap<String, Any> {
        val result = mutableMapOf<String, Any>()
        for (raw in rawEvents) {
            val detail = JsonObject(
                raw.filterKeys { it != "x-lotus-summarydataonly" } +
                    mapOf("description" to JsonPrimitive(""), "last-modified" to JsonPrimitive("20261101T000000Z")),
            )
            result[SERVER + raw.str("href")] = JsonObject(mapOf("events" to JsonArray(listOf(detail))))
        }
        for ((href, body) in detailsFixture) result[SERVER + href] = body.jsonObject
        return result
    }

    // --- конвертация сводки ---

    @Test
    fun utcEventIsConverted() {
        val event = eventFromDomino(rawEvents[0], owner = OWNER)
        assertEquals("Budget review", event.title)
        assertEquals(utc("2026-11-13T22:00:00Z"), event.start)
        assertEquals(utc("2026-11-13T23:00:00Z"), event.end)
        assertEquals(Triple("Room 2", null, A_ID), Triple(event.location, event.source, event.id))
        assertEquals(A_ID, event.uid)
        assertTrue(event.organizerIsMe && !event.hasAttendees && !event.isPrivate)
        assertTrue(!event.ignored && event.originalStart == null)
    }

    @Test
    fun allDayEndIsExclusiveInList() {
        val event = eventFromDomino(rawEvents[1])
        assertTrue(event.allDay)
        assertEquals(date("2026-11-16") to date("2026-11-17"), event.start to event.end)
    }

    @Test
    fun recurringInstance() {
        val event = eventFromDomino(rawEvents[2])
        assertEquals(W_ID, event.id)
        assertEquals("C1B2C3D4E5F6A7B8C9D0E1F2A3B4C5D6-Lotus_Notes_Generated", event.uid)
        assertEquals(utc("2026-11-17T07:00:00Z"), event.originalStart)
    }

    @Test
    fun markerBecomesSource() {
        val event = eventFromDomino(rawEvents[3])
        assertEquals("Release night" to System.GOOGLE, event.title to event.source)
    }

    @Test
    fun tzidTimeWithoutDefinitionIsRejected() {
        assertFailsWith<IllegalArgumentException> { eventFromDomino(rawEvents[4]) }
    }

    @Test
    fun foreignPrivateMeeting() {
        val event = eventFromDomino(rawEvents[5], owner = OWNER)
        assertFalse(event.organizerIsMe)
        assertTrue(event.hasAttendees)
        assertTrue(event.isPrivate)
    }

    @Test
    fun ownMeetingOrganizerFormatsAreNormalized() {
        assertTrue(eventFromDomino(rawEvents[6], owner = OWNER).organizerIsMe)
    }

    @Test
    fun foreignOrganizerWithUnknownOwnerIsNotMe() {
        assertFalse(eventFromDomino(rawEvents[5]).organizerIsMe)
    }

    @Test
    fun cancelledInstanceIsIgnored() {
        assertTrue(eventFromDomino(rawEvents[7], owner = OWNER).ignored)
    }

    @Test
    fun tzidTimeIsConvertedWithTimezonesBlock() {
        val body = detailsFixture.getValue("/mail/user.nsf/api/calendar/events/$A_ID").jsonObject
        val raw = body.getValue("events").jsonArray[0].jsonObject

        val event = eventFromDomino(raw, owner = OWNER, timezones = body.getValue("timezones").jsonArray)

        assertEquals(utc("2026-11-13T22:00:00Z"), event.start) // FLE зимой = UTC+2
        assertEquals("Q4\nfigures", event.description)
        assertEquals(Instant.parse("2026-10-07T05:00:45Z"), event.modified)
    }

    @Test
    fun fixtureTimezonesMatchInlineCopy() {
        val body = detailsFixture.values.first().jsonObject
        assertEquals(FLE_TIMEZONES, body.getValue("timezones").jsonArray)
    }

    @Test
    fun connectReadsOwnerFromFixture() = runTest {
        val server = FakeDomino(calendar = fixture("domino_calendar.json").jsonObject)
        val info = server.calendar().connect()
        assertEquals(EVENTS_URL, info.eventsUrl)
        assertEquals(OWNER.copy(displayName = "Ivan Petrov"), info.owner)
    }

    // --- list ---

    @Test
    fun listMergesDetailsAndSkipsUnsupported() = runTest {
        val server = FakeDomino(rawEvents, details())
        val skipped = mutableListOf<String?>()

        val listed = server.calendar { raw, _ -> skipped += raw.str("id") }.list(WINDOW).associateBy { it.id }

        assertEquals(8, listed.size) // tzid-событие пропущено, две записи "Business trip" склеены
        assertEquals(listOf(rawEvents[4].str("id")), skipped)
        val budget = listed.getValue(A_ID)
        assertEquals(utc("2026-11-13T22:00:00Z"), budget.start) // время из сводки (UTC)
        assertEquals("Q4\nfigures", budget.description)
        assertEquals(Instant.parse("2026-10-07T05:00:45Z"), budget.modified)
        assertEquals("Standing agenda", listed.getValue(W_ID).description)
        assertEquals(utc("2026-11-17T07:00:00Z"), listed.getValue(W_ID).start)
        val mine = listed.getValue(ME_ID)
        assertTrue(mine.organizerIsMe && mine.hasAttendees)
        assertEquals("Line one\nLine two", mine.description)
        assertTrue(listed.values.filter { !it.ignored }.all { it.modified != null })
        val first = server.gets.first { it.url == EVENTS_URL }.params
        assertEquals("2026-10-31T21:00:00Z", first["since"])
        assertEquals("2026-11-30T21:00:00Z", first["before"])
        // детали запрошены по href, в том числе для экземпляра серии
        assertTrue("$SERVER/mail/user.nsf/api/calendar/events/$W_ID" in server.gets.map { it.url })
    }

    @Test
    fun multiDayAllDayEntriesAreMerged() = runTest {
        val listed = FakeDomino(rawEvents, details()).calendar().list(WINDOW)
        val trip = listed.filter { it.title == "Business trip" }
        assertEquals(1, trip.size)
        assertEquals(date("2026-11-23") to date("2026-11-25"), trip[0].start to trip[0].end)
    }

    @Test
    fun listSkipsDetailsOfIgnoredEntries() = runTest {
        // Реальный сервер: отменённые экземпляры и уведомления (x-lotus-unid) отдают 404 на детали.
        val cancelled = JsonObject(
            rawEvents[7] + ("href" to JsonPrimitive("/mail/user.nsf/api/calendar/events/C/20261126T080000Z")),
        )
        val notice = obj(
            """{"href": "/mail/user.nsf/api/calendar/events/N-Lotus_Notes_Generated",
                "id": "N-Lotus_Notes_Generated", "summary": "Invitation: Planning",
                "start": {"date": "2026-11-13", "time": "12:30:00", "utc": true},
                "end": {"date": "2026-11-13", "time": "13:30:00", "utc": true},
                "transparency": "transparent", "x-lotus-noticetype": {"data": "I"},
                "x-lotus-unid": {"data": "B1A3589DF68FA0F243258E74004F64B2"}}""",
        )
        val details = details()
        for (raw in listOf(cancelled, notice)) details[SERVER + raw.str("href")] = 404
        val server = FakeDomino(rawEvents + listOf(cancelled, notice), details)

        val listed = server.calendar().list(WINDOW)

        assertTrue(listed.associateBy { it.id }.getValue("N-Lotus_Notes_Generated").ignored)
        assertTrue(listed.filter { it.title == rawEvents[7].str("summary") }.all { it.ignored })
        val fetched = server.gets.map { it.url }
        assertFalse(SERVER + cancelled.str("href") in fetched)
        assertFalse(SERVER + notice.str("href") in fetched)
    }

    @Test
    fun listDetailErrorsAbort() = runTest {
        // Без деталей описание было бы пустым: лучше прервать, чем затереть копии.
        val details = details()
        details[SERVER + rawEvents[0].str("href")] = 500
        val error = assertFailsWith<DominoHttpException> { FakeDomino(rawEvents, details).calendar().list(WINDOW) }
        assertEquals(500, error.status)
    }

    @Test
    fun listEventGoneBetweenRequestsAborts() = runTest {
        val details = details()
        details[SERVER + rawEvents[0].str("href")] = 404
        assertFailsWith<DominoException> { FakeDomino(rawEvents, details).calendar().list(WINDOW) }
    }

    // --- get ---

    @Test
    fun getReadsDetailById() = runTest {
        val server = FakeDomino(details = details())

        val event = server.calendar().get(W_ID)

        assertEquals("$EVENTS_URL/$W_ID", server.gets.last().url)
        assertNotNull(event)
        assertEquals(W_ID, event.id)
        assertEquals(utc("2026-11-17T07:00:00Z"), event.start) // из tzid через timezones
        assertEquals("Standing agenda", event.description)
    }

    @Test
    fun getCancelledIsNull() = runTest {
        val cancelled = rawEvents[7]
        val server = FakeDomino(details = details())
        assertNull(server.calendar().get("${cancelled.str("id")}/${cancelled.str("recurrenceId")}"))
    }

    // --- update ---

    @Test
    fun updatePutsDetailWithChangedFields() = runTest {
        val details = details()
        val server = FakeDomino(details = details)
        val url = "$EVENTS_URL/$W_ID"
        val original = details.getValue(url) as JsonObject
        val changed = CalendarEvent(
            "Weekly sync v2", utc("2026-11-17T08:00:00Z"), utc("2026-11-17T08:45:00Z"),
            location = "", description = "New agenda",
        )

        server.calendar().update(W_ID, changed)

        val put = server.puts.single()
        assertEquals(url, put.url)
        val body = put.body!!
        assertEquals(original["timezones"], body["timezones"])
        val event = body.getValue("events").jsonArray.single().jsonObject
        val before = original.getValue("events").jsonArray[0].jsonObject
        assertEquals("Weekly sync v2", event.str("summary"))
        assertEquals("", event.str("location"))
        assertEquals("New agenda", event.str("description"))
        assertEquals(obj("""{"date": "2026-11-17", "time": "08:00:00", "utc": true}"""), event["start"])
        assertEquals(obj("""{"date": "2026-11-17", "time": "08:45:00", "utc": true}"""), event["end"])
        assertEquals(before["sequence"], event["sequence"])
        assertEquals("20261117T070000Z", event.str("recurrenceId"))
        assertEquals(before.keys.toList(), event.keys.toList()) // порядок полей сохранён
    }

    @Test
    fun updateKeepsMarkerOfCopy() = runTest {
        val server = FakeDomino(details = details())
        val changed = CalendarEvent(
            "Budget review", utc("2026-11-13T22:00:00Z"), utc("2026-11-13T23:00:00Z"), source = System.TEAMS,
        )

        server.calendar().update(A_ID, changed)

        val event = server.puts.single().body!!.getValue("events").jsonArray.single().jsonObject
        assertEquals("[MT] Budget review", event.str("summary"))
    }

    @Test
    fun updateOfSeriesResponseSendsOnlyTheInstance() = runTest {
        val master = obj("""{"id": "S", "summary": "Series", "start": {"date": "2026-11-17"}}""")
        val instance = obj(
            """{"id": "S", "summary": "Series", "recurrenceId": "20261117T070000Z", "start": {"date": "2026-11-17"}}""",
        )
        val server = FakeDomino(
            details = mutableMapOf(
                "$EVENTS_URL/S/20261117T070000Z" to JsonObject(mapOf("events" to JsonArray(listOf(master, instance)))),
            ),
        )

        server.calendar().update("S/20261117T070000Z", CalendarEvent("New", date("2026-11-17"), date("2026-11-18")))

        val events = server.puts.single().body!!.getValue("events").jsonArray
        assertEquals(1, events.size)
        assertEquals("20261117T070000Z", events[0].jsonObject.str("recurrenceId"))
    }
}
