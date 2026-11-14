package app.gcaltolotus.adapters.domino

import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Ноябрь 2026 по Москве (UTC+3), как WINDOW в tests/test_domino_adapter.py. */
internal val WINDOW = Window(Instant.parse("2026-10-31T21:00:00Z"), Instant.parse("2026-11-30T21:00:00Z"))

private fun numbered(count: Int, time: (Int) -> Pair<String, String>): List<JsonObject> = (0 until count).map { i ->
    val (start, end) = time(i)
    obj(
        """{"href": "/mail/user.nsf/api/calendar/events/U$i", "id": "U$i", "summary": "E$i",
            "start": {"date": "2026-11-13", "time": "$start", "utc": true},
            "end": {"date": "2026-11-13", "time": "$end", "utc": true}}""",
    )
}

private fun detailOf(raw: JsonObject, vararg extra: Pair<String, String>) =
    JsonObject(mapOf("events" to kotlinx.serialization.json.JsonArray(listOf(JsonObject(raw + extra.map { (k, v) -> k to JsonPrimitive(v) })))))

private const val INSTANCE_ID = "S1-Lotus_Notes_Generated/20261117T070000Z"

/** Сводка экземпляра серии в списке. */
private val INSTANCE_SUMMARY = obj(
    """{"href": "/mail/user.nsf/api/calendar/events/$INSTANCE_ID", "id": "S1-Lotus_Notes_Generated",
        "summary": "Weekly sync", "recurrenceId": "20261117T070000Z",
        "start": {"date": "2026-11-17", "time": "07:00:00", "utc": true},
        "end": {"date": "2026-11-17", "time": "07:30:00", "utc": true}}""",
)

/** Детальный ответ по URL экземпляра, в котором его нет: только мастер серии. */
private val MASTER_ONLY = obj(
    """{"events": [{"href": "/mail/user.nsf/api/calendar/events/S1-Lotus_Notes_Generated",
        "id": "S1-Lotus_Notes_Generated", "summary": "Weekly sync (series)",
        "description": "Series agenda", "last-modified": "20261113T202614Z",
        "start": {"date": "2026-11-10", "time": "07:00:00", "utc": true},
        "end": {"date": "2026-11-10", "time": "07:30:00", "utc": true}}]}""",
)

class DominoCalendarTest {
    // --- подключение ---

    @Test
    fun connectReadsEventsUrlAndOwnerWithBasicAuth() = runTest {
        val server = FakeDomino()
        val info = server.calendar().connect()
        assertEquals(EVENTS_URL, info.eventsUrl)
        assertEquals(OWNER.copy(displayName = "Ivan Petrov"), info.owner)
        assertEquals(listOf("$SERVER/api/calendar"), server.gets.map { it.url })
        assertEquals("Basic dTpw", server.gets[0].authorization) // base64("u:p")
    }

    @Test
    fun connectWithoutEventsLinkFails() = runTest {
        val server = FakeDomino(calendar = obj("""{"calendars": []}"""))
        assertFailsWith<DominoException> { server.calendar().connect() }
    }

    @Test
    fun calendarInfoIsFetchedOnce() = runTest {
        val server = FakeDomino()
        val calendar = server.calendar()
        calendar.delete("A")
        calendar.delete("B")
        assertEquals(1, server.gets.count { it.url.endsWith("/api/calendar") })
    }

    // --- 401: защита от блокировки ---

    @Test
    fun unauthorizedIsDistinctAndNeverRetried() = runTest {
        val server = FakeDomino(status = 401)
        val calendar = server.calendar()
        assertFailsWith<DominoAuthException> { calendar.list(WINDOW) }
        assertEquals(1, server.sent.size)
        // после 401 адаптер больше не ходит на сервер, пока его не пересоздадут с новым паролем
        server.status = null
        assertFailsWith<DominoAuthException> { calendar.get("X") }
        assertFailsWith<DominoAuthException> { calendar.connect() }
        assertEquals(1, server.sent.size)
    }

    @Test
    fun unauthorizedDuringDetailsAbortsList() = runTest {
        val many = numbered(3) { "1$it:00:00" to "1$it:30:00" }
        val server = FakeDomino(many)
        server.details["$SERVER/mail/user.nsf/api/calendar/events/U0"] = 401
        assertFailsWith<DominoAuthException> { server.calendar().list(WINDOW) }
        assertEquals("$SERVER/mail/user.nsf/api/calendar/events/U0", server.sent.last().url)
    }

    @Test
    fun forbiddenIsAnOrdinaryHttpError() = runTest {
        val server = FakeDomino(status = 403)
        val error = assertFailsWith<DominoHttpException> { server.calendar().connect() }
        assertEquals(403, error.status)
    }

    // --- list: страницы ---

    @Test
    fun listPagesByContentRangeUntilEmptyAndDedups() = runTest {
        val many = numbered(7) { "1$it:00:00" to "1$it:30:00" }
        val server = FakeDomino(many, cap = 3, overlap = 1)
        for (raw in many) server.details[SERVER + raw.str("href")] = detailOf(raw, "description" to "d")

        val listed = server.calendar().list(WINDOW)

        assertEquals((0 until 7).map { "U$it" }, listed.map { it.id })
        assertEquals(listOf(0, 2, 4, 6, 7), server.pageStarts) // конец Content-Range + 1, до пустой страницы
        val first = server.gets.first { it.url == EVENTS_URL }.params
        assertEquals("2026-10-31T21:00:00Z", first["since"])
        assertEquals("2026-11-30T21:00:00Z", first["before"])
        assertEquals("100", first["count"])
    }

    @Test
    fun listFallsBackToPageLengthWithoutContentRange() = runTest {
        val many = numbered(5) { "10:00:00" to "11:00:00" }
        val server = FakeDomino(many, cap = 2, contentRange = false)
        for (raw in many) server.details[SERVER + raw.str("href")] = detailOf(raw)

        val listed = server.calendar().list(WINDOW)

        assertEquals(listOf("U0", "U1", "U2", "U3", "U4"), listed.map { it.id })
    }

    @Test
    fun listRequestsAreSequential() = runTest {
        val many = numbered(3) { "1$it:00:00" to "1$it:30:00" }
        val server = FakeDomino(many)
        for (raw in many) server.details[SERVER + raw.str("href")] = detailOf(raw)

        server.calendar().list(WINDOW)

        assertEquals(
            listOf("$SERVER/api/calendar", EVENTS_URL, EVENTS_URL) +
                (0 until 3).map { "$SERVER/mail/user.nsf/api/calendar/events/U$it" },
            server.gets.map { it.url },
        )
    }

    // --- create ---

    @Test
    fun createPostsMarkedUtcEvent() = runTest {
        val server = FakeDomino()
        val copy = CalendarEvent(
            "Release night", utc("2026-11-13T22:00:00Z"), utc("2026-11-13T23:30:00Z"), source = System.GOOGLE,
        )

        assertEquals("NEWUID", server.calendar().create(copy))

        val post = server.sent.single { it.method.value == "POST" }
        assertEquals(EVENTS_URL, post.url)
        val body = post.body!!.getValue("events").jsonArray.single().jsonObject
        assertEquals(eventToDomino(copy), body)
        assertEquals(null, body["class"])
    }

    @Test
    fun createPrivateCopy() = runTest {
        val server = FakeDomino()
        val private = CalendarEvent(
            "Doctor", date("2026-11-13"), date("2026-11-14"), source = System.TEAMS, isPrivate = true,
        )
        server.calendar().create(private)
        val body = server.posts.single().getValue("events").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("private"), body["class"])
    }

    // --- get ---

    @Test
    fun getMissingIsNull() = runTest {
        for (status in listOf(404, 410)) {
            val server = FakeDomino(details = mutableMapOf("$EVENTS_URL/GONE" to status))
            assertNull(server.calendar().get("GONE"))
        }
    }

    @Test
    fun getUnknownIsNull() = runTest {
        assertNull(FakeDomino().calendar().get("NOPE"))
    }

    @Test
    fun getInstanceMissingFromDetailIsNull() = runTest {
        // экземпляр удалён или перенесён: мастер серии вместо него не возвращаем
        val server = FakeDomino(details = mutableMapOf("$EVENTS_URL/$INSTANCE_ID" to MASTER_ONLY))
        assertNull(server.calendar().get(INSTANCE_ID))
    }

    @Test
    fun listInstanceMissingFromDetailAborts() = runTest {
        // как с пропавшим событием: поля мастера в экземпляр не подмешиваем
        val server = FakeDomino(listOf(INSTANCE_SUMMARY))
        server.details["$EVENTS_URL/$INSTANCE_ID"] = MASTER_ONLY
        assertFailsWith<DominoException> { server.calendar().list(WINDOW) }
    }

    @Test
    fun getOtherErrorsPropagate() = runTest {
        val server = FakeDomino(details = mutableMapOf("$EVENTS_URL/X" to 500))
        assertEquals(500, assertFailsWith<DominoHttpException> { server.calendar().get("X") }.status)
    }

    // --- update ---

    @Test
    fun updateMissingEventRaises() = runTest {
        val event = CalendarEvent("x", date("2026-01-01"), date("2026-01-02"))
        val error = assertFailsWith<DominoHttpException> { FakeDomino().calendar().update("GONE", event) }
        assertEquals(404, error.status)
    }

    @Test
    fun updateInstanceMissingFromDetailDoesNotPutMaster() = runTest {
        // иначе PUT мастера по URL экземпляра перезаписал бы всю серию
        val server = FakeDomino(details = mutableMapOf("$EVENTS_URL/$INSTANCE_ID" to MASTER_ONLY))
        val event = CalendarEvent("Weekly sync v2", utc("2026-11-17T08:00:00Z"), utc("2026-11-17T08:45:00Z"))
        assertFailsWith<DominoException> { server.calendar().update(INSTANCE_ID, event) }
        assertEquals(emptyList(), server.puts)
    }

    // --- delete ---

    @Test
    fun deleteInstanceByHref() = runTest {
        val id = "C1-Lotus_Notes_Generated/20261117T070000Z"
        val server = FakeDomino(details = mutableMapOf("$EVENTS_URL/$id" to obj("{}")))
        server.calendar().delete(id)
        assertEquals(listOf("$EVENTS_URL/$id"), server.deletes)
    }

    @Test
    fun deleteAlreadyDeletedIsOk() = runTest {
        val server = FakeDomino()
        server.calendar().delete("GONE") // 404 (cserror 1031) — уже удалено
        assertEquals(listOf("$EVENTS_URL/GONE"), server.deletes)
    }

    @Test
    fun deleteOtherErrorsPropagate() = runTest {
        val server = FakeDomino(details = mutableMapOf("$EVENTS_URL/X" to 403))
        assertFailsWith<DominoHttpException> { server.calendar().delete("X") }
    }
}
