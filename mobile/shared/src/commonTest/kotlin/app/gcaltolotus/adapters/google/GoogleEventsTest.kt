package app.gcaltolotus.adapters.google

import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.When
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

internal fun at(iso: String): When = When.Timed(Instant.parse(iso))

internal fun day(iso: String): When = When.AllDay(LocalDate.parse(iso))

/** Чистые преобразования событий Google без сети и фикстур (порт части tests/test_google_adapter.py). */
class GoogleEventsTest {
    @Test
    fun createBodyHasMarkerAndUtcTimes() {
        val copy = CalendarEvent(
            title = "Standup",
            start = at("2026-11-13T07:00:00Z"),
            end = at("2026-11-13T07:15:00Z"),
            source = System.TEAMS,
        )

        val body = eventToGoogle(copy)

        assertEquals(JsonPrimitive("[MT] Standup"), body["summary"])
        assertEquals(
            obj("""{"dateTime": "2026-11-13T07:00:00+00:00", "timeZone": "UTC"}"""),
            body["start"],
        )
        assertEquals(
            obj("""{"dateTime": "2026-11-13T07:15:00+00:00", "timeZone": "UTC"}"""),
            body["end"],
        )
        // Без id у копии — только система в скрытой метке.
        assertEquals(obj("""{"private": {"gcalsync_origin": "teams"}}"""), body["extendedProperties"])
    }

    @Test
    fun createBodyStoresHiddenMarkerAndPrivacy() {
        val copy = CalendarEvent(
            title = "Budget review",
            start = at("2026-11-13T09:00:00Z"),
            end = at("2026-11-13T10:00:00Z"),
            source = System.DOMINO,
            id = "A1B2/20261113T090000Z",
            isPrivate = true,
        )

        val body = eventToGoogle(copy)

        assertEquals(
            obj(
                """{"private": {"gcalsync_origin": "domino",
                   "gcalsync_origin_id": "A1B2/20261113T090000Z"}}""",
            ),
            body["extendedProperties"],
        )
        assertEquals(JsonPrimitive("private"), body["visibility"])
        assertEquals(JsonPrimitive("[LN] Budget review"), body["summary"])
    }

    @Test
    fun nativeEventBodyHasNoMarker() {
        val body = eventToGoogle(
            CalendarEvent(title = "Mine", start = day("2026-11-13"), end = day("2026-11-14")),
        )

        assertEquals(JsonPrimitive("Mine"), body["summary"])
        assertEquals(obj("""{"date": "2026-11-13"}"""), body["start"])
        assertEquals(obj("""{"date": "2026-11-14"}"""), body["end"])
        assertFalse("extendedProperties" in body)
        assertFalse("visibility" in body)
    }

    @Test
    fun syncedFieldsAreTheOnlyKeysOfPatchBody() {
        val body = syncedFieldsToGoogle(
            CalendarEvent(
                title = "Budget review v2",
                start = at("2026-11-13T09:30:00Z"),
                end = at("2026-11-13T10:30:00Z"),
                description = "New agenda",
                source = System.DOMINO,
                id = "x",
                isPrivate = true,
            ),
        )

        assertEquals(listOf("summary", "location", "description", "start", "end"), body.keys.toList())
        assertEquals(JsonPrimitive("[LN] Budget review v2"), body["summary"])
        assertEquals(JsonPrimitive(""), body["location"])
        assertEquals(JsonPrimitive("New agenda"), body["description"])
    }

    @Test
    fun allDayRoundTrip() {
        val holiday = CalendarEvent(
            title = "Holiday",
            start = day("2026-11-13"),
            end = day("2026-11-14"),
            source = System.DOMINO,
            id = "x",
        )

        val body = eventToGoogle(holiday)
        val back = eventFromGoogle(JsonObject(body + ("id" to JsonPrimitive("x"))))

        assertEquals(holiday, back)
    }

    @Test
    fun timedRoundTripKeepsInstant() {
        val event = CalendarEvent(
            title = "Call",
            start = at("2026-11-13T07:00:00Z"),
            end = at("2026-11-13T07:30:00Z"),
            id = "y",
        )

        val back = eventFromGoogle(JsonObject(eventToGoogle(event) + ("id" to JsonPrimitive("y"))))

        assertEquals(event, back)
    }

    @Test
    fun offsetTimesAreConvertedToUtc() {
        val event = eventFromGoogle(
            obj(
                """{"id": "a", "summary": "Release  night",
                   "start": {"dateTime": "2026-11-14T01:00:00+03:00", "timeZone": "Europe/Moscow"},
                   "end": {"dateTime": "2026-11-14T02:30:00+03:00", "timeZone": "Europe/Moscow"}}""",
            ),
        )

        assertEquals("Release night", event.title)
        assertEquals(at("2026-11-13T22:00:00Z"), event.start)
        assertEquals(at("2026-11-13T23:30:00Z"), event.end)
    }

    @Test
    fun missingFieldsTakeDefaults() {
        val event = eventFromGoogle(
            obj("""{"id": "a", "start": {"date": "2026-11-13"}, "end": {"date": "2026-11-14"}}"""),
        )

        assertEquals("", event.title)
        assertEquals("", event.location)
        assertEquals("", event.description)
        assertNull(event.source)
        assertNull(event.uid)
        assertNull(event.modified)
        assertTrue(event.organizerIsMe) // нет organizer — своё событие
        assertFalse(event.hasAttendees)
        assertFalse(event.isPrivate)
        assertFalse(event.ignored)
    }

    @Test
    fun ownMarkerStaysInTitle() {
        val event = eventFromGoogle(
            obj(
                """{"id": "a", "summary": "[GC] Mine",
                   "start": {"date": "2026-11-13"}, "end": {"date": "2026-11-14"}}""",
            ),
        )

        assertEquals("[GC] Mine" to null, event.title to event.source)
    }

    @Test
    fun hiddenMarkerOfOwnOrUnknownSystemIsNotACopy() {
        for (origin in listOf("google", "outlook")) {
            val event = eventFromGoogle(
                obj(
                    """{"id": "a", "summary": "[LN] Title",
                       "extendedProperties": {"private": {"gcalsync_origin": "$origin"}},
                       "start": {"date": "2026-11-13"}, "end": {"date": "2026-11-14"}}""",
                ),
            )
            // Скрытая метка не годится — остаётся маркер в названии.
            assertEquals("Title" to System.DOMINO, event.title to event.source, origin)
        }
    }

    @Test
    fun organizerWithoutSelfIsForeign() {
        val event = eventFromGoogle(
            obj(
                """{"id": "a", "organizer": {"email": "boss@example.com"},
                   "start": {"date": "2026-11-13"}, "end": {"date": "2026-11-14"}}""",
            ),
        )

        assertFalse(event.organizerIsMe)
    }

    @Test
    fun selfOnlyAttendeeIsNotAttendees() {
        val event = eventFromGoogle(
            obj(
                """{"id": "a", "attendees": [{"email": "me@x", "self": true}],
                   "start": {"date": "2026-11-13"}, "end": {"date": "2026-11-14"}}""",
            ),
        )

        assertFalse(event.hasAttendees)
    }

    @Test
    fun cancelledInstanceTakesOriginalStart() {
        val event = eventFromGoogle(
            obj(
                """{"id": "g-gone", "status": "cancelled", "recurringEventId": "g-meeting",
                   "originalStartTime": {"dateTime": "2026-11-24T07:00:00Z"}}""",
            ),
        )

        assertTrue(event.ignored)
        assertEquals(at("2026-11-24T07:00:00Z"), event.start)
        assertEquals(at("2026-11-24T07:00:00Z"), event.end)
        assertEquals(at("2026-11-24T07:00:00Z"), event.originalStart)
    }

    @Test
    fun eventWithoutStartIsRejected() {
        assertFailsWith<IllegalArgumentException> { eventFromGoogle(obj("""{"id": "a"}""")) }
        assertFailsWith<IllegalArgumentException> {
            eventFromGoogle(obj("""{"id": "a", "start": {"date": "2026-11-13"}}"""))
        }
        // Начало — момент, конец — дата.
        assertFailsWith<IllegalArgumentException> {
            eventFromGoogle(
                obj(
                    """{"id": "a", "start": {"dateTime": "2026-11-13T07:00:00Z"},
                       "end": {"date": "2026-11-14"}}""",
                ),
            )
        }
    }

    @Test
    fun builtBodiesAreValidJson() {
        // Тело строится buildJsonObject — проверка, что его можно сериализовать и разобрать.
        val body = eventToGoogle(
            CalendarEvent(title = "A", start = day("2026-11-13"), end = day("2026-11-14")),
        )
        assertEquals(body, obj(body.toString()))
        assertEquals(buildJsonObject { put("date", "2026-11-13") }, body["start"])
    }
}
