package app.gcaltolotus.adapters.domino

import app.gcaltolotus.sync.Calendar
import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.Window
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlin.io.encoding.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Ошибка обращения к Domino. */
open class DominoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Сервер ответил 401: неверный логин или пароль. Повторять нельзя — несколько неудачных входов
 * подряд блокируют учётную запись. Приложение должно выключить Lotus до повторного ввода пароля
 * (ADR 0002); экземпляр [DominoCalendar] после 401 больше не ходит на сервер.
 */
class DominoAuthException(message: String) : DominoException(message)

/** Сервер ответил ошибкой, кроме 401 (и кроме 404/410 там, где они значат «удалено»). */
class DominoHttpException(
    val status: Int,
    val method: String,
    val url: String,
    val body: String,
) : DominoException("Domino: HTTP $status на $method $url: ${body.take(200)}")

/** Данные календаря из `GET /api/calendar`: адрес событий и владелец. */
data class DominoCalendarInfo(val eventsUrl: String, val owner: DominoOwner?)

/**
 * Календарь Domino через Domino REST (Calendar API сервера 14.5.1). Порт [Calendar]-части
 * `DominoRestAdapter` из src/adapters/domino_rest.py.
 *
 * [client] внедряется (в тестах — MockEngine); на него ложатся таймауты и TLS. Клиент не должен
 * повторять запросы сам (без `HttpRequestRetry` на 401) и не должен иметь своей авторизации:
 * Basic-заголовок ставит адаптер.
 *
 * Защита от блокировки учётной записи:
 * - 401 — [DominoAuthException], без повтора; после него экземпляр отвечает той же ошибкой, не
 *   обращаясь к серверу (для нового пароля создайте новый экземпляр);
 * - запросы идут строго по одному (в том числе при одновременных вызовах из разных корутин).
 *
 * Адрес событий и владелец календаря читаются из `GET /api/calendar` при первом обращении
 * (или явным [connect]) и запоминаются.
 *
 * @param serverUrl адрес сервера, например `https://mail.example`.
 * @param onSkipped вызывается для записи списка, которую не удалось разобрать (например, время с
 *   `tzid` без описания зоны); такая запись пропускается, как в Python.
 */
class DominoCalendar(
    private val client: HttpClient,
    serverUrl: String,
    username: String,
    password: String,
    private val onSkipped: (raw: JsonObject, error: Exception) -> Unit = { _, _ -> },
) : Calendar {
    override val system: System = System.DOMINO

    val serverUrl: String = serverUrl.trimEnd('/')

    private val authorization = "Basic " + Base64.encode("$username:$password".encodeToByteArray())
    private val lock = Mutex()
    private var info: DominoCalendarInfo? = null
    private var unauthorized = false

    /**
     * Проверка подключения и чтение календаря: `GET /api/calendar` (всегда идёт на сервер).
     * Подходит для экрана настроек: успех — логин, пароль и адрес верны.
     */
    suspend fun connect(): DominoCalendarInfo {
        val url = urlJoin(serverUrl, "/api/calendar")
        val body = send(HttpMethod.Get, url).ok().json()
        val calendar = (body?.get("calendars") as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw DominoException("Календарь не найден в ответе Domino")
        val events = (calendar["links"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { it.str("rel") == "events" }?.str("href")
            ?: throw DominoException("URL событий не найден в ответе Domino")
        return DominoCalendarInfo(
            eventsUrl = urlJoin(serverUrl, events),
            owner = DominoOwner.fromJson(calendar["owner"] as? JsonObject),
        ).also { info = it }
    }

    private suspend fun calendarInfo(): DominoCalendarInfo = info ?: connect()

    /** URL события; для экземпляра серии id = `<uid>/<recurrenceId>`, как в его href. */
    private fun eventUrl(info: DominoCalendarInfo, eventId: String) = "${info.eventsUrl}/$eventId"

    /**
     * Все события окна с описанием и временем изменения.
     *
     * Список отдаёт только сводку (без description и last-modified), поэтому для каждого
     * события делается ещё один GET по его href: N+1 запросов. Время берётся из сводки (UTC), из
     * деталей — только [DETAIL_FIELDS]. Отменённые и уведомления деталей не имеют (404) и
     * возвращаются без них. Если событие пропало между запросами или детали не прочитались —
     * исключение: без деталей описание было бы пустым и затёрло бы копии.
     */
    override suspend fun list(window: Window): List<CalendarEvent> {
        val info = calendarInfo()
        val events = mutableListOf<CalendarEvent>()
        for (raw in mergeMultiDay(listSummaries(info.eventsUrl, window))) {
            val event = try {
                eventFromDomino(raw, info.owner)
            } catch (e: IllegalArgumentException) {
                onSkipped(raw, e)
                continue
            }
            if (event.ignored) {
                events += event // отменённое или уведомление: детали не нужны
                continue
            }
            val href = raw.str("href")
            val url = if (!href.isNullOrEmpty()) urlJoin(serverUrl, href) else eventUrl(info, event.id ?: "")
            // событие (или экземпляр серии) удалили между запросами: без деталей описание было
            // бы пустым, а детали мастера исказили бы экземпляр
            val found = detail(url)?.let { pick(it.eventList(), event.id ?: "") }
                ?: throw DominoException("Событие Domino ${event.id} пропало при чтении деталей")
            val merged = JsonObject(raw + DETAIL_FIELDS.mapNotNull { k -> found[k]?.let { k to it } })
            events += eventFromDomino(merged, info.owner)
        }
        return events
    }

    /**
     * Сводки событий окна со всех страниц. Сервер отдаёт не больше ~40 событий за запрос,
     * сколько бы ни просили в `count`, а `Content-Range` ("items 0-43") считает не события:
     * листаем с конца диапазона + 1 до пустой страницы, а дубли на стыках страниц убираем по
     * (href, начало).
     */
    private suspend fun listSummaries(eventsUrl: String, window: Window): List<JsonObject> {
        val entries = mutableListOf<JsonObject>()
        val seen = mutableSetOf<Pair<String?, Any?>>()
        var offset = 0
        while (true) {
            val reply = send(
                HttpMethod.Get,
                eventsUrl,
                query = listOf(
                    "since" to utcParam(window.start),
                    "before" to utcParam(window.end),
                    "start" to offset.toString(),
                    "count" to PAGE_SIZE.toString(),
                ),
            ).ok()
            val page = reply.json()?.eventList().orEmpty()
            if (page.isEmpty()) return entries
            for (raw in page) {
                val key = (raw.str("href") ?: raw.str("id")) to raw["start"]
                if (seen.add(key)) entries += raw
            }
            offset = nextStart(reply.contentRange, offset, page.size)
        }
    }

    /** Детальный GET события: `{"timezones": [...], "events": [...]}`; `null`, если удалено. */
    private suspend fun detail(url: String): JsonObject? {
        val reply = send(HttpMethod.Get, url)
        if (reply.status in GONE_STATUSES) return null
        return reply.ok().json()
    }

    override suspend fun create(event: CalendarEvent): String {
        val info = calendarInfo()
        val body = JsonObject(mapOf("events" to JsonArray(listOf(eventToDomino(event)))))
        val created = send(HttpMethod.Post, info.eventsUrl, body = body).ok().json()?.eventList().orEmpty()
        return created.firstOrNull()?.str("id")?.ifEmpty { null }
            ?: throw DominoException("ID события не найден в ответе Domino")
    }

    /** Событие по id (детальный GET, время по `timezones`); удалённое или отменённое — `null`. */
    override suspend fun get(eventId: String): CalendarEvent? {
        val info = calendarInfo()
        val body = detail(eventUrl(info, eventId)) ?: return null
        val raw = pick(body.eventList(), eventId) ?: return null
        if (raw.str("status") == "cancelled") return null
        return eventFromDomino(raw, info.owner, body["timezones"] as? JsonArray)
    }

    /**
     * GET деталей -> замена синхронизируемых полей -> PUT того же тела по тому же URL.
     * Остальное (sequence, recurrenceId, участники, timezones) уходит как пришло; PUT по URL
     * экземпляра меняет только этот экземпляр. Отсутствующее событие — [DominoHttpException].
     */
    override suspend fun update(eventId: String, event: CalendarEvent) {
        val url = eventUrl(calendarInfo(), eventId)
        val body = send(HttpMethod.Get, url).ok().json() ?: JsonObject(emptyMap())
        val target = pick(body.eventList(), eventId)
            ?: throw DominoException("Событие Domino $eventId не найдено в ответе")
        val changed = JsonObject(
            target + mapOf(
                "summary" to JsonPrimitive(event.markedTitle()),
                "location" to JsonPrimitive(event.location),
                "description" to JsonPrimitive(event.description),
            ) + dominoTimes(event),
        )
        send(HttpMethod.Put, url, body = JsonObject(body + ("events" to JsonArray(listOf(changed))))).ok()
    }

    /** DELETE по URL события (для экземпляра — только он). 404/410 — уже удалено. */
    override suspend fun delete(eventId: String) {
        val reply = send(HttpMethod.Delete, eventUrl(calendarInfo(), eventId))
        if (reply.status in GONE_STATUSES) return
        reply.ok()
    }

    // --- HTTP ---

    private class Reply(
        val method: HttpMethod,
        val url: String,
        val status: Int,
        val text: String,
        val contentRange: String?,
    ) {
        fun ok(): Reply {
            if (status !in 200..299) throw DominoHttpException(status, method.value, url, text)
            return this
        }

        /** Тело как объект JSON; пустое тело — `null` (Domino так отвечает на пустой список). */
        fun json(): JsonObject? {
            if (text.isBlank()) return null
            return Json.parseToJsonElement(text) as? JsonObject
                ?: throw DominoException("Ответ Domino не объект JSON: $method $url")
        }
    }

    /** Один запрос; запросы экземпляра идут строго по одному, после 401 — ни одного. */
    private suspend fun send(
        method: HttpMethod,
        url: String,
        query: List<Pair<String, String>> = emptyList(),
        body: JsonObject? = null,
    ): Reply = lock.withLock {
        if (unauthorized) throw DominoAuthException(AUTH_MESSAGE)
        val response = client.request(url) {
            this.method = method
            expectSuccess = false
            header(HttpHeaders.Authorization, authorization)
            header(HttpHeaders.Accept, "application/json")
            for ((name, value) in query) parameter(name, value)
            if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
        if (response.status.value == 401) {
            unauthorized = true
            throw DominoAuthException(AUTH_MESSAGE)
        }
        Reply(method, url, response.status.value, response.bodyAsText(), response.headers["Content-Range"])
    }

    private fun JsonObject.eventList(): List<JsonObject> =
        (this["events"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private companion object {
        /** Сервер всё равно отдаёт не больше ~40 событий за запрос. */
        const val PAGE_SIZE = 100
        val GONE_STATUSES = setOf(404, 410)
        const val AUTH_MESSAGE = "Domino: неверный логин или пароль (401). Lotus выключен до повторного ввода пароля"
    }
}
