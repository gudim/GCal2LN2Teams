package app.gcaltolotus.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

class SyncJsonTest {
    @Test
    fun whenToAndFromJson() {
        assertEquals(JsonPrimitive("2026-11-13T10:00:00Z"), SyncJson.whenToJson(at("2026-11-13T13:00:00+03:00")))
        assertEquals(JsonPrimitive("2026-11-13T10:00:00.250000Z"), SyncJson.whenToJson(at("2026-11-13T10:00:00.25Z")))
        assertEquals(JsonPrimitive("2026-11-13"), SyncJson.whenToJson(day("2026-11-13")))
        assertEquals(JsonNull, SyncJson.whenToJson(null))
        assertEquals(day("2026-11-13"), SyncJson.whenFromJson(JsonPrimitive("2026-11-13")))
        assertEquals(at("2026-11-13T10:00:00Z"), SyncJson.whenFromJson(JsonPrimitive("2026-11-13T13:00:00+03:00")))
        assertEquals(null, SyncJson.whenFromJson(JsonNull))
        assertFailsWith<IllegalArgumentException> { SyncJson.whenFromJson(JsonPrimitive("2026-11-13T10:00:00")) }
    }

    @Test
    fun eventRoundTripWithEveryField() {
        val event = CalendarEvent(
            "Обзор", at("2026-11-13T10:00:00Z"), at("2026-11-13T11:00:00Z"),
            location = "Room", description = "a\nb", source = System.TEAMS, id = "g1", uid = "U1",
            originalStart = at("2026-11-12T10:00:00Z"), organizerIsMe = false, hasAttendees = true,
            isPrivate = true, modified = Instant.parse("2026-11-01T08:00:00Z"), ignored = true,
        )
        val json = SyncJson.eventToJson(event)
        assertEquals(
            Json.parseToJsonElement(
                """{"id":"g1","title":"Обзор","start":"2026-11-13T10:00:00Z","end":"2026-11-13T11:00:00Z",
                "location":"Room","description":"a\nb","source":"teams","uid":"U1",
                "original_start":"2026-11-12T10:00:00Z","organizer_is_me":false,"has_attendees":true,
                "private":true,"modified":"2026-11-01T08:00:00Z","ignored":true}""",
            ),
            json,
        )
        assertEquals(event, SyncJson.eventFromJson(json))
    }

    @Test
    fun eventWithNullsAndDates() {
        val event = CalendarEvent("Holiday", day("2026-11-13"), day("2026-11-14"))
        val json = SyncJson.eventToJson(event)
        assertEquals(JsonNull, json["id"])
        assertEquals(JsonNull, json["source"])
        assertEquals(JsonNull, json["modified"])
        assertEquals(event, SyncJson.eventFromJson(json))
    }

    @Test
    fun missingKeyIsAnError() {
        val json = SyncJson.eventToJson(CalendarEvent("x", day("2026-11-13"), day("2026-11-14")))
        assertFailsWith<IllegalArgumentException> {
            SyncJson.eventFromJson(kotlinx.serialization.json.JsonObject(json - "ignored"))
        }
    }

    @Test
    fun linkRoundTripKeepsSnapshotsAsIs() {
        val snapshot = timed("Обзор", "2026-11-13T10:00:00Z").fields()
        val link = Link(System.GOOGLE, "g1", System.DOMINO, "d1", LinkKind.COPY, snapshot, snapshot, kept = true)
        val json = SyncJson.linkToJson(link)
        assertEquals(JsonPrimitive("google:g1->domino"), json["key"])
        assertEquals(link, SyncJson.linkFromJson(json))
        val old = Link(System.DOMINO, "d1", System.TEAMS, null, LinkKind.DISTINCT)
        assertEquals(old, SyncJson.linkFromJson(SyncJson.linkToJson(old)))
        assertEquals(JsonNull, SyncJson.linkToJson(old)["source_fields"])
    }

    @Test
    fun linksAreSortedByKey() {
        val links = listOf(
            Link(System.TEAMS, "t1", System.GOOGLE),
            Link(System.GOOGLE, "g1", System.DOMINO, "d1", LinkKind.TWIN),
            Link(System.GOOGLE, "g1", System.DOMINO),
        )
        assertEquals(
            listOf("google:g1->domino", "teams:t1->google", "twin:google:g1->domino:d1"),
            SyncJson.linksToJson(links).map { (it as kotlinx.serialization.json.JsonObject)["key"]!!.let { k -> (k as JsonPrimitive).content } },
        )
    }

    @Test
    fun windowRoundTrip() {
        val window = Window(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"))
        val json = SyncJson.windowToJson(window)
        assertEquals(Json.parseToJsonElement("""{"start":"2026-01-01T00:00:00Z","end":"2027-01-01T00:00:00Z"}"""), json)
        assertEquals(window, SyncJson.windowFromJson(json))
    }
}
