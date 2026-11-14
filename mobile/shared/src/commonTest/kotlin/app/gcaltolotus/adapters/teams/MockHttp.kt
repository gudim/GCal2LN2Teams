package app.gcaltolotus.adapters.teams

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Записанный запрос: метод, адрес, заголовки и тело текстом. */
data class Recorded(
    val method: HttpMethod,
    val url: Url,
    val headers: Headers,
    val body: String,
    val contentType: String?,
) {
    val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
}

/** Ответ из очереди [MockHttp]. */
data class Reply(val status: Int = 200, val body: String = "")

fun ok(json: String) = Reply(200, json)

/**
 * HTTP-клиент на MockEngine: отвечает по очереди [replies] и записывает запросы. Сеть не
 * используется; кончились ответы — тест падает.
 */
class MockHttp(vararg replies: Reply) {
    private val queue = ArrayDeque(replies.toList())
    val requests = mutableListOf<Recorded>()

    val client = HttpClient(MockEngine { request ->
        requests += Recorded(
            request.method,
            request.url,
            request.headers,
            request.body.toByteArray().decodeToString(),
            request.body.contentType?.toString(),
        )
        val reply = queue.removeFirstOrNull() ?: error("Нет ответа для ${request.method} ${request.url}")
        respond(
            reply.body,
            HttpStatusCode.fromValue(reply.status),
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    })

    val remaining: Int get() = queue.size
}

/** Часы, которые показывают заданное время (его можно двигать). */
class FakeClock(var now: Instant) : Clock {
    override fun now(): Instant = now
}
