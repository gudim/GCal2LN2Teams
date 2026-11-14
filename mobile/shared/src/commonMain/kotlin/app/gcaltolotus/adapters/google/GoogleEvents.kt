package app.gcaltolotus.adapters.google

import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.When
import app.gcaltolotus.sync.splitMarker
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Преобразования событий Google Calendar API v3 (ресурс `Event`) в [CalendarEvent] и обратно.
 * Порт `event_from_google` / `event_to_google` из `src/adapters/google_calendar.py`.
 */

/** Ключ скрытой метки копии: система-источник. Приватные extendedProperties видны только этому календарю. */
const val ORIGIN_KEY: String = "gcalsync_origin"

/** Ключ скрытой метки копии: id оригинала в системе-источнике. */
const val ORIGIN_ID_KEY: String = "gcalsync_origin_id"

/** Служебные типы событий Google: не копируются. default, outOfOffice, focusTime — копируются. */
internal val IGNORED_EVENT_TYPES: Set<String> = setOf("birthday", "workingLocation", "fromGmail")

private val PRIVATE_VISIBILITY = setOf("private", "confidential")

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.flag(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

/** `{"dateTime": ...}` — момент (смещение обязательно, приводится к UTC), `{"date": ...}` — дата. */
private fun parseGoogleWhen(value: JsonObject): When {
    value.string("dateTime")?.let { return When.Timed(Instant.parse(it)) }
    val date = value.string("date") ?: throw IllegalArgumentException("Нет dateTime/date: $value")
    return When.AllDay(LocalDate.parse(date))
}

private fun hiddenOrigin(raw: JsonObject): System? {
    val origin = raw.obj("extendedProperties")?.obj("private")?.string(ORIGIN_KEY)
    if (origin.isNullOrEmpty()) return null
    // Неизвестная система в метке — не копия (в Python — предупреждение в журнал).
    val system = System.entries.firstOrNull { it.value == origin } ?: return null
    return system.takeIf { it != System.GOOGLE }
}

private fun isIgnored(raw: JsonObject): Boolean {
    if (raw.string("status") == "cancelled") return true
    if ((raw.string("eventType") ?: "default") in IGNORED_EVENT_TYPES) return true
    val me = raw.objects("attendees").firstOrNull { it.flag("self") }
    return me != null && me.string("responseStatus") == "declined"
}

/**
 * Событие Google Calendar API -> [CalendarEvent].
 *
 * Копия распознаётся по скрытой метке в `extendedProperties.private`, а если её нет — по
 * маркеру в названии. Отменённый экземпляр серии приходит без start/end: время берётся из
 * `originalStartTime`, событие помечается `ignored`.
 *
 * Неполное или неразборчивое событие — [IllegalArgumentException] (`list` такие пропускает).
 */
fun eventFromGoogle(raw: JsonObject): CalendarEvent {
    val (title, markerSource) = splitMarker(raw.string("summary") ?: "", own = System.GOOGLE)
    val source = hiddenOrigin(raw) ?: markerSource
    val originalStart = raw.obj("originalStartTime")?.let(::parseGoogleWhen)
    val start: When
    val end: When
    val rawStart = raw.obj("start")
    if (rawStart != null) {
        start = parseGoogleWhen(rawStart)
        end = parseGoogleWhen(raw.obj("end") ?: throw IllegalArgumentException("Нет end"))
    } else if (raw.string("status") == "cancelled" && originalStart != null) {
        start = originalStart
        end = originalStart
    } else {
        throw IllegalArgumentException("Нет start")
    }
    val attendees = raw.objects("attendees").filterNot { it.flag("self") }
    // Нет organizer — своё событие.
    val organizer = raw.obj("organizer")
    return CalendarEvent(
        title = title,
        start = start,
        end = end,
        location = raw.string("location") ?: "",
        description = raw.string("description") ?: "",
        source = source,
        id = raw.string("id"),
        uid = raw.string("iCalUID"),
        originalStart = originalStart,
        organizerIsMe = organizer == null || organizer.flag("self"),
        hasAttendees = attendees.isNotEmpty(),
        isPrivate = raw.string("visibility") in PRIVATE_VISIBILITY,
        modified = raw.string("updated")?.takeIf { it.isNotEmpty() }?.let(Instant::parse),
        ignored = isIgnored(raw),
    )
}

private fun whenToGoogle(value: When): JsonElement = buildJsonObject {
    when (value) {
        is When.AllDay -> put("date", value.isoformat())
        is When.Timed -> {
            put("dateTime", value.isoformat())
            put("timeZone", "UTC")
        }
    }
}

/**
 * Синхронизируемые поля: название с маркером, место, описание, начало и конец (конец all-day
 * исключающий). Тело `events.patch` при обновлении.
 */
fun syncedFieldsToGoogle(event: CalendarEvent): JsonObject = buildJsonObject {
    put("summary", event.markedTitle())
    put("location", event.location)
    put("description", event.description)
    put("start", whenToGoogle(event.start))
    put("end", whenToGoogle(event.end))
}

/** [CalendarEvent] -> тело запроса `events.insert` (со скрытой меткой для копии). */
fun eventToGoogle(event: CalendarEvent): JsonObject {
    val fields = syncedFieldsToGoogle(event)
    return buildJsonObject {
        fields.forEach { (key, value) -> put(key, value) }
        val source = event.source
        if (source != null) {
            put(
                "extendedProperties",
                buildJsonObject {
                    put(
                        "private",
                        buildJsonObject {
                            put(ORIGIN_KEY, source.value)
                            if (!event.id.isNullOrEmpty()) put(ORIGIN_ID_KEY, event.id)
                        },
                    )
                },
            )
        }
        if (event.isPrivate) put("visibility", "private")
    }
}
