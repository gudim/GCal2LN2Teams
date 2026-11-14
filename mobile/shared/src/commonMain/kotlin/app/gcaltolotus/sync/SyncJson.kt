package app.gcaltolotus.sync

import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Языконезависимый JSON событий, связей, окон, действий и планов. Порт `src/sync/serialize.py`.
 * Формат: spec/README.md, разделы «Событие», «Связь», «Действие»;
 * эталоны (spec/golden) и выгрузка связей (spec/links-export.md) пишут связи одинаково.
 *
 * Работает с деревом [JsonElement] (kotlinx-serialization-json), чтобы явно повторить Python:
 * все ключи пишутся, `null` пишется как `null`, при чтении отсутствующий ключ — ошибка
 * ([IllegalArgumentException]). Лишние ключи при чтении игнорируются (например, `key` у связи
 * или `google_calendar` в выгрузке).
 */
object SyncJson {
    // --- время ---

    /** `when_to_json`: момент — `2026-11-13T10:00:00Z` (UTC), дата — `2026-11-13`. */
    fun whenToJson(value: When?): JsonElement = value?.let { JsonPrimitive(it.toJson()) } ?: JsonNull

    fun instantToJson(value: Instant?): JsonElement = whenToJson(value?.let { When.Timed(it) })

    /** `when_from_json`: `null` — нет значения, 10 символов — дата, иначе момент. */
    fun whenFromJson(element: JsonElement): When? = element.stringOrNull()?.let { When.parse(it) }

    fun instantFromJson(element: JsonElement): Instant? = when (val w = whenFromJson(element)) {
        null -> null
        is When.Timed -> w.instant
        is When.AllDay -> throw IllegalArgumentException("Ожидался момент, а не дата: $element")
    }

    // --- событие ---

    fun eventToJson(event: CalendarEvent): JsonObject = buildJsonObject {
        put("id", event.id)
        put("title", event.title)
        put("start", whenToJson(event.start))
        put("end", whenToJson(event.end))
        put("location", event.location)
        put("description", event.description)
        put("source", event.source?.value)
        put("uid", event.uid)
        put("original_start", whenToJson(event.originalStart))
        put("organizer_is_me", event.organizerIsMe)
        put("has_attendees", event.hasAttendees)
        put("private", event.isPrivate)
        put("modified", instantToJson(event.modified))
        put("ignored", event.ignored)
    }

    fun eventFromJson(element: JsonElement): CalendarEvent {
        val o = element.obj()
        return CalendarEvent(
            id = o.field("id").stringOrNull(),
            title = o.field("title").string(),
            start = whenFromJson(o.field("start")) ?: missing("start"),
            end = whenFromJson(o.field("end")) ?: missing("end"),
            location = o.field("location").string(),
            description = o.field("description").string(),
            source = o.field("source").stringOrNull()?.takeIf { it.isNotEmpty() }
                ?.let(System::fromValue),
            uid = o.field("uid").stringOrNull(),
            originalStart = whenFromJson(o.field("original_start")),
            organizerIsMe = o.field("organizer_is_me").bool(),
            hasAttendees = o.field("has_attendees").bool(),
            isPrivate = o.field("private").bool(),
            modified = instantFromJson(o.field("modified")),
            ignored = o.field("ignored").bool(),
        )
    }

    // --- снимок и связь ---

    /** Снимок пишется как есть (порядок ключей сохраняется). */
    fun fieldsToJson(fields: Fields?): JsonElement =
        fields?.let { f -> JsonObject(f.mapValues { JsonPrimitive(it.value) }) } ?: JsonNull

    /** Снимок читается как есть: любые ключи, значения — строки. */
    fun fieldsFromJson(element: JsonElement): Fields? {
        if (element is JsonNull) return null
        val result = LinkedHashMap<String, String>()
        for ((k, v) in element.obj()) result[k] = v.string()
        return result
    }

    /** `link_to_json`: с вычисляемым `key`. */
    fun linkToJson(link: Link): JsonObject = buildJsonObject {
        put("key", link.key())
        put("kind", link.kind.value)
        put("source_system", link.sourceSystem.value)
        put("source_id", link.sourceId)
        put("target_system", link.targetSystem.value)
        put("target_id", link.targetId)
        put("source_fields", fieldsToJson(link.sourceFields))
        put("target_fields", fieldsToJson(link.targetFields))
        put("kept", link.kept)
    }

    /** `link_from_json`: поле `key` вычисляемое и при чтении не используется. */
    fun linkFromJson(element: JsonElement): Link {
        val o = element.obj()
        return Link(
            sourceSystem = System.fromValue(o.field("source_system").string()),
            sourceId = o.field("source_id").string(),
            targetSystem = System.fromValue(o.field("target_system").string()),
            targetId = o.field("target_id").stringOrNull(),
            kind = LinkKind.fromValue(o.field("kind").string()),
            sourceFields = fieldsFromJson(o.field("source_fields")),
            targetFields = fieldsFromJson(o.field("target_fields")),
            kept = o.field("kept").bool(),
        )
    }

    /** `links_to_json`: связи, отсортированные по ключу. */
    fun linksToJson(links: List<Link>): JsonArray =
        JsonArray(links.sortedBy { it.key() }.map(::linkToJson))

    // --- действие и план (только запись: эталоны сравнивают план движка) ---

    /** `action_to_json`: у служебных `link`/`unlink` событие `null`; `subject` вне `delete` — `""`. */
    fun actionToJson(action: Action): JsonObject = buildJsonObject {
        put("key", action.key)
        put("kind", action.kind.value)
        put("origin", action.origin.value)
        put("target", action.target.value)
        put("event", if (action.kind.isBookkeeping) JsonNull else eventToJson(action.event))
        put("selected", action.selected)
        put("silent", action.silent)
        put("reason", action.reason)
        put("subject", action.subject?.value ?: "")
        put("changed", JsonArray(action.changed.map(::JsonPrimitive)))
        put("depends_on", JsonArray(action.dependsOn.map(::JsonPrimitive)))
        put("link", action.link?.let(::linkToJson) ?: JsonNull)
        put("fallback", JsonArray(action.fallback.map(::actionToJson)))
    }

    /** `plan_to_json`. */
    fun planToJson(plan: Plan): JsonObject = buildJsonObject {
        put("actions", JsonArray(plan.actions.map(::actionToJson)))
        put("warnings", JsonArray(plan.warnings.map(::JsonPrimitive)))
        put("refused", JsonArray(plan.refused.map(::actionToJson)))
    }

    // --- окно ---

    fun windowToJson(window: Window): JsonObject = buildJsonObject {
        put("start", instantToJson(window.start))
        put("end", instantToJson(window.end))
    }

    fun windowFromJson(element: JsonElement): Window {
        val o = element.obj()
        return Window(
            instantFromJson(o.field("start")) ?: missing("start"),
            instantFromJson(o.field("end")) ?: missing("end"),
        )
    }

    // --- помощники для разбора (используются и выгрузкой связей) ---

    internal fun stringToJson(value: String?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

    internal fun JsonElement.obj(): JsonObject =
        this as? JsonObject ?: throw IllegalArgumentException("Ожидался объект: $this")

    internal fun JsonObject.field(name: String): JsonElement =
        this[name] ?: throw IllegalArgumentException("Нет поля '$name'")

    internal fun JsonElement.stringOrNull(): String? = when {
        this is JsonNull -> null
        this is JsonPrimitive && isString -> content
        else -> throw IllegalArgumentException("Ожидалась строка или null: $this")
    }

    internal fun JsonElement.string(): String =
        stringOrNull() ?: throw IllegalArgumentException("Ожидалась строка, а не null")

    internal fun JsonElement.bool(): Boolean {
        val p = this as? JsonPrimitive
        if (p == null || p.isString || p is JsonNull) {
            throw IllegalArgumentException("Ожидалось true/false: $this")
        }
        return p.content.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("Ожидалось true/false: $this")
    }

    internal fun JsonElement.int(): Int {
        val p = this as? JsonPrimitive
        if (p == null || p.isString || p is JsonNull) {
            throw IllegalArgumentException("Ожидалось число: $this")
        }
        return p.content.toIntOrNull() ?: throw IllegalArgumentException("Ожидалось целое: $this")
    }

    private fun missing(name: String): Nothing =
        throw IllegalArgumentException("Поле '$name' не может быть null")
}
