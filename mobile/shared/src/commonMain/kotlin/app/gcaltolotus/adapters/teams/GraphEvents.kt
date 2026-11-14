package app.gcaltolotus.adapters.teams

import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.PyText
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.When
import app.gcaltolotus.sync.splitMarker
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// Преобразования событий MS Graph <-> CalendarEvent. Порт event_from_graph/event_to_graph из
// src/adapters/ms_teams_calendar.py; ошибки разбора — IllegalArgumentException (ValueError/KeyError).

/** Время в UTC, описание текстом, а id неизменяемые (не меняются при переносе между папками). */
const val GRAPH_PREFER: String =
    "outlook.timezone=\"UTC\", outlook.body-content-type=\"text\", IdType=\"ImmutableId\""

const val GRAPH_SELECT: String =
    "id,subject,body,bodyPreview,location,start,end,isAllDay,isCancelled,iCalUId,type," +
        "originalStart,isOrganizer,attendees,sensitivity,lastModifiedDateTime,responseStatus"

/** Пространство имён скрытой метки копии (именованные extended properties). */
private const val PROP_NAMESPACE = "{66f5a359-4659-4830-9070-00047ec6ac6e}"

/** Скрытая метка: система оригинала. */
const val ORIGIN_PROP: String = "String $PROP_NAMESPACE Name gcalsync_origin"

/** Скрытая метка: id оригинала в его системе. */
const val ORIGIN_ID_PROP: String = "String $PROP_NAMESPACE Name gcalsync_origin_id"

const val GRAPH_EXPAND: String =
    "singleValueExtendedProperties(\$filter=id eq '$ORIGIN_PROP' or id eq '$ORIGIN_ID_PROP')"

private val PRIVATE_SENSITIVITY = setOf("private", "confidential")

/** Пространство имён детерминированного transactionId (повторный POST не создаст дубль). */
private const val TRANSACTION_NAMESPACE = "66f5a359-4659-4830-9070-00047ec6ac6e"

/**
 * Событие MS Graph (calendarView или GET /me/events/{id} с `Prefer: outlook.timezone="UTC"`)
 * -> [CalendarEvent].
 *
 * Копия распознаётся по скрытой метке (`singleValueExtendedProperties`), иначе — по маркеру в
 * названии. Отменённое (`isCancelled`) или отклонённое мной событие — `ignored`. У экземпляров
 * серии (`type` не `singleInstance`) `originalStart` — исходное начало экземпляра.
 *
 * @param warn сюда пишется предупреждение о неизвестной системе в скрытой метке.
 * @throws IllegalArgumentException нет `start`/`end`, время не в UTC или неожиданного формата.
 */
fun eventFromGraph(raw: JsonObject, warn: (String) -> Unit = {}): CalendarEvent {
    val (title, markerSource) = splitMarker(raw.string("subject") ?: "", own = System.TEAMS)
    val source = hiddenOrigin(raw, warn) ?: markerSource
    val allDay = raw.flag("isAllDay")
    val declined = raw.obj("responseStatus")?.string("response") == "declined"
    val original = if (raw.string("type") != "singleInstance") {
        parseOffsetStamp(raw.string("originalStart"))?.let { When.Timed(it) }
    } else {
        null
    }
    return CalendarEvent(
        title = title,
        start = parseGraphWhen(raw.required("start"), allDay),
        end = parseGraphWhen(raw.required("end"), allDay),
        location = raw.obj("location")?.string("displayName") ?: "",
        description = description(raw),
        source = source,
        id = raw.string("id"),
        uid = raw.string("iCalUId"),
        originalStart = original,
        // Как Python bool(raw.get("isOrganizer", True)): поля нет — считаем, что организатор я.
        organizerIsMe = if (raw["isOrganizer"] == null) true else raw.flag("isOrganizer"),
        hasAttendees = (raw["attendees"] as? JsonArray)?.isNotEmpty() == true,
        isPrivate = raw.string("sensitivity") in PRIVATE_SENSITIVITY,
        modified = parseOffsetStamp(raw.string("lastModifiedDateTime")),
        ignored = raw.flag("isCancelled") || declined,
    )
}

/**
 * [CalendarEvent] -> тело `POST /me/events`: синхронизируемые поля ([syncedFields]), для копии
 * скрытая метка и детерминированный `transactionId` (UUID5 от `<origin>|<id>|teams`, тот же,
 * что у Python), для частного события `sensitivity: private`.
 */
fun eventToGraph(event: CalendarEvent): JsonObject {
    val body = syncedFields(event).toMutableMap()
    val source = event.source
    if (source != null) {
        val props = mutableListOf(prop(ORIGIN_PROP, source.value))
        val id = event.id
        if (!id.isNullOrEmpty()) {
            props += prop(ORIGIN_ID_PROP, id)
            val key = "${source.value}|$id|${System.TEAMS.value}"
            body["transactionId"] = JsonPrimitive(uuid5(TRANSACTION_NAMESPACE, key))
        }
        body["singleValueExtendedProperties"] = JsonArray(props)
    }
    if (event.isPrivate) body["sensitivity"] = JsonPrimitive("private")
    return JsonObject(body)
}

/**
 * Синхронизируемые поля (для POST и PATCH): название с маркером, начало/конец в UTC без
 * смещения, `isAllDay`, место, описание простым текстом.
 */
fun syncedFields(event: CalendarEvent): JsonObject = buildJsonObject {
    put("subject", event.markedTitle())
    put("start", graphWhen(event.start))
    put("end", graphWhen(event.end))
    put("isAllDay", event.allDay)
    put("location", buildJsonObject { put("displayName", event.location) })
    put("body", buildJsonObject {
        put("contentType", "text")
        put("content", event.description)
    })
}

private fun graphWhen(value: When): JsonObject = buildJsonObject {
    val text = when (value) {
        is When.AllDay -> "${value.isoformat()}T00:00:00"
        // Python: naive UTC datetime.isoformat() — микросекунды, только если не нулевые.
        is When.Timed -> value.isoformat().removeSuffix("+00:00")
    }
    put("dateTime", text)
    put("timeZone", "UTC")
}

private fun prop(id: String, value: String) = buildJsonObject {
    put("id", id)
    put("value", value)
}

private fun parseGraphWhen(value: JsonElement, allDay: Boolean): When {
    val obj = value as? JsonObject ?: throw IllegalArgumentException("Ожидался объект времени: $value")
    val zone = obj.string("timeZone")
    require(zone == "UTC") { "Ожидалось время в UTC, получено '$zone'" }
    val dateTime = obj.string("dateTime") ?: throw IllegalArgumentException("Нет dateTime: $obj")
    val stamp = dateTime.take(19) // Graph отдаёт 7 знаков дробной части
    if (allDay) return When.AllDay(LocalDate.parse(stamp.take(10)))
    return When.Timed(LocalDateTime.parse(stamp).toInstant(TimeZone.UTC))
}

private val OFFSET_STAMP =
    Regex("""^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d+))?(Z|[+-]\d\d:\d\d)?$""")

/** `2026-11-10T08:15:30.1234567Z` -> момент (дробная часть до микросекунд); пусто — `null`. */
internal fun parseOffsetStamp(value: String?): Instant? {
    if (value.isNullOrEmpty()) return null
    val match = OFFSET_STAMP.matchEntire(value)
        ?: throw IllegalArgumentException("Неожиданный формат времени Graph: '$value'")
    val (stamp, fraction, offset) = match.destructured
    val micros = if (fraction.isEmpty()) "" else "." + fraction.take(6).padEnd(6, '0')
    val zone = if (offset.isEmpty() || offset == "Z") "Z" else offset
    return Instant.parse(stamp + micros + zone)
}

private fun hiddenOrigin(raw: JsonObject, warn: (String) -> Unit): System? {
    val props = raw["singleValueExtendedProperties"] as? JsonArray ?: return null
    val origin = props.asSequence()
        .mapNotNull { it as? JsonObject }
        .lastOrNull { it.string("id") == ORIGIN_PROP } // dict в Python: побеждает последний
        ?.string("value")
    if (origin.isNullOrEmpty()) return null
    val system = System.entries.firstOrNull { it.value == origin }
    if (system == null) {
        warn("Неизвестная система в скрытой метке события Teams ${raw.string("id")}: '$origin'")
        return null
    }
    return if (system != System.TEAMS) system else null
}

private fun description(raw: JsonObject): String {
    val body = raw.obj("body")
    return when (body?.string("contentType")) {
        "text" -> PyText.strip(body.string("content") ?: "")
        "html" -> htmlToText(body.string("content") ?: "")
        else -> PyText.strip(raw.string("bodyPreview") ?: "")
    }
}

// --- Доступ к полям JSON с семантикой Python dict.get ---

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

private fun JsonObject.required(key: String): JsonElement =
    this[key]?.takeIf { it !is JsonNull } ?: throw IllegalArgumentException("Нет поля '$key'")
