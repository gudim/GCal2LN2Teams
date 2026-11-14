package app.gcaltolotus.adapters.domino

import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.PyText
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.When
import app.gcaltolotus.sync.splitMarker
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Конвертация событий Domino Calendar API (сервер 14.5.1) <-> CalendarEvent.
 * Порт модульных функций src/adapters/domino_rest.py.
 */

/** Классы доступа, при которых событие частное. */
internal val PRIVATE_CLASSES = setOf("private", "confidential")

/** Поля, которые берутся из детального GET: список отдаёт только сводку (x-lotus-summarydataonly). */
internal val DETAIL_FIELDS = listOf("description", "last-modified", "attendees", "organizer", "sequence")

/**
 * Владелец календаря (`calendars[0].owner` из `GET /api/calendar`): по нему решается, моя ли
 * встреча и кто из участников «другой».
 */
data class DominoOwner(
    val distinguishedName: String? = null,
    val email: String? = null,
    val displayName: String? = null,
) {
    companion object {
        fun fromJson(owner: JsonObject?): DominoOwner? = owner?.let {
            DominoOwner(it.str("distinguishedName"), it.str("email"), it.str("displayName"))
        }
    }
}

/** Строковое значение поля; `null`, если поля нет или оно не строка. */
internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** Python-истинность значения JSON (`if value:`). */
private fun JsonElement?.truthy(): Boolean = when (this) {
    null -> false
    is JsonPrimitive -> when {
        isString -> content.isNotEmpty()
        booleanOrNull != null -> booleanOrNull!!
        content == "null" -> false
        else -> content.toDoubleOrNull()?.let { it != 0.0 } ?: true
    }
    is JsonObject -> isNotEmpty()
    is JsonArray -> isNotEmpty()
}

private fun bad(message: String): Nothing = throw IllegalArgumentException(message)

/**
 * Событие Domino Calendar API -> [CalendarEvent] (`event_from_domino`).
 *
 * [raw] — сводка из списка (время в UTC), дополненная полями детального GET, или сам детальный
 * ответ (время с `tzid`, тогда нужен его массив [timezones]). [owner] — владелец календаря.
 *
 * - id экземпляра повторяющегося события — `<uid>/<recurrenceId>` (у всех экземпляров общий uid);
 * - маркер чужой системы снимается с названия и становится `source`;
 * - дата конца all-day исключающая (так отдаёт сервер 14.5.1); конец, равный началу или
 *   отсутствующий, — однодневное событие;
 * - `\r\n` в описании заменяется на `\n`, остальное описание (в том числе перевод строки в
 *   конце) сохраняется как есть: снимок полей обрезает края сам;
 * - `ignored` — у отменённых (`status: cancelled`) и уведомлений (есть `x-lotus-unid`).
 *
 * Неподдерживаемые данные (время с `tzid` без описания зоны, нет `start`, кривая дата) —
 * [IllegalArgumentException].
 */
fun eventFromDomino(
    raw: JsonObject,
    owner: DominoOwner? = null,
    timezones: JsonArray? = null,
): CalendarEvent {
    val zones = timezones.orEmpty().mapNotNull { it as? JsonObject }.associateBy { it.str("tzid") }
    val (title, source) = splitMarker(raw.str("summary") ?: "", System.DOMINO)
    val start = parseDominoWhen(raw["start"], zones)
    var end = if ("end" in raw) parseDominoWhen(raw["end"], zones) else start
    if (start is When.AllDay && end is When.AllDay && end <= start) {
        end = When.AllDay(start.date.plus(1, DateTimeUnit.DAY))
    }
    val uid = raw.str("id")
    val recurrenceId = raw.str("recurrenceId")
    val eventId = if (!uid.isNullOrEmpty() && !recurrenceId.isNullOrEmpty()) "$uid/$recurrenceId" else uid

    val organizer = raw.obj("organizer") ?: JsonObject(emptyMap())
    val lotusOrganizer = raw.obj("x-lotus-organizer")?.str("data")
    val organizerName = lotusOrganizer?.ifEmpty { null } ?: organizer.str("displayName")
    val organizerEmail = organizer.str("email")
    val hasOrganizer = !organizerName.isNullOrEmpty() || !organizerEmail.isNullOrEmpty()
    val organizerIsMe = !hasOrganizer || isOwner(organizerName, organizerEmail, owner)
    // участник, кроме владельца календаря; чужой организатор тоже считается участником
    val others = (raw["attendees"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .filter { !isOwner(it.str("displayName"), it.str("email"), owner) }
    val hasAttendees = others.isNotEmpty() || (hasOrganizer && !organizerIsMe)

    return CalendarEvent(
        title = title,
        start = start,
        end = end,
        location = raw.str("location") ?: "",
        description = (raw.str("description") ?: "").replace("\r\n", "\n"),
        source = source,
        id = eventId,
        uid = uid,
        originalStart = parseStamp(recurrenceId)?.let { When.Timed(it) },
        organizerIsMe = organizerIsMe,
        hasAttendees = hasAttendees,
        isPrivate = (raw.str("class") ?: "").lowercase() in PRIVATE_CLASSES,
        modified = parseStamp(raw.str("last-modified")),
        // x-lotus-unid есть только у документов-уведомлений (приглашение, перенос, отмена),
        // а не у записей календаря; деталей у них нет (404)
        ignored = raw.str("status") == "cancelled" || "x-lotus-unid" in raw,
    )
}

/**
 * [CalendarEvent] -> событие для `POST .../api/calendar/events` (`event_to_domino`).
 * Название с маркером системы-источника; время в UTC. Скрытой метки у Domino нет: копию узнаём
 * по маркеру в названии и по таблице связей.
 */
fun eventToDomino(event: CalendarEvent): JsonObject = buildJsonObject {
    put("summary", event.markedTitle())
    for ((key, value) in dominoTimes(event)) put(key, value)
    if (event.location.isNotEmpty()) put("location", event.location)
    if (event.description.isNotEmpty()) put("description", event.description)
    if (event.isPrivate) put("class", "private")
}

/** `start`/`end` для записи: all-day — даты (конец исключающий), иначе дата+время в UTC. */
internal fun dominoTimes(event: CalendarEvent): Map<String, JsonObject> {
    fun json(value: When): JsonObject = when (value) {
        // исключающая дата конца, как в ответах сервера (запись не проверена на живом сервере)
        is When.AllDay -> buildJsonObject { put("date", value.date.toString()) }
        is When.Timed -> {
            val t = value.instant.toLocalDateTime(TimeZone.UTC)
            buildJsonObject {
                put("date", t.date.toString())
                put("time", "${pad(t.hour)}:${pad(t.minute)}:${pad(t.second)}")
                put("utc", true)
            }
        }
    }
    return linkedMapOf("start" to json(event.start), "end" to json(event.end))
}

private fun pad(value: Int): String = value.toString().padStart(2, '0')

/** Время Domino: `{"date"}` — дата; `{"date","time","utc":true}` — UTC; `{"date","time","tzid"}` — по зоне. */
internal fun parseDominoWhen(value: JsonElement?, zones: Map<String?, JsonObject>): When {
    val obj = value as? JsonObject ?: bad("Нет времени события: $value")
    val day = LocalDate.parse(obj.str("date") ?: bad("Нет даты: $obj"))
    val time = obj.str("time") ?: return When.AllDay(day)
    val local = LocalDateTime(day, LocalTime.parse(time.take(8)))
    if (obj["utc"].truthy()) return When.Timed(local.toInstant(TimeZone.UTC))
    val tzid = obj.str("tzid")
    // tzid ссылается на описание зоны из массива timezones ответа; без него время не понять
    val zone = zones[tzid] ?: bad("Время не в UTC (tzid=$tzid) без описания зоны")
    return When.Timed(local.toInstant(utcOffset(zone, local)))
}

/** `20261007T050045Z` -> момент UTC; `20261007` -> полночь UTC; пусто -> `null`. */
internal fun parseStamp(value: String?): Instant? {
    if (value.isNullOrEmpty()) return null
    fun num(from: Int, to: Int): Int {
        if (value.length < to) bad("Неверная отметка времени: $value")
        return value.substring(from, to).toIntOrNull() ?: bad("Неверная отметка времени: $value")
    }
    val date = LocalDate(num(0, 4), num(4, 6), num(6, 8))
    if ('T' !in value) {
        if (value.length != 8) bad("Неверная отметка времени: $value")
        return date.atStartOfDayUtc()
    }
    if (value.length != 16 || value[8] != 'T' || value[15] != 'Z') bad("Неверная отметка времени: $value")
    return LocalDateTime(date, LocalTime(num(9, 11), num(11, 13), num(13, 15))).toInstant(TimeZone.UTC)
}

private fun LocalDate.atStartOfDayUtc(): Instant = LocalDateTime(this, LocalTime(0, 0)).toInstant(TimeZone.UTC)

/** `CN=Ivan Petrov/O=ACME` и `Ivan Petrov/ACME` -> `ivan petrov/acme`. */
internal fun normalizeDn(name: String): String {
    val prefix = Regex("^[A-Za-z]+=")
    return PyText.casefold(name.split("/").joinToString("/") { prefix.replaceFirst(PyText.strip(it), "") })
}

/** Это владелец календаря: совпадает DN (в любой записи) или e-mail без учёта регистра. */
internal fun isOwner(name: String?, email: String?, owner: DominoOwner?): Boolean {
    if (owner == null) return false
    val ownerDn = owner.distinguishedName
    if (!name.isNullOrEmpty() && !ownerDn.isNullOrEmpty() && normalizeDn(name) == normalizeDn(ownerDn)) return true
    val ownerEmail = owner.email
    return !email.isNullOrEmpty() && !ownerEmail.isNullOrEmpty() &&
        PyText.casefold(email) == PyText.casefold(ownerEmail)
}

/** Параметр `since`/`before`: момент в UTC без долей секунды, `2026-10-31T21:00:00Z`. */
internal fun utcParam(moment: Instant): String {
    val t = moment.toLocalDateTime(TimeZone.UTC)
    return "${t.date}T${pad(t.hour)}:${pad(t.minute)}:${pad(t.second)}Z"
}

/**
 * Следующий `start` страницы: конец `Content-Range` + 1 (единицы `start` — не число событий),
 * а если заголовка нет или он не продвигает — [offset] + длина страницы.
 */
internal fun nextStart(contentRange: String?, offset: Int, count: Int): Int {
    val match = Regex("(\\d+)-(\\d+)").find(contentRange ?: "")
    val end = match?.groupValues?.get(2)?.toIntOrNull()
    if (end != null && end + 1 > offset) return end + 1
    return offset + count
}

/**
 * Многодневное all-day событие список отдаёт по записи на каждый день с одним href:
 * склеиваем их в одно событие от первого до последнего дня (на месте первой записи).
 */
internal fun mergeMultiDay(entries: List<JsonObject>): List<JsonObject> {
    val result = mutableListOf<JsonObject>()
    val byHref = mutableMapOf<String, Int>()
    for (raw in entries) {
        val href = raw.str("href")
        val start = raw["start"] as? JsonObject
        val allDay = start != null && "time" !in start
        if (href.isNullOrEmpty() || raw["recurrenceId"].truthy() || !allDay) {
            result += raw
            continue
        }
        val index = byHref[href]
        if (index == null) {
            byHref[href] = result.size
            result += raw
            continue
        }
        val merged = result[index].toMutableMap()
        fun dateOf(value: JsonElement?): String = (value as? JsonObject)?.str("date") ?: ""
        val rawEnd = raw["end"] ?: raw.getValue("start")
        if (dateOf(raw["start"]) < dateOf(merged["start"])) merged["start"] = raw.getValue("start")
        if (dateOf(rawEnd) > dateOf(merged["end"] ?: merged["start"])) merged["end"] = rawEnd
        result[index] = JsonObject(merged)
    }
    return result
}

/**
 * Событие из детального ответа: GET серии отдаёт мастер + все экземпляры. Ищем по
 * `recurrenceId` из id (`uid/recurrenceId`). Нет записи экземпляра (удалён или перенесён) —
 * `null`, а не мастер: иначе чтение или PUT задели бы всю серию. Первое событие подходит только
 * для id без recurrenceId.
 */
internal fun pick(events: List<JsonObject>, eventId: String): JsonObject? {
    val recurrence = if ('/' in eventId) eventId.substringAfter('/') else null
    events.firstOrNull { it.str("recurrenceId") == recurrence }?.let { return it }
    return if (recurrence == null) events.firstOrNull() else null
}

/**
 * Python `urljoin(base, ref)` для случаев адаптера: абсолютный [ref] — как есть, `/путь` —
 * от корня сервера, относительный — от «каталога» [base].
 */
internal fun urlJoin(base: String, ref: String): String {
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(ref)) return ref
    val origin = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*").find(base)?.value ?: ""
    if (ref.startsWith("/")) return origin + ref
    val path = base.removePrefix(origin).substringBefore('?').substringBefore('#')
    return "$origin${path.substringBeforeLast('/', "")}/$ref"
}
