package app.gcaltolotus.adapters.domino

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal const val SERVER = "https://domino.example"
internal const val EVENTS_URL = "$SERVER/mail/user.nsf/api/calendar/events"
internal val OWNER = DominoOwner(distinguishedName = "Ivan Petrov/ACME", email = "IPetrov@example.com")

/** Ответ `GET /api/calendar` (как tests/fixtures/domino_calendar.json). */
internal val CALENDAR_JSON: JsonObject = obj(
    """
    {"calendars": [{
      "owner": {"displayName": "Ivan Petrov", "distinguishedName": "Ivan Petrov/ACME",
                "homeServer": "MAIL/ACME", "email": "IPetrov@example.com"},
      "links": [{"rel": "events", "href": "/mail/user.nsf/api/calendar/events"},
                {"rel": "invitations", "href": "/mail/user.nsf/api/calendar/invitations"}]
    }]}
    """,
)

internal fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

/** Записанный запрос: метод, URL без query, параметры query, тело. */
internal data class Sent(
    val method: HttpMethod,
    val url: String,
    val params: Map<String, String>,
    val body: JsonObject?,
    val authorization: String?,
)

/**
 * Поддельный сервер Domino на MockEngine (порт `FakeSession` из tests/test_domino_adapter.py):
 * список кусками не больше [cap] с перекрытием [overlap] в Content-Range, детали по полному URL
 * события (href), неизвестный URL — 404.
 *
 * [details]: URL → тело ответа (JsonObject) или код ошибки (Int). DELETE по URL из [details] с
 * телом — 200, с кодом — этот код, по неизвестному URL — 404.
 */
internal class FakeDomino(
    val events: List<JsonObject> = emptyList(),
    val details: MutableMap<String, Any> = mutableMapOf(),
    val cap: Int? = null,
    val overlap: Int = 0,
    val calendar: JsonObject = CALENDAR_JSON,
    val contentRange: Boolean = true,
    /** Код, которым отвечать на любой запрос (например, 401). */
    var status: Int? = null,
) {
    val sent = mutableListOf<Sent>()

    val gets: List<Sent> get() = sent.filter { it.method == HttpMethod.Get }
    val posts: List<JsonObject> get() = sent.filter { it.method == HttpMethod.Post }.map { it.body!! }
    val puts: List<Sent> get() = sent.filter { it.method == HttpMethod.Put }
    val deletes: List<String> get() = sent.filter { it.method == HttpMethod.Delete }.map { it.url }

    /** GET-запросы к списку событий. */
    val pageStarts: List<Int>
        get() = gets.filter { it.url == EVENTS_URL }.map { it.params.getValue("start").toInt() }

    val client: HttpClient = HttpClient(MockEngine { request -> handle(request) })

    fun calendar(onSkipped: (JsonObject, Exception) -> Unit = { _, _ -> }) =
        DominoCalendar(client, SERVER, "u", "p", onSkipped)

    private fun bodyOf(request: HttpRequestData): JsonObject? = when (val content = request.body) {
        is TextContent -> obj(content.text)
        is OutgoingContent.ByteArrayContent -> obj(content.bytes().decodeToString())
        else -> null
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.json(
        payload: JsonObject?,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
    ) = respond(
        payload?.toString() ?: "",
        HttpStatusCode.fromValue(status),
        headersOf(
            *(headers + (HttpHeaders.ContentType to "application/json"))
                .map { (k, v) -> k to listOf(v) }.toTypedArray(),
        ),
    )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.handle(request: HttpRequestData) =
        run {
            val url = request.url.toString().substringBefore('?')
            val params = request.url.parameters.names().associateWith { request.url.parameters[it]!! }
            sent += Sent(request.method, url, params, bodyOf(request), request.headers[HttpHeaders.Authorization])
            status?.let { return@run json(null, it) }
            when (request.method) {
                HttpMethod.Get -> get(url, params)
                HttpMethod.Post -> json(obj("""{"events": [{"id": "NEWUID"}]}"""))
                HttpMethod.Put -> json(bodyOf(request))
                HttpMethod.Delete -> {
                    val value = details.remove(url)
                    json(null, if (value == null) 404 else value as? Int ?: 200)
                }
                else -> json(null, 405)
            }
        }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.get(
        url: String,
        params: Map<String, String>,
    ) = when {
        url.endsWith("/api/calendar") -> json(calendar)
        url == EVENTS_URL -> {
            val start = params.getValue("start").toInt()
            val count = params.getValue("count").toInt()
            val size = minOf(count, cap ?: count)
            val page = events.drop(start).take(size)
            if (page.isEmpty()) {
                json(null) // Domino отдаёт пустое тело, если событий нет
            } else {
                val last = start + page.size - 1 - overlap
                json(
                    JsonObject(mapOf("events" to JsonArray(page))),
                    headers = if (contentRange) mapOf("Content-Range" to "items $start-$last") else emptyMap(),
                )
            }
        }
        url in details -> when (val value = details.getValue(url)) {
            is Int -> json(null, value)
            else -> json(value as JsonObject)
        }
        else -> json(obj("""{"code": 404, "text": "Not found", "cserror": 1028}"""), 404)
    }
}
