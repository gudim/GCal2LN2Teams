package app.gcaltolotus.adapters.teams

import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.When
import app.gcaltolotus.sync.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** HTTP-часть календаря Teams на MockEngine, без фикстур (они — в jvmTest). */
class TeamsCalendarHttpTest {
    private fun at(text: String) = When.Timed(Instant.parse(text))

    private fun calendar(http: MockHttp, tokens: MutableList<String> = mutableListOf("T1")) =
        TeamsCalendar(
            http.client,
            accessToken = { tokens.first() },
            refreshAccessToken = { tokens.removeAt(0); tokens.first() },
        )

    @Test
    fun createSendsUtcWithoutOffset() = runTest {
        val http = MockHttp(ok("""{"id":"NEW"}"""))
        val copy = CalendarEvent(
            "Release night",
            at("2026-11-14T01:00:00+03:00"),
            at("2026-11-14T02:30:00+03:00"),
            description = "Deploy",
            source = System.GOOGLE,
        )

        assertEquals("NEW", calendar(http).create(copy))

        val request = http.requests.single()
        assertEquals("POST", request.method.value)
        assertEquals("https://graph.microsoft.com/v1.0/me/events", request.url.toString())
        val body = request.json
        assertEquals(JsonPrimitive("[GC] Release night"), body["subject"])
        assertEquals(
            buildJsonObject { put("dateTime", "2026-11-13T22:00:00"); put("timeZone", "UTC") },
            body["start"],
        )
        assertEquals(
            buildJsonObject { put("contentType", "text"); put("content", "Deploy") },
            body["body"],
        )
        assertEquals(JsonPrimitive(false), body["isAllDay"])
        assertEquals("Bearer T1", request.headers["Authorization"])
        assertTrue(request.contentType.orEmpty().startsWith("application/json"))
    }

    @Test
    fun createStoresHiddenMarkerPrivacyAndTransactionId() = runTest {
        val http = MockHttp(ok("""{"id":"NEW"}"""), ok("""{"id":"NEW2"}"""))
        val copy = CalendarEvent(
            "Budget review",
            at("2026-11-13T09:00:00Z"),
            at("2026-11-13T10:00:00Z"),
            source = System.DOMINO,
            id = "A1B2/20261113T090000Z",
            isPrivate = true,
        )
        val teams = calendar(http)

        teams.create(copy)
        teams.create(copy)

        val (first, second) = http.requests
        val body = first.json
        assertEquals(
            JsonArray(
                listOf(
                    buildJsonObject { put("id", ORIGIN_PROP); put("value", "domino") },
                    buildJsonObject { put("id", ORIGIN_ID_PROP); put("value", "A1B2/20261113T090000Z") },
                ),
            ),
            body["singleValueExtendedProperties"],
        )
        assertEquals(JsonPrimitive("private"), body["sensitivity"])
        assertEquals(body["transactionId"], second.json["transactionId"])
        assertTrue(first.headers["Prefer"]!!.contains("IdType=\"ImmutableId\""))
    }

    @Test
    fun getOtherErrorsPropagate() = runTest {
        val error = assertFailsWith<GraphException> { calendar(MockHttp(Reply(500, "boom"))).get("x") }
        assertEquals(500, error.status)
    }

    @Test
    fun getEncodesIdAsOnePathSegment() = runTest {
        val http = MockHttp(Reply(404))

        calendar(http).get("AA/B+C=")

        assertEquals("/v1.0/me/events/AA%2FB+C=", http.requests.single().url.encodedPath)
    }

    @Test
    fun updatePatchesSyncedFields() = runTest {
        val http = MockHttp(ok("{}"))
        val changed = CalendarEvent(
            "Budget review v2",
            at("2026-11-13T09:30:00Z"),
            at("2026-11-13T10:30:00Z"),
            description = "New agenda",
            source = System.DOMINO,
        )

        calendar(http).update("AAMk-copy", changed)

        val request = http.requests.single()
        assertEquals("PATCH", request.method.value)
        assertEquals("/v1.0/me/events/AAMk-copy", request.url.encodedPath)
        val body = request.json
        assertEquals(JsonPrimitive("[LN] Budget review v2"), body["subject"])
        assertEquals(
            buildJsonObject { put("contentType", "text"); put("content", "New agenda") },
            body["body"],
        )
        assertEquals(buildJsonObject { put("displayName", "") }, body["location"])
        assertFalse("transactionId" in body || "singleValueExtendedProperties" in body)
        assertTrue(request.headers["Prefer"]!!.contains("IdType=\"ImmutableId\""))
    }

    @Test
    fun deleteToleratesAlreadyDeleted() = runTest {
        for (reply in listOf(Reply(204), Reply(404), Reply(410))) {
            val http = MockHttp(reply)

            calendar(http).delete("AAMk-copy")

            val request = http.requests.single()
            assertEquals("DELETE", request.method.value)
            assertEquals("/v1.0/me/events/AAMk-copy", request.url.encodedPath)
        }
    }

    @Test
    fun deleteOtherErrorsPropagate() = runTest {
        val error = assertFailsWith<GraphException> { calendar(MockHttp(Reply(403))).delete("x") }
        assertEquals(403, error.status)
    }

    @Test
    fun unauthorizedRefreshesOnceAndRetries() = runTest {
        val http = MockHttp(Reply(401), Reply(204))
        val tokens = mutableListOf("OLD", "NEW")

        calendar(http, tokens).delete("x")

        assertEquals(listOf("Bearer OLD", "Bearer NEW"), http.requests.map { it.headers["Authorization"] })
    }

    @Test
    fun secondUnauthorizedIsAnError() = runTest {
        val http = MockHttp(Reply(401), Reply(401))
        val tokens = mutableListOf("OLD", "NEW", "NEWER")

        val error = assertFailsWith<GraphException> { calendar(http, tokens).delete("x") }

        assertEquals(401, error.status)
        assertEquals(2, http.requests.size)
        assertEquals(listOf("NEW", "NEWER"), tokens) // обновили ровно один раз
    }

    @Test
    fun nextLinkToAnotherHostIsRefused() = runTest {
        val http = MockHttp(ok("""{"value":[],"@odata.nextLink":"https://evil.example.com/steal"}"""))
        val window = Window(Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"))

        assertFailsWith<GraphException> { calendar(http).list(window) }
        assertEquals(1, http.requests.size)
    }

    @Test
    fun windowIsSentInWholeSeconds() = runTest {
        val http = MockHttp(ok("""{"value":[]}"""))
        val window = Window(Instant.parse("2026-11-01T00:00:00.5Z"), Instant.parse("2026-12-01T03:00:00+03:00"))

        assertEquals(emptyList(), calendar(http).list(window))

        val params = http.requests.single().url.parameters
        assertEquals("2026-11-01T00:00:00Z", params["startDateTime"])
        assertEquals("2026-12-01T00:00:00Z", params["endDateTime"])
        assertEquals("100", params["\$top"])
        assertEquals(GRAPH_SELECT, params["\$select"])
    }

    @Test
    fun defaultCalendarIsOneGet() = runTest {
        val http = MockHttp(
            ok("""{"id": "AAA", "name": "Календарь", "owner": {"name": "Иван Петров", "address": "ipetrov@example.com"}}"""),
        )

        val info = calendar(http).defaultCalendar()

        assertEquals(TeamsCalendarInfo("AAA", "Календарь", "Иван Петров", "ipetrov@example.com"), info)
        val request = http.requests.single()
        assertEquals("GET", request.method.value)
        assertEquals("$GRAPH_API_ENDPOINT/me/calendar", request.url.toString().substringBefore('?'))
        assertEquals("Bearer T1", request.headers["Authorization"])
    }
}
