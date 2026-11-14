package app.gcaltolotus.adapters.google

import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.Window
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

/** Ответ подставного сервера: статус и тело JSON. */
internal data class Reply(val status: HttpStatusCode, val body: String = "{}")

internal fun ok(body: String) = Reply(HttpStatusCode.OK, body)

/** Записанный запрос: метод, адрес, заголовок Authorization, тело. */
internal data class Seen(val method: HttpMethod, val url: io.ktor.http.Url, val auth: String?, val body: String?)

/**
 * Подставной Google: [handler] отвечает на каждый запрос, все запросы пишутся в [seen],
 * токены выдаются по порядку («t1», «t2», …), [refreshes] — вызовы с forceRefresh.
 */
internal class FakeGoogle(
    private val calendarId: String = "primary",
    private val handler: (Seen) -> Reply,
) {
    val seen = mutableListOf<Seen>()
    val refreshes = mutableListOf<Boolean>()
    private var tokens = 0

    private val engine = MockEngine { request: HttpRequestData ->
        val s = Seen(
            request.method,
            request.url,
            request.headers[HttpHeaders.Authorization],
            (request.body as? TextContent)?.text,
        )
        seen += s
        val reply = handler(s)
        respond(
            reply.body,
            reply.status,
            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    val adapter = GoogleCalendarAdapter(
        http = HttpClient(engine),
        accessToken = { force ->
            refreshes += force
            tokens += 1
            "t$tokens"
        },
        calendarId = calendarId,
    )
}

private val WINDOW = Window(Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z"))

private const val EVENTS = "https://www.googleapis.com/calendar/v3/calendars/primary/events"

class GoogleCalendarAdapterTest {
    @Test
    fun systemIsGoogle() {
        assertEquals(System.GOOGLE, FakeGoogle { ok("{}") }.adapter.system)
    }

    @Test
    fun listSendsWindowAndFollowsPageToken() = runTest {
        val google = FakeGoogle { s ->
            if (s.url.parameters["pageToken"] == null) {
                ok(
                    """{"nextPageToken": "p2", "items": [
                        {"id": "a", "summary": "A", "start": {"date": "2026-11-13"}, "end": {"date": "2026-11-14"}}]}""",
                )
            } else {
                ok("""{"items": [{"id": "b", "start": {"date": "2026-11-15"}, "end": {"date": "2026-11-16"}}]}""")
            }
        }

        val listed = google.adapter.list(WINDOW)

        assertEquals(listOf("a", "b"), listed.map { it.id })
        val first = google.seen[0]
        assertEquals(HttpMethod.Get, first.method)
        assertEquals(EVENTS, first.url.toString().substringBefore('?'))
        assertEquals("2026-11-01T00:00:00+00:00", first.url.parameters["timeMin"])
        assertEquals("2026-12-01T00:00:00+00:00", first.url.parameters["timeMax"])
        assertEquals("true", first.url.parameters["singleEvents"])
        assertEquals("250", first.url.parameters["maxResults"])
        assertEquals("Bearer t1", first.auth)
        assertEquals("p2", google.seen[1].url.parameters["pageToken"])
        assertEquals(2, google.seen.size)
    }

    @Test
    fun listSkipsBrokenEvents() = runTest {
        val google = FakeGoogle {
            ok(
                """{"items": [
                    {"id": "broken", "summary": "No times"},
                    {"id": "bad-date", "start": {"date": "13.11.2026"}, "end": {"date": "2026-11-14"}},
                    {"id": "good", "start": {"date": "2026-11-13"}, "end": {"date": "2026-11-14"}}]}""",
            )
        }

        assertEquals(listOf("good"), google.adapter.list(WINDOW).map { it.id })
    }

    @Test
    fun calendarIdIsPartOfPath() = runTest {
        val google = FakeGoogle(calendarId = "team@group.calendar.google.com") { ok("""{"items": []}""") }

        google.adapter.list(WINDOW)

        assertEquals(
            listOf("calendar", "v3", "calendars", "team@group.calendar.google.com", "events"),
            google.seen[0].url.segments,
        )
    }

    @Test
    fun createPostsBodyAndReturnsId() = runTest {
        val google = FakeGoogle { ok("""{"id": "new-1"}""") }
        val copy = CalendarEvent(
            title = "Standup",
            start = at("2026-11-13T07:00:00Z"),
            end = at("2026-11-13T07:15:00Z"),
            source = System.TEAMS,
        )

        val id = google.adapter.create(copy)

        assertEquals("new-1", id)
        val s = google.seen.single()
        assertEquals(HttpMethod.Post, s.method)
        assertEquals(EVENTS, s.url.toString())
        assertEquals(eventToGoogle(copy), obj(s.body!!))
    }

    @Test
    fun getReturnsEvent() = runTest {
        val google = FakeGoogle {
            ok("""{"id": "g-late", "summary": "Release night", "start": {"date": "2026-11-13"}, "end": {"date": "2026-11-14"}}""")
        }

        val event = google.adapter.get("g-late")

        assertEquals("Release night", event?.title)
        assertEquals("$EVENTS/g-late", google.seen.single().url.toString())
    }

    @Test
    fun getMissingOrCancelledIsNull() = runTest {
        for (reply in listOf(
            Reply(HttpStatusCode.NotFound),
            Reply(HttpStatusCode.Gone),
            ok("""{"id": "x", "status": "cancelled"}"""),
        )) {
            assertNull(FakeGoogle { reply }.adapter.get("x"), reply.toString())
        }
    }

    @Test
    fun getOtherErrorsPropagate() = runTest {
        val google = FakeGoogle { Reply(HttpStatusCode.InternalServerError, """{"error": "boom"}""") }

        val error = assertFailsWith<GoogleApiException> { google.adapter.get("x") }

        assertEquals(500, error.status)
    }

    @Test
    fun updatePatchesSyncedFieldsWithoutNotifications() = runTest {
        val google = FakeGoogle { ok("""{"id": "g-copy"}""") }
        val changed = CalendarEvent(
            title = "Budget review v2",
            start = at("2026-11-13T09:30:00Z"),
            end = at("2026-11-13T10:30:00Z"),
            description = "New agenda",
            source = System.DOMINO,
            isPrivate = true,
        )

        google.adapter.update("g-copy", changed)

        val s = google.seen.single()
        assertEquals(HttpMethod.Patch, s.method)
        assertEquals("$EVENTS/g-copy", s.url.toString().substringBefore('?'))
        assertEquals("none", s.url.parameters["sendUpdates"])
        val body = obj(s.body!!)
        assertEquals(JsonPrimitive("[LN] Budget review v2"), body["summary"])
        assertEquals(JsonPrimitive("New agenda"), body["description"])
        assertEquals(JsonPrimitive(""), body["location"])
        assertEquals(
            obj("""{"dateTime": "2026-11-13T09:30:00+00:00", "timeZone": "UTC"}"""),
            body["start"],
        )
        assertTrue("extendedProperties" !in body && "visibility" !in body)
    }

    @Test
    fun updateErrorsPropagate() = runTest {
        val google = FakeGoogle { Reply(HttpStatusCode.NotFound) }

        assertFailsWith<GoogleApiException> {
            google.adapter.update(
                "x",
                CalendarEvent(title = "A", start = day("2026-11-13"), end = day("2026-11-14")),
            )
        }
    }

    @Test
    fun deleteAlreadyDeletedIsOk() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Gone, HttpStatusCode.NoContent)) {
            val google = FakeGoogle { Reply(status, "") }

            google.adapter.delete("x")

            val s = google.seen.single()
            assertEquals(HttpMethod.Delete, s.method)
            assertEquals("$EVENTS/x", s.url.toString().substringBefore('?'))
            assertEquals("none", s.url.parameters["sendUpdates"])
        }
    }

    @Test
    fun deleteOtherErrorsPropagate() = runTest {
        val google = FakeGoogle { Reply(HttpStatusCode.Forbidden) }

        val error = assertFailsWith<GoogleApiException> { google.adapter.delete("x") }

        assertEquals(403, error.status)
    }

    @Test
    fun unauthorizedRefreshesTokenOnceAndRetries() = runTest {
        val google = FakeGoogle { s ->
            if (s.auth == "Bearer t1") Reply(HttpStatusCode.Unauthorized) else ok("""{"id": "new-1"}""")
        }

        val id = google.adapter.create(
            CalendarEvent(title = "A", start = day("2026-11-13"), end = day("2026-11-14")),
        )

        assertEquals("new-1", id)
        assertEquals(listOf(false, true), google.refreshes)
        assertEquals(listOf("Bearer t1", "Bearer t2"), google.seen.map { it.auth })
        // Повтор с тем же телом.
        assertEquals(google.seen[0].body, google.seen[1].body)
    }

    @Test
    fun secondUnauthorizedFails() = runTest {
        val google = FakeGoogle { Reply(HttpStatusCode.Unauthorized) }

        val error = assertFailsWith<GoogleApiException> { google.adapter.list(WINDOW) }

        assertEquals(401, error.status)
        assertEquals(listOf(false, true), google.refreshes)
        assertEquals(2, google.seen.size)
    }

    @Test
    fun listCalendarsReadsAllPages() = runTest {
        val google = FakeGoogle { s ->
            if (s.url.parameters["pageToken"] == null) {
                ok(
                    """{"nextPageToken": "c2", "items": [
                        {"id": "me@x", "summary": "Я", "timeZone": "Europe/Moscow",
                         "accessRole": "owner", "primary": true, "selected": true}]}""",
                )
            } else {
                ok(
                    """{"items": [
                        {"id": "hol@x", "summary": "Праздники", "description": "Госпраздники",
                         "accessRole": "reader"},
                        {"id": "team@x", "accessRole": "writer"}]}""",
                )
            }
        }

        val calendars = google.adapter.listCalendars()

        assertEquals(
            listOf(
                GoogleCalendarInfo("me@x", "Я", "", "Europe/Moscow", "owner", primary = true, selected = true),
                GoogleCalendarInfo("hol@x", "Праздники", "Госпраздники", null, "reader", primary = false, selected = false),
                GoogleCalendarInfo("team@x", "team@x", "", null, "writer", primary = false, selected = false),
            ),
            calendars,
        )
        assertEquals(listOf(true, false, true), calendars.map { it.writable })
        assertEquals(
            "https://www.googleapis.com/calendar/v3/users/me/calendarList",
            google.seen[0].url.toString(),
        )
        assertEquals("c2", google.seen[1].url.parameters["pageToken"])
    }

    @Test
    fun primaryCalendarReadsCalendarsPrimary() = runTest {
        val google = FakeGoogle { ok("""{"id": "me@x", "summary": "me@x", "timeZone": "Europe/Moscow"}""") }

        val primary = google.adapter.primaryCalendar()

        assertEquals(GoogleCalendarInfo("me@x", "me@x", "", "Europe/Moscow", "owner", primary = true, selected = true), primary)
        assertEquals(HttpMethod.Get, google.seen.single().method)
        assertEquals("https://www.googleapis.com/calendar/v3/calendars/primary", google.seen.single().url.toString())
    }

    @Test
    fun createCalendarUsesPrimaryTimeZone() = runTest {
        val google = FakeGoogle { s ->
            if (s.method == HttpMethod.Get) ok("""{"id": "me@x", "timeZone": "Europe/Moscow"}""")
            else ok("""{"id": "new@group.calendar.google.com", "summary": "Notes", "timeZone": "Europe/Moscow"}""")
        }

        val created = google.adapter.createCalendar("Notes")

        assertEquals(
            GoogleCalendarInfo("new@group.calendar.google.com", "Notes", "", "Europe/Moscow", "owner", primary = false, selected = false),
            created,
        )
        assertTrue(created.writable)
        val post = google.seen[1]
        assertEquals(HttpMethod.Post, post.method)
        assertEquals("https://www.googleapis.com/calendar/v3/calendars", post.url.toString())
        val body = kotlinx.serialization.json.Json.parseToJsonElement(post.body!!) as kotlinx.serialization.json.JsonObject
        assertEquals(JsonPrimitive("Notes"), body["summary"])
        assertEquals(JsonPrimitive("Europe/Moscow"), body["timeZone"])
    }

    @Test
    fun createCalendarFallsBackToUtcAndKeepsRequestedName() = runTest {
        val google = FakeGoogle { s ->
            if (s.method == HttpMethod.Get) ok("""{"id": "me@x"}""") else ok("""{"id": "new@x"}""")
        }
        val created = google.adapter.createCalendar("Рабочий")
        assertEquals("Рабочий", created.summary)
        assertTrue("\"timeZone\":\"UTC\"" in google.seen[1].body!!, google.seen[1].body)
    }

    @Test
    fun createCalendarErrorsPropagate() = runTest {
        val google = FakeGoogle { s ->
            if (s.method == HttpMethod.Get) ok("""{"id": "me@x"}""") else Reply(HttpStatusCode.Forbidden, """{"error": "quota"}""")
        }
        val error = assertFailsWith<GoogleApiException> { google.adapter.createCalendar("Notes") }
        assertEquals(403, error.status)
    }
}
