package app.gcaltolotus.adapters.teams

import app.gcaltolotus.sync.Calendar
import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.Window
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.content.TextContent
import io.ktor.http.takeFrom
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Базовый адрес MS Graph. */
const val GRAPH_API_ENDPOINT: String = "https://graph.microsoft.com/v1.0"

private val GONE_STATUSES = setOf(404, 410)

/** Ответ MS Graph с ошибкой (статус не 2xx). [body] — тело ответа как есть. */
class GraphException(val status: Int, val body: String, message: String = "MS Graph: HTTP $status") :
    Exception(message)

/** Основной календарь пользователя Teams (`GET /me/calendar`): для проверки подключения. */
data class TeamsCalendarInfo(val id: String?, val name: String?, val ownerName: String?, val ownerAddress: String?)

/**
 * Календарь Teams/Outlook через MS Graph (делегированные права, календарь вошедшего
 * пользователя). Порт методов `list`/`create`/`get`/`update`/`delete` класса
 * `MSTeamsCalendarAdapter` из `src/adapters/ms_teams_calendar.py`.
 *
 * Все запросы идут с [GRAPH_PREFER]: время в UTC, тело текстом, неизменяемые id. На ответ 401
 * календарь один раз берёт новый токен через [refreshAccessToken] и повторяет запрос; второй
 * 401 — [GraphException].
 *
 * @param http Ktor-клиент (в тестах — на MockEngine).
 * @param accessToken действующий access-токен (обновляется, если истёк).
 * @param refreshAccessToken принудительно обновить токен (после 401) и вернуть новый.
 * @param log сюда пишутся пропущенные события и предупреждения (по-русски).
 */
class TeamsCalendar(
    private val http: HttpClient,
    private val accessToken: suspend () -> String,
    private val refreshAccessToken: suspend () -> String,
    private val log: (String) -> Unit = {},
) : Calendar {
    /** Календарь, который берёт и обновляет токены через [auth]. */
    constructor(http: HttpClient, auth: TeamsAuth, log: (String) -> Unit = {}) :
        this(http, { auth.accessToken() }, { auth.refresh() }, log)

    override val system: System = System.TEAMS

    /**
     * Все события окна через `/me/calendarView` (повторяющиеся развёрнуты), все страницы по
     * `@odata.nextLink`. Отменённые и отклонённые возвращаются с `ignored`: движок удалит их
     * копии. Событие, которое не удалось разобрать, пропускается с записью в [log].
     */
    override suspend fun list(window: Window): List<CalendarEvent> {
        var url: String? = URLBuilder(GRAPH_API_ENDPOINT).apply {
            appendPathSegments("me", "calendarView")
            parameters.append("startDateTime", graphStamp(window.start))
            parameters.append("endDateTime", graphStamp(window.end))
            parameters.append("\$select", GRAPH_SELECT)
            parameters.append("\$expand", GRAPH_EXPAND)
            parameters.append("\$top", "100")
        }.buildString()
        val events = mutableListOf<CalendarEvent>()
        while (url != null) {
            val page = send(HttpMethod.Get, url)
            for (item in page["value"] as? JsonArray ?: JsonArray(emptyList())) {
                val raw = item as? JsonObject ?: continue
                try {
                    events += eventFromGraph(raw, log)
                } catch (e: IllegalArgumentException) {
                    log("Пропущено событие Teams ${(raw["id"] as? JsonPrimitive)?.contentOrNull}: ${e.message}")
                }
            }
            // nextLink уже содержит все параметры; токен отправляем только в Graph.
            url = (page["@odata.nextLink"] as? JsonPrimitive)?.contentOrNull?.also { next ->
                if (!next.startsWith("$GRAPH_API_ENDPOINT/")) {
                    throw GraphException(0, next, "MS Graph: nextLink ведёт не в Graph: $next")
                }
            }
        }
        return events
    }

    override suspend fun create(event: CalendarEvent): String {
        val created = send(HttpMethod.Post, eventUrl(null), eventToGraph(event))
        return (created["id"] as? JsonPrimitive)?.contentOrNull
            ?: throw GraphException(200, created.toString(), "MS Graph: в ответе на создание нет id")
    }

    /** Событие по id; удалённое (404/410) или отменённое — `null`. */
    override suspend fun get(eventId: String): CalendarEvent? {
        val url = URLBuilder(eventUrl(eventId)).apply {
            parameters.append("\$select", GRAPH_SELECT)
            parameters.append("\$expand", GRAPH_EXPAND)
        }.buildString()
        val raw = try {
            send(HttpMethod.Get, url)
        } catch (e: GraphException) {
            if (e.status in GONE_STATUSES) return null
            throw e
        }
        if ((raw["isCancelled"] as? JsonPrimitive)?.contentOrNull == "true") return null
        return eventFromGraph(raw, log)
    }

    /**
     * PATCH синхронизируемых полей. Graph не умеет править встречу организатора без рассылки
     * обновлений участникам: движок не должен вызывать update для оригиналов с `hasAttendees`.
     */
    override suspend fun update(eventId: String, event: CalendarEvent) {
        send(HttpMethod.Patch, eventUrl(eventId), syncedFields(event))
    }

    /** Удаление; уже удалённое (404/410) — не ошибка. */
    override suspend fun delete(eventId: String) {
        try {
            send(HttpMethod.Delete, eventUrl(eventId))
        } catch (e: GraphException) {
            if (e.status !in GONE_STATUSES) throw e
            log("Событие Teams $eventId уже удалено")
        }
    }

    /** Основной календарь вошедшего пользователя: один `GET /me/calendar` (с обновлением токена на 401). */
    suspend fun defaultCalendar(): TeamsCalendarInfo {
        val raw = send(HttpMethod.Get, "$GRAPH_API_ENDPOINT/me/calendar")
        val owner = raw["owner"] as? JsonObject
        fun JsonObject?.text(key: String) = ((this?.get(key)) as? JsonPrimitive)?.takeIf { it.isString }?.content
        return TeamsCalendarInfo(raw.text("id"), raw.text("name"), owner.text("name"), owner.text("address"))
    }

    private fun eventUrl(eventId: String?): String = URLBuilder(GRAPH_API_ENDPOINT).apply {
        appendPathSegments("me", "events")
        if (eventId != null) appendPathSegments(eventId, encodeSlash = true)
    }.buildString()

    /** Запрос к Graph с токеном; на 401 — одно обновление токена и повтор. Пустой ответ — `{}`. */
    private suspend fun send(method: HttpMethod, url: String, body: JsonObject? = null): JsonObject {
        var token = accessToken()
        var refreshed = false
        while (true) {
            val response = http.request {
                this.method = method
                this.url.takeFrom(url)
                expectSuccess = false
                header(HttpHeaders.Authorization, "Bearer $token")
                header("Prefer", GRAPH_PREFER)
                if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
            val status = response.status.value
            val text = response.bodyAsText()
            if (status == 401 && !refreshed) {
                refreshed = true
                token = refreshAccessToken()
                continue
            }
            if (status !in 200..299) {
                throw GraphException(status, text, "MS Graph: ${method.value} $url -> HTTP $status")
            }
            if (text.isBlank()) return JsonObject(emptyMap())
            return Json.parseToJsonElement(text).jsonObject
        }
    }
}

/** Момент для calendarView: `2026-11-01T00:00:00Z`, секунды без дробной части (как strftime). */
private fun graphStamp(instant: Instant): String {
    val t = instant.toLocalDateTime(TimeZone.UTC)
    fun pad(v: Int, w: Int = 2) = v.toString().padStart(w, '0')
    return "${pad(t.year, 4)}-${pad(t.month.ordinal + 1)}-${pad(t.day)}T" +
        "${pad(t.hour)}:${pad(t.minute)}:${pad(t.second)}Z"
}
