package app.gcaltolotus.sync

import app.gcaltolotus.sync.SyncJson.field
import app.gcaltolotus.sync.SyncJson.obj
import app.gcaltolotus.sync.SyncJson.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Сбой из `input.failures`: операция [operation] над событием [title] в [system] падает. */
data class GoldenFailure(val system: System, val operation: String, val title: String)

/**
 * Вход эталонного сценария (spec/README.md, «input»). Тестовая обвязка для переноса движка:
 * разбирает только вход; `expected` остаётся деревом JSON в [GoldenScenario.expected].
 *
 * @property calendars календари в каноническом порядке систем (google, domino, teams), только
 *   прочитанные; события — в порядке файла (по `id`).
 * @property links связи в порядке файла (по ключу).
 * @property selectKeys `null` — ключей нет (выбор по умолчанию).
 * @property selectRoutes `null` — все маршруты.
 */
data class GoldenInput(
    val window: Window,
    val direction: String,
    val pairs: List<Pair<System, System>>,
    val calendars: Map<System, List<CalendarEvent>>,
    val links: List<Link>,
    val selectKeys: List<String>?,
    val selectRoutes: List<Pair<System, System>>?,
    val failures: List<GoldenFailure>,
    /** Системы, не прочитанные из-за сбоя; поле необязательное (нет — пусто). */
    val unavailable: List<System> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("window", SyncJson.windowToJson(window))
        put("direction", direction)
        put("pairs", pairsToJson(pairs))
        put("calendars", calendarsToJson(calendars))
        put("links", JsonArray(links.map(SyncJson::linkToJson)))
        put(
            "select",
            buildJsonObject {
                put("keys", selectKeys?.let { k -> JsonArray(k.map(::JsonPrimitive)) } ?: JsonNull)
                put("routes", selectRoutes?.let(::pairsToJson) ?: JsonNull)
            },
        )
        put(
            "failures",
            JsonArray(
                failures.map {
                    buildJsonObject {
                        put("system", it.system.value)
                        put("operation", it.operation)
                        put("title", it.title)
                    }
                },
            ),
        )
        if (unavailable.isNotEmpty()) put("unavailable", JsonArray(unavailable.map { JsonPrimitive(it.value) }))
    }

    companion object {
        fun fromJson(element: JsonElement): GoldenInput {
            val o = element.obj()
            val select = o.field("select").obj()
            return GoldenInput(
                window = SyncJson.windowFromJson(o.field("window")),
                direction = o.field("direction").string(),
                pairs = pairsFromJson(o.field("pairs")),
                calendars = calendarsFromJson(o.field("calendars")),
                links = o.field("links").array().map(SyncJson::linkFromJson),
                selectKeys = select.field("keys").takeUnless { it is JsonNull }?.array()?.map { it.string() },
                selectRoutes = select.field("routes").takeUnless { it is JsonNull }?.let(::pairsFromJson),
                failures = o.field("failures").array().map {
                    val f = it.obj()
                    GoldenFailure(
                        System.fromValue(f.field("system").string()),
                        f.field("operation").string(),
                        f.field("title").string(),
                    )
                },
                unavailable = o["unavailable"]?.array()?.map { System.fromValue(it.string()) }.orEmpty(),
            )
        }

        /** `{система: [событие, ...]}` → карта в каноническом порядке систем. */
        fun calendarsFromJson(element: JsonElement): Map<System, List<CalendarEvent>> {
            val o = element.obj()
            val result = LinkedHashMap<System, List<CalendarEvent>>()
            for (system in System.ORDER) {
                o[system.value]?.let { events -> result[system] = events.array().map(SyncJson::eventFromJson) }
            }
            require(o.keys.all { key -> System.ORDER.any { it.value == key } }) { "Неизвестная система: ${o.keys}" }
            return result
        }

        fun calendarsToJson(calendars: Map<System, List<CalendarEvent>>): JsonObject = JsonObject(
            calendars.entries.associate { (system, events) ->
                system.value to JsonArray(events.map(SyncJson::eventToJson))
            },
        )

        private fun pairsFromJson(element: JsonElement): List<Pair<System, System>> =
            element.array().map {
                val p = it.array()
                require(p.size == 2) { "Пара систем: $it" }
                System.fromValue(p[0].string()) to System.fromValue(p[1].string())
            }

        private fun pairsToJson(pairs: List<Pair<System, System>>): JsonArray =
            JsonArray(pairs.map { JsonArray(listOf(JsonPrimitive(it.first.value), JsonPrimitive(it.second.value))) })
    }
}

/** Файл `spec/golden/<сценарий>.json`. */
data class GoldenScenario(
    val name: String,
    val description: String,
    val input: GoldenInput,
    /** `expected` как есть; формат — spec/README.md, «expected». */
    val expected: JsonObject,
) {
    companion object {
        fun parse(text: String): GoldenScenario {
            val o = Json.parseToJsonElement(text).obj()
            return GoldenScenario(
                o.field("name").string(),
                o.field("description").string(),
                GoldenInput.fromJson(o.field("input")),
                o.field("expected").obj(),
            )
        }
    }
}

internal fun JsonElement.array(): JsonArray =
    this as? JsonArray ?: throw IllegalArgumentException("Ожидался список: $this")
