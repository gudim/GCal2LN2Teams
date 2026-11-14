package app.gcaltolotus.adapters.domino

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray

internal fun utc(text: String): When = When.Timed(Instant.parse(text))

internal fun date(text: String): When = When.AllDay(LocalDate.parse(text))

/** Зона «FLE Standard Time» из ответов реального сервера (UTC+2, летом UTC+3). */
internal val FLE_TIMEZONES: JsonArray = obj(
    """
    {"timezones": [{
      "tzid": "FLE Standard Time",
      "standard": {"start": {"date": "1950-10-29", "time": "02:00:00"},
                   "offsetFrom": "+0300", "offsetTo": "+0200",
                   "recurrenceRule": "FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU;BYHOUR=2;BYMINUTE=0"},
      "daylight": {"start": {"date": "1950-03-26", "time": "02:00:00"},
                   "offsetFrom": "+0200", "offsetTo": "+0300",
                   "recurrenceRule": "FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU;BYHOUR=2;BYMINUTE=0"}
    }]}
    """,
).getValue("timezones").jsonArray

class DominoConvertTest {
    @Test
    fun allDayEndEqualToStartMeansOneDay() {
        val raw = obj("""{"id": "X", "summary": "S", "start": {"date": "2026-11-16"}, "end": {"date": "2026-11-16"}}""")
        assertEquals(date("2026-11-17"), eventFromDomino(raw).end)
    }

    @Test
    fun allDayWithoutEndIsOneDay() {
        val raw = obj("""{"id": "X", "summary": "S", "start": {"date": "2026-11-16"}}""")
        assertEquals(date("2026-11-17"), eventFromDomino(raw).end)
    }

    @Test
    fun tzidTimeWithoutDefinitionIsRejected() {
        val raw = obj(
            """{"id": "X", "summary": "S",
                "start": {"date": "2026-11-18", "time": "10:00:00", "tzid": "FLE Standard Time"},
                "end": {"date": "2026-11-18", "time": "11:00:00", "tzid": "FLE Standard Time"}}""",
        )
        assertFailsWith<IllegalArgumentException> { eventFromDomino(raw) }
    }

    @Test
    fun tzidStandardAndDaylightTime() {
        fun at(day: String, time: String) = eventFromDomino(
            obj(
                """{"id": "X", "summary": "S",
                    "start": {"date": "$day", "time": "$time", "tzid": "FLE Standard Time"},
                    "end": {"date": "$day", "time": "$time", "tzid": "FLE Standard Time"}}""",
            ),
            timezones = FLE_TIMEZONES,
        ).start
        assertEquals(utc("2026-07-01T09:00:00Z"), at("2026-07-01", "12:00:00")) // лето: UTC+3
        assertEquals(utc("2026-11-13T22:00:00Z"), at("2026-11-14", "00:00:00")) // зима: UTC+2
        // переходы: последнее воскресенье марта (29.03.2026) и октября (25.10.2026) в 02:00
        assertEquals(utc("2026-03-28T23:59:00Z"), at("2026-03-29", "01:59:00"))
        assertEquals(utc("2026-03-28T23:00:00Z"), at("2026-03-29", "02:00:00"))
        assertEquals(utc("2026-10-24T22:59:00Z"), at("2026-10-25", "01:59:00"))
        assertEquals(utc("2026-10-25T00:00:00Z"), at("2026-10-25", "02:00:00"))
    }

    @Test
    fun zoneWithoutDaylightUsesStandardOffset() {
        val zones = obj(
            """{"z": [{"tzid": "Minsk", "standard": {"offsetFrom": "+0300", "offsetTo": "+0300"}}]}""",
        ).getValue("z").jsonArray
        val raw = obj(
            """{"id": "X", "summary": "S",
                "start": {"date": "2026-07-01", "time": "12:00:00", "tzid": "Minsk"},
                "end": {"date": "2026-07-01", "time": "13:00:00", "tzid": "Minsk"}}""",
        )
        assertEquals(utc("2026-07-01T09:00:00Z"), eventFromDomino(raw, timezones = zones).start)
    }

    @Test
    fun southernHemisphereDaylight() {
        // Сидней: летнее время (UTC+11) с первого воскресенья октября до первого воскресенья апреля
        val zones = obj(
            """{"z": [{"tzid": "AUS",
                "standard": {"offsetTo": "+1000", "recurrenceRule": "FREQ=YEARLY;BYMONTH=4;BYDAY=1SU;BYHOUR=3"},
                "daylight": {"offsetTo": "+1100", "recurrenceRule": "FREQ=YEARLY;BYMONTH=10;BYDAY=1SU;BYHOUR=2"}}]}""",
        ).getValue("z").jsonArray
        fun at(day: String) = eventFromDomino(
            obj(
                """{"id": "X", "summary": "S",
                    "start": {"date": "$day", "time": "12:00:00", "tzid": "AUS"},
                    "end": {"date": "$day", "time": "13:00:00", "tzid": "AUS"}}""",
            ),
            timezones = zones,
        ).start
        assertEquals(utc("2026-01-15T01:00:00Z"), at("2026-01-15"))
        assertEquals(utc("2026-07-15T02:00:00Z"), at("2026-07-15"))
    }

    @Test
    fun recurrenceIdGivesInstanceIdAndOriginalStart() {
        val raw = obj(
            """{"id": "UID", "summary": "S", "recurrenceId": "20261117T070000Z",
                "start": {"date": "2026-11-17", "time": "07:00:00", "utc": true},
                "end": {"date": "2026-11-17", "time": "07:30:00", "utc": true}}""",
        )
        val event = eventFromDomino(raw)
        assertEquals("UID/20261117T070000Z", event.id)
        assertEquals("UID", event.uid)
        assertEquals(utc("2026-11-17T07:00:00Z"), event.originalStart)
    }

    @Test
    fun descriptionCrLfBecomesLfAndLastModifiedIsParsed() {
        val raw = obj(
            """{"id": "X", "summary": "S", "description": "Q4\r\nfigures\r\n",
                "last-modified": "20261007T050045Z",
                "start": {"date": "2026-11-17", "time": "07:00:00", "utc": true},
                "end": {"date": "2026-11-17", "time": "07:30:00", "utc": true}}""",
        )
        val event = eventFromDomino(raw)
        assertEquals("Q4\nfigures\n", event.description)
        assertEquals("Q4\nfigures", event.fields()["description"]) // хвостовой перевод строки не в снимке
        assertEquals(Instant.parse("2026-10-07T05:00:45Z"), event.modified)
    }

    @Test
    fun dateOnlyStampIsMidnightUtc() {
        assertEquals(Instant.parse("2026-10-07T00:00:00Z"), parseStamp("20261007"))
        assertNull(parseStamp(null))
        assertNull(parseStamp(""))
    }

    @Test
    fun notificationAndCancelledAreIgnored() {
        val times = """"start": {"date": "2026-11-17", "time": "07:00:00", "utc": true},
                       "end": {"date": "2026-11-17", "time": "07:30:00", "utc": true}"""
        assertTrue(eventFromDomino(obj("""{"id": "N", "summary": "S", $times, "x-lotus-unid": {"data": "B1"}}""")).ignored)
        assertTrue(eventFromDomino(obj("""{"id": "C", "summary": "S", $times, "status": "cancelled"}""")).ignored)
        assertFalse(eventFromDomino(obj("""{"id": "O", "summary": "S", $times, "status": "confirmed"}""")).ignored)
    }

    @Test
    fun confidentialIsPrivate() {
        val raw = obj("""{"id": "X", "summary": "S", "class": "CONFIDENTIAL", "start": {"date": "2026-11-16"}}""")
        assertTrue(eventFromDomino(raw).isPrivate)
    }

    @Test
    fun ownerDnFormatsAndEmailAreNormalized() {
        assertEquals("ivan petrov/acme", normalizeDn("CN=Ivan Petrov/O=ACME"))
        assertEquals("ivan petrov/acme", normalizeDn(" Ivan Petrov / ACME"))
        assertTrue(isOwner("CN=Ivan Petrov/O=ACME", null, OWNER))
        assertTrue(isOwner("Someone", "ipetrov@EXAMPLE.com", OWNER))
        assertFalse(isOwner("CN=Anna Smirnova/O=ACME", null, OWNER))
        assertFalse(isOwner("Ivan Petrov/ACME", null, null))
    }

    @Test
    fun meetingWithOtherAttendeesHasAttendees() {
        val raw = obj(
            """{"id": "M", "summary": "My meeting",
                "start": {"date": "2026-11-20", "time": "10:00:00", "utc": true},
                "end": {"date": "2026-11-20", "time": "11:00:00", "utc": true},
                "organizer": {"displayName": "Ivan Petrov/ACME", "email": "IPetrov@example.com"},
                "attendees": [{"displayName": "Ivan Petrov/ACME", "email": "IPetrov@example.com"},
                              {"displayName": "Team Mailbox/acme", "email": "team@example.com"}]}""",
        )
        val event = eventFromDomino(raw, owner = OWNER)
        assertTrue(event.organizerIsMe)
        assertTrue(event.hasAttendees)

        val alone = obj(raw.toString().replace("Team Mailbox/acme", "Ivan Petrov/ACME").replace("team@", "IPetrov@"))
        assertFalse(eventFromDomino(alone, owner = OWNER).hasAttendees)
    }

    @Test
    fun foreignOrganizerCountsAsAttendee() {
        val raw = obj(
            """{"id": "F", "summary": "S", "x-lotus-organizer": {"data": "CN=Anna Smirnova/O=ACME"},
                "start": {"date": "2026-11-19", "time": "08:00:00", "utc": true},
                "end": {"date": "2026-11-19", "time": "09:00:00", "utc": true}}""",
        )
        val event = eventFromDomino(raw, owner = OWNER)
        assertFalse(event.organizerIsMe)
        assertTrue(event.hasAttendees)
    }

    // --- запись ---

    @Test
    fun timedEventIsWrittenInUtcWithMarker() {
        val copy = CalendarEvent(
            "Release night", utc("2026-11-13T22:00:00Z"), utc("2026-11-13T23:30:00Z"), source = System.GOOGLE,
        )
        assertEquals(
            obj(
                """{"summary": "[GC] Release night",
                    "start": {"date": "2026-11-13", "time": "22:00:00", "utc": true},
                    "end": {"date": "2026-11-13", "time": "23:30:00", "utc": true}}""",
            ),
            eventToDomino(copy),
        )
    }

    @Test
    fun optionalFieldsAreWrittenOnlyWhenSet() {
        val event = CalendarEvent(
            "Doctor", date("2026-11-13"), date("2026-11-14"),
            location = "Clinic", description = "Bring card", isPrivate = true, source = System.TEAMS,
        )
        assertEquals(
            obj(
                """{"summary": "[MT] Doctor", "start": {"date": "2026-11-13"}, "end": {"date": "2026-11-14"},
                    "location": "Clinic", "description": "Bring card", "class": "private"}""",
            ),
            eventToDomino(event),
        )
    }

    @Test
    fun allDayRoundTrip() {
        val holiday = CalendarEvent(
            "Holiday", date("2026-11-13"), date("2026-11-15"), source = System.GOOGLE, id = "H", uid = "H",
        )
        val raw = kotlinx.serialization.json.JsonObject(eventToDomino(holiday) + ("id" to kotlinx.serialization.json.JsonPrimitive("H")))
        assertEquals(holiday, eventFromDomino(raw))
    }

    @Test
    fun ownMarkerStaysInTitle() {
        val raw = obj("""{"id": "X", "summary": "[LN] Own", "start": {"date": "2026-11-16"}}""")
        val event = eventFromDomino(raw)
        assertEquals("[LN] Own" to null, event.title to event.source)
    }

    // --- помощники списка ---

    @Test
    fun nextStartUsesContentRangeEndOrPageLength() {
        assertEquals(44, nextStart("items 0-43", 0, 40))
        assertEquals(50, nextStart(null, 10, 40))
        assertEquals(50, nextStart("items 0-5", 10, 40)) // диапазон не продвигает — по длине страницы
    }

    @Test
    fun multiDayAllDayEntriesAreMergedByHref() {
        val entries = listOf(
            obj("""{"href": "/t", "id": "T", "summary": "Trip", "start": {"date": "2026-11-24"}, "end": {"date": "2026-11-25"}}"""),
            obj("""{"href": "/o", "id": "O", "summary": "Other", "start": {"date": "2026-11-24"}}"""),
            obj("""{"href": "/t", "id": "T", "summary": "Trip", "start": {"date": "2026-11-23"}, "end": {"date": "2026-11-24"}}"""),
            obj("""{"href": "/t", "id": "T", "summary": "Trip", "start": {"date": "2026-11-25"}, "end": {"date": "2026-11-26"}}"""),
        )
        val merged = mergeMultiDay(entries)
        assertEquals(listOf("T", "O"), merged.map { it["id"].toString().trim('"') })
        val trip = eventFromDomino(merged[0])
        assertEquals(date("2026-11-23") to date("2026-11-26"), trip.start to trip.end)
    }

    @Test
    fun pickChoosesInstanceByRecurrenceId() {
        val events = listOf(
            obj("""{"id": "U", "summary": "master"}"""),
            obj("""{"id": "U", "summary": "i1", "recurrenceId": "20261117T070000Z"}"""),
        )
        assertEquals("i1", pick(events, "U/20261117T070000Z")!!["summary"].toString().trim('"'))
        assertEquals("master", pick(events, "U")!!["summary"].toString().trim('"'))
        assertNull(pick(events, "U/OTHER")) // экземпляра нет: не мастер, иначе задели бы всю серию
        assertNull(pick(emptyList(), "U"))
    }

    @Test
    fun utcParamDropsFractions() {
        assertEquals("2026-10-31T21:00:00Z", utcParam(Instant.parse("2026-10-31T21:00:00.123Z")))
    }

    @Test
    fun urlJoinLikePython() {
        assertEquals("https://h/mail/a.nsf", urlJoin("https://h", "/mail/a.nsf"))
        assertEquals("https://h/mail/a.nsf", urlJoin("https://h/x/y", "/mail/a.nsf"))
        assertEquals("https://other/z", urlJoin("https://h", "https://other/z"))
        assertEquals("https://h/x/z", urlJoin("https://h/x/y", "z"))
    }
}
