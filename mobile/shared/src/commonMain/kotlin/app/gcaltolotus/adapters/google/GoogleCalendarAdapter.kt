package app.gcaltolotus.adapters.google

import app.gcaltolotus.sync.Calendar
import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.When
import app.gcaltolotus.sync.Window
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Источник access-токена Google. `forceRefresh = false` — обычный вызов (можно отдать
 * сохранённый токен); `true` — сервер ответил 401, токен нужно обновить (refresh-токеном или
 * повторным входом). Реализует слой OAuth.
 */
typealias GoogleAccessToken = suspend (forceRefresh: Boolean) -> String

/** Ошибка Google Calendar API: HTTP-статус и тело ответа. 401 — вход в Google не удался. */
class GoogleApiException(val status: Int, val body: String, message: String) : Exception(message)

/**
 * Календарь из списка календарей аккаунта (`users/me/calendarList`) — для выбора календаря.
 *
 * @property summary название; если его нет — [id] (как `_calendar_json` в веб-версии).
 * @property accessRole `owner`, `writer`, `reader`, `freeBusyReader`.
 */
data class GoogleCalendarInfo(
    val id: String,
    val summary: String,
    val description: String,
    val timeZone: String?,
    val accessRole: String?,
    val primary: Boolean,
    val selected: Boolean,
) {
    /** Можно ли писать копии в этот календарь. */
    val writable: Boolean get() = accessRole in WRITABLE_ROLES

    companion object {
        val WRITABLE_ROLES: Set<String> = setOf("owner", "writer")
    }
}

/**
 * Выбор календаря Google: календари аккаунта и создание нового. Реализует [GoogleCalendarAdapter];
 * отдельный интерфейс — чтобы оркестрацию можно было проверить без HTTP.
 */
interface GoogleCalendarDirectory {
    /** Календари аккаунта (все страницы). */
    suspend fun listCalendars(): List<GoogleCalendarInfo>

    /** Создать дополнительный календарь [summary] (владелец — пользователь). */
    suspend fun createCalendar(summary: String): GoogleCalendarInfo
}

/**
 * Google Calendar API v3 напрямую через Ktor. Порт `GoogleCalendarAdapter` из
 * `src/adapters/google_calendar.py` (только интерфейс [Calendar], список календарей и создание календаря; вход —
 * забота [accessToken]).
 *
 * Каждый запрос идёт с `Authorization: Bearer <accessToken(false)>`; на 401 токен
 * запрашивается ещё раз с `forceRefresh = true`, и запрос повторяется один раз. Ошибки HTTP —
 * [GoogleApiException].
 *
 * @param http клиент Ktor (в тестах — с MockEngine); `expectSuccess` клиента не важен.
 * @param calendarId календарь, с которым работает адаптер; `primary` — основной.
 * @param baseUrl корень API (для тестов и прокси).
 * @param log журнал (пропущенные при чтении события).
 */
class GoogleCalendarAdapter(
    private val http: HttpClient,
    private val accessToken: GoogleAccessToken,
    val calendarId: String = "primary",
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val log: (String) -> Unit = {},
) : Calendar, GoogleCalendarDirectory {
    override val system: System = System.GOOGLE

    /** Все события в окне, со всех страниц. Повторяющиеся развёрнуты в экземпляры. */
    override suspend fun list(window: Window): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        var pageToken: String? = null
        do {
            val page = call(HttpMethod.Get, eventsPath()) {
                url.parameters.apply {
                    append("timeMin", When.Timed(window.start).isoformat())
                    append("timeMax", When.Timed(window.end).isoformat())
                    append("singleEvents", "true")
                    append("maxResults", "250")
                    pageToken?.let { append("pageToken", it) }
                }
            }.json()
            for (raw in page.items()) {
                try {
                    events += eventFromGoogle(raw)
                } catch (e: IllegalArgumentException) {
                    log("Пропущено событие Google ${raw.string("id")}: ${e.message}")
                }
            }
            pageToken = page.string("nextPageToken")
        } while (!pageToken.isNullOrEmpty())
        return events
    }

    override suspend fun create(event: CalendarEvent): String {
        val created = call(HttpMethod.Post, eventsPath()) { json(eventToGoogle(event)) }.json()
        return created.string("id") ?: throw GoogleApiException(200, created.toString(), "Нет id у созданного события")
    }

    /** Событие по id; удалённое (404/410) или отменённое — `null`. */
    override suspend fun get(eventId: String): CalendarEvent? {
        val response = call(HttpMethod.Get, eventsPath(eventId), allow = GONE)
        if (response.status.value in GONE) return null
        val raw = response.json()
        if (raw.string("status") == "cancelled") return null
        return eventFromGoogle(raw)
    }

    /** patch только синхронизируемых полей; участникам письма не рассылаются. */
    override suspend fun update(eventId: String, event: CalendarEvent) {
        call(HttpMethod.Patch, eventsPath(eventId)) {
            url.parameters.append("sendUpdates", "none")
            json(syncedFieldsToGoogle(event))
        }
    }

    /** Удаление без писем участникам; уже удалённое (404/410) — не ошибка. */
    override suspend fun delete(eventId: String) {
        val response = call(HttpMethod.Delete, eventsPath(eventId), allow = GONE) {
            url.parameters.append("sendUpdates", "none")
        }
        if (response.status.value in GONE) log("Событие Google $eventId уже удалено")
    }

    /** Календари аккаунта (все страницы) — для выбора календаря. */
    override suspend fun listCalendars(): List<GoogleCalendarInfo> {
        val calendars = mutableListOf<GoogleCalendarInfo>()
        var pageToken: String? = null
        do {
            val page = call(HttpMethod.Get, listOf("users", "me", "calendarList")) {
                pageToken?.let { url.parameters.append("pageToken", it) }
            }.json()
            for (raw in page.items()) {
                val id = raw.string("id") ?: continue
                calendars += GoogleCalendarInfo(
                    id = id,
                    summary = raw.string("summary")?.takeIf { it.isNotEmpty() } ?: id,
                    description = raw.string("description") ?: "",
                    timeZone = raw.string("timeZone"),
                    accessRole = raw.string("accessRole"),
                    primary = raw.flag("primary"),
                    selected = raw.flag("selected"),
                )
            }
            pageToken = page.string("nextPageToken")
        } while (!pageToken.isNullOrEmpty())
        return calendars
    }

    /**
     * Основной календарь (`GET calendars/primary`): один дешёвый запрос, подходит для проверки
     * входа. [GoogleCalendarInfo.summary] у основного календаря — обычно адрес аккаунта.
     */
    suspend fun primaryCalendar(): GoogleCalendarInfo {
        val raw = call(HttpMethod.Get, listOf("calendars", "primary")).json()
        val id = raw.string("id") ?: "primary"
        return GoogleCalendarInfo(
            id = id,
            summary = raw.string("summary")?.takeIf { it.isNotEmpty() } ?: id,
            description = raw.string("description") ?: "",
            timeZone = raw.string("timeZone"),
            accessRole = "owner",
            primary = true,
            selected = true,
        )
    }

    /**
     * Создать дополнительный календарь (владелец — пользователь) в часовом поясе основного
     * (`create_calendar` в Python): `GET calendars/primary`, затем `POST calendars`.
     */
    override suspend fun createCalendar(summary: String): GoogleCalendarInfo {
        val timeZone = primaryCalendar().timeZone ?: "UTC"
        val body = JsonObject(mapOf("summary" to JsonPrimitive(summary), "timeZone" to JsonPrimitive(timeZone)))
        val created = call(HttpMethod.Post, listOf("calendars")) { json(body) }.json()
        val id = created.string("id") ?: throw GoogleApiException(200, created.toString(), "Нет id у созданного календаря")
        return GoogleCalendarInfo(
            id = id,
            summary = created.string("summary")?.takeIf { it.isNotEmpty() } ?: summary,
            description = "",
            timeZone = created.string("timeZone") ?: timeZone,
            accessRole = "owner",
            primary = false,
            selected = false,
        )
    }

    private fun eventsPath(eventId: String? = null): List<String> =
        listOfNotNull("calendars", calendarId, "events", eventId)

    /**
     * Запрос к API с токеном и одним повтором после 401. Успех или статус из [allow] —
     * ответ, иначе [GoogleApiException].
     */
    private suspend fun call(
        method: HttpMethod,
        path: List<String>,
        allow: Set<Int> = emptySet(),
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        suspend fun send(forceRefresh: Boolean): HttpResponse {
            val token = accessToken(forceRefresh)
            return http.request {
                this.method = method
                url.takeFrom(URLBuilder(baseUrl).appendPathSegments(path, encodeSlash = true))
                expectSuccess = false
                configure()
                bearerAuth(token)
            }
        }
        var response = send(forceRefresh = false)
        if (response.status == HttpStatusCode.Unauthorized) response = send(forceRefresh = true)
        if (!response.status.isSuccess() && response.status.value !in allow) {
            val body = response.bodyAsText()
            throw GoogleApiException(
                response.status.value,
                body,
                "Google Calendar API: ${method.value} ${path.joinToString("/")} -> ${response.status}",
            )
        }
        return response
    }

    private fun HttpRequestBuilder.json(body: JsonObject) {
        setBody(TextContent(body.toString(), ContentType.Application.Json))
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.items(): List<JsonObject> =
        (this["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    companion object {
        const val DEFAULT_BASE_URL: String = "https://www.googleapis.com/calendar/v3"

        private val GONE = setOf(404, 410)
    }
}
