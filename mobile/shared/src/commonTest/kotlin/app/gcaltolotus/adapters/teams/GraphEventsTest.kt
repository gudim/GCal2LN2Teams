package app.gcaltolotus.adapters.teams

import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.When
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Преобразования без HTTP: event_to_graph / event_from_graph / html_to_text / transactionId. */
class GraphEventsTest {
    private fun day(y: Int, m: Int, d: Int) = When.AllDay(LocalDate(y, m, d))

    @Test
    fun allDayRoundTrip() {
        val holiday = CalendarEvent(
            "Holiday", day(2026, 11, 13), day(2026, 11, 14), source = System.DOMINO, id = "H",
        )

        val body = eventToGraph(holiday)
        val back = eventFromGraph(JsonObject(body + ("id" to JsonPrimitive("H"))))

        assertEquals(holiday, back)
    }

    @Test
    fun transactionIdDiffersPerOrigin() {
        val a = CalendarEvent("A", day(2026, 11, 13), day(2026, 11, 14), source = System.DOMINO, id = "1")
        val b = CalendarEvent("A", day(2026, 11, 13), day(2026, 11, 14), source = System.GOOGLE, id = "1")

        assertNotEquals(eventToGraph(a)["transactionId"], eventToGraph(b)["transactionId"])
    }

    @Test
    fun transactionIdIsTheSameUuid5AsPython() {
        // Значения посчитаны Python: uuid.uuid5(_TRANSACTION_NAMESPACE, "<origin>|<id>|teams").
        val cases = mapOf(
            (System.DOMINO to "A1B2/20261113T090000Z") to "485411fe-402c-50c0-856a-3281a2511556",
            (System.DOMINO to "1") to "bda5c118-b84e-5bc3-80e3-058c6cd14789",
            (System.GOOGLE to "Событие") to "fe5c884b-dba6-5f74-bc57-90873bb91e91",
        )
        for ((origin, expected) in cases) {
            val event = CalendarEvent(
                "A", day(2026, 11, 13), day(2026, 11, 14), source = origin.first, id = origin.second,
            )
            assertEquals(expected, eventToGraph(event)["transactionId"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun copyWithoutIdHasMarkerButNoTransactionId() {
        val copy = CalendarEvent("A", day(2026, 11, 13), day(2026, 11, 14), source = System.GOOGLE)

        val body = eventToGraph(copy)

        assertEquals(
            JsonArray(listOf(buildJsonObject { put("id", ORIGIN_PROP); put("value", "google") })),
            body["singleValueExtendedProperties"],
        )
        assertFalse("transactionId" in body)
    }

    @Test
    fun nativeEventBodyHasNoMarker() {
        val body = eventToGraph(CalendarEvent("Mine", day(2026, 11, 13), day(2026, 11, 14)))

        assertFalse("singleValueExtendedProperties" in body || "transactionId" in body)
        assertFalse("sensitivity" in body)
        assertEquals(JsonPrimitive("Mine"), body["subject"])
        assertEquals(
            buildJsonObject { put("dateTime", "2026-11-13T00:00:00"); put("timeZone", "UTC") },
            body["start"],
        )
        assertEquals(JsonPrimitive(true), body["isAllDay"])
    }

    @Test
    fun timedMicrosecondsAreKeptLikePythonIsoformat() {
        val event = CalendarEvent(
            "A",
            When.Timed(Instant.parse("2026-11-13T09:00:00.250Z")),
            When.Timed(Instant.parse("2026-11-13T10:00:00Z")),
        )

        val body = eventToGraph(event)

        assertEquals(JsonPrimitive("2026-11-13T09:00:00.250000"), (body["start"] as JsonObject)["dateTime"])
        assertEquals(JsonPrimitive("2026-11-13T10:00:00"), (body["end"] as JsonObject)["dateTime"])
    }

    @Test
    fun unknownHiddenOriginFallsBackToTitleMarker() {
        val raw = minimal("[GC] Title") + ("singleValueExtendedProperties" to props("outlook"))

        assertEquals(System.GOOGLE, eventFromGraph(JsonObject(raw)).source)
    }

    @Test
    fun ownSystemInHiddenOriginIsNotACopy() {
        val raw = minimal("Title") + ("singleValueExtendedProperties" to props("teams"))

        assertEquals(null, eventFromGraph(JsonObject(raw)).source)
    }

    @Test
    fun missingStartIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            eventFromGraph(JsonObject(minimal("x") - "start"))
        }
    }

    @Test
    fun offsetStampIsConvertedToUtcAndTruncatedToMicroseconds() {
        val raw = minimal("x") + ("lastModifiedDateTime" to JsonPrimitive("2026-11-10T11:15:30.1234567+03:00"))

        assertEquals(
            Instant.parse("2026-11-10T08:15:30.123456Z"),
            eventFromGraph(JsonObject(raw)).modified,
        )
    }

    @Test
    fun badOffsetStampIsRejected() {
        val raw = minimal("x") + ("lastModifiedDateTime" to JsonPrimitive("10.11.2026 08:15"))

        assertFailsWith<IllegalArgumentException> { eventFromGraph(JsonObject(raw)) }
    }

    @Test
    fun htmlToTextSkipsStyleAndBreaksBlocks() {
        val html = "<html><head><title>T</title><style>p{margin:0}</style></head><body>" +
            "<div>Hello&nbsp;team<br>Line&amp;two</div><p>Third</p>" +
            "<script>if (a < b) { x('</div>') }</script><!-- note -->" +
            "<ul><li>One &#8211; &#x41;</li><li>Two&lt;3&gt;  spaced   out</li></ul>" +
            "<P>Upper<BR/>case</P>&unknown; a < b</body></html>"

        assertEquals(
            "Hello team\nLine&two\nThird\nOne – A\nTwo<3> spaced out\nUpper\ncase\n&unknown; a < b",
            htmlToText(html),
        )
    }

    @Test
    fun sha1MatchesKnownVectors() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", sha1(ByteArray(0)).toHex())
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1("abc".encodeToByteArray()).toHex())
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            sha1("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).toHex(),
        )
        assertEquals("34aa973cd4c4daa4f61eeb2bdbad27316534016f", sha1(ByteArray(1_000_000) { 'a'.code.toByte() }).toHex())
    }

    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun minimal(subject: String): Map<String, kotlinx.serialization.json.JsonElement> = buildJsonObject {
        put("id", "X")
        put("subject", subject)
        put("start", buildJsonObject { put("dateTime", "2026-11-13T09:00:00.0000000"); put("timeZone", "UTC") })
        put("end", buildJsonObject { put("dateTime", "2026-11-13T10:00:00.0000000"); put("timeZone", "UTC") })
    }

    private fun props(origin: String) =
        JsonArray(listOf(buildJsonObject { put("id", ORIGIN_PROP); put("value", origin) }))
}
