package app.gcaltolotus.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

internal fun at(text: String): When = When.Timed(Instant.parse(text))

internal fun day(text: String): When = When.AllDay(LocalDate.parse(text))

internal fun timed(title: String, start: String, hours: Int = 1, id: String? = null, source: System? = null) =
    Instant.parse(start).let {
        CalendarEvent(title, When.Timed(it), When.Timed(it + hours.hours), id = id, source = source)
    }

class CalendarEventTest {
    @Test
    fun titleWhitespaceIsCollapsedLikePythonSplit() {
        val e = timed("  Team \t sync  now\n", "2026-11-13T10:00:00Z")
        assertEquals("Team sync now", e.title)
    }

    @Test
    fun copyNormalisesTitleAgain() {
        val e = timed("A", "2026-11-13T10:00:00Z").copy(title = " B   C ")
        assertEquals("B C", e.title)
    }

    @Test
    fun startAndEndMustBeOfOneKind() {
        assertFailsWith<IllegalArgumentException> {
            CalendarEvent("x", at("2026-01-01T00:00:00Z"), day("2026-01-02"))
        }
        assertFailsWith<IllegalArgumentException> {
            CalendarEvent("x", day("2026-01-01"), at("2026-01-02T00:00:00Z"))
        }
    }

    @Test
    fun timeWithOffsetIsStoredAsUtc() {
        // 01:00 по Москве 14-го == 22:00 UTC 13-го: один момент
        val msk = timed("Release", "2026-11-14T01:00:00+03:00")
        val utc = timed("Release", "2026-11-13T22:00:00Z")
        assertEquals(utc.matchKey(), msk.matchKey())
        assertEquals("2026-11-13T22:00:00+00:00", msk.fields()["start"])
    }

    @Test
    fun fieldsOfTimedEvent() {
        val e = CalendarEvent(
            "Обзор", at("2026-11-13T10:00:00Z"), at("2026-11-13T11:00:00Z"),
            location = "  Room \t 5 ", description = "  line 1   \r\n\tline 2\t\n\n  ",
        )
        assertEquals(
            mapOf(
                "title" to "Обзор",
                "start" to "2026-11-13T10:00:00+00:00",
                "end" to "2026-11-13T11:00:00+00:00",
                "location" to "Room 5",
                "description" to "line 1\n\tline 2",
            ),
            e.fields(),
        )
        assertEquals(FIELD_NAMES, e.fields().keys.toList())
    }

    @Test
    fun fieldsPrintMicrosecondsOnlyWhenNonZero() {
        val e = CalendarEvent("x", at("2026-11-13T10:00:00.5Z"), at("2026-11-13T11:00:00.000123Z"))
        assertEquals("2026-11-13T10:00:00.500000+00:00", e.fields()["start"])
        assertEquals("2026-11-13T11:00:00.000123+00:00", e.fields()["end"])
        // наносекунды Python не хранит: отбрасываются
        val n = CalendarEvent("x", at("2026-11-13T10:00:00.000000999Z"), at("2026-11-13T11:00:00Z"))
        assertEquals("2026-11-13T10:00:00+00:00", n.fields()["start"])
    }

    @Test
    fun fieldsOfAllDayEvent() {
        val e = CalendarEvent("Holiday", day("2026-11-13"), day("2026-11-14"))
        assertEquals("2026-11-13", e.fields()["start"])
        assertEquals("2026-11-14", e.fields()["end"])
        assertEquals(true, e.allDay)
    }

    @Test
    fun descriptionNormalisationMatchesPython() {
        // Ожидания получены из Python: "\n".join(l.rstrip() for l in d.strip().splitlines())
        fun desc(d: String) =
            CalendarEvent("x", day("2026-01-01"), day("2026-01-02"), description = d).fields()["description"]
        assertEquals("", desc(""))
        assertEquals("", desc(" \n\t "))
        assertEquals("a\n\nb", desc("a\r\n\r\nb\n"))
        assertEquals("a\nb\nc\nd", desc("a\rb c\u000bd"))
        assertEquals("a\n b", desc("a  \n b　"))
    }

    @Test
    fun keys() {
        val e = timed("Straße Review", "2026-11-13T10:00:00Z")
        assertEquals(EventKey("Straße Review", at("2026-11-13T10:00:00Z")), e.matchKey())
        assertEquals(EventKey("strasse review", at("2026-11-13T10:00:00Z")), e.twinKey())
        assertEquals(timed("ОБЗОР", "2026-11-13T10:00:00Z").twinKey(), timed("обзор", "2026-11-13T10:00:00Z").twinKey())
        assertNull(e.uidKey())
        assertNull(e.copy(uid = "").uidKey())
        assertEquals(EventKey("U1", at("2026-11-13T10:00:00Z")), e.copy(uid = "U1").uidKey())
        assertEquals(
            EventKey("U1", at("2026-11-12T10:00:00Z")),
            e.copy(uid = "U1", originalStart = at("2026-11-12T10:00:00Z")).uidKey(),
        )
    }

    @Test
    fun dateAndMomentKeysDiffer() {
        assertNotEquals(
            CalendarEvent("x", day("2026-11-13"), day("2026-11-14")).matchKey(),
            CalendarEvent("x", at("2026-11-13T00:00:00Z"), at("2026-11-14T00:00:00Z")).matchKey(),
        )
    }

    @Test
    fun markedTitle() {
        assertEquals("Standup", timed("Standup", "2026-11-13T10:00:00Z").markedTitle())
        assertEquals("[GC] Standup", timed("Standup", "2026-11-13T10:00:00Z", source = System.GOOGLE).markedTitle())
        assertEquals("[LN] A", timed("A", "2026-11-13T10:00:00Z", source = System.DOMINO).markedTitle())
        assertEquals("[MT] A", timed("A", "2026-11-13T10:00:00Z", source = System.TEAMS).markedTitle())
    }

    @Test
    fun splitMarkerIgnoresOwnMarker() {
        assertEquals("Team sync" to System.GOOGLE, splitMarker(" [GC]  Team  sync ", own = System.DOMINO))
        assertEquals("[LN] Mine" to null, splitMarker("[LN] Mine", own = System.DOMINO))
        assertEquals("Plain" to null, splitMarker("Plain", own = System.GOOGLE))
    }

    @Test
    fun equalityCoversAllFields() {
        val a = timed("A", "2026-11-13T10:00:00Z", id = "g1")
        assertEquals(a, timed(" A ", "2026-11-13T10:00:00Z", id = "g1"))
        assertEquals(a.hashCode(), timed("A", "2026-11-13T10:00:00Z", id = "g1").hashCode())
        assertNotEquals(a, a.copy(isPrivate = true))
        assertNotEquals(a, a.copy(modified = Instant.parse("2026-11-13T10:00:00Z")))
    }

    @Test
    fun systemsInCanonicalOrder() {
        assertEquals(listOf("google", "domino", "teams"), System.ORDER.map { it.value })
        assertEquals(System.TEAMS, System.fromValue("teams"))
        assertFailsWith<IllegalArgumentException> { System.fromValue("outlook") }
    }

    @Test
    fun whenComparesOnlyWithinOneKind() {
        assertEquals(true, at("2026-11-13T10:00:00Z") < at("2026-11-13T11:00:00Z"))
        assertEquals(true, day("2026-11-13") < day("2026-11-14"))
        assertFailsWith<IllegalArgumentException> { at("2026-11-13T10:00:00Z") < day("2026-11-14") }
    }
}
