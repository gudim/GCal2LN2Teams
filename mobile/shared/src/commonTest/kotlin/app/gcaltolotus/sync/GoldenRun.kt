package app.gcaltolotus.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Текст ошибки, с которой падают операции из `input.failures`. */
const val GOLDEN_FAILURE: String = "сбой"

/**
 * Календарь эталонного прогона (`GoldenCalendar` в `src/sync/golden.py`): id новой копии
 * назначает календарь, как настоящий сервер.
 *
 * - [create]: id = `<система>-new-<n>`, n — номер вызова `create` в этом календаре с 1;
 * - [failures] — пары (операция, название), на которых `create`/`update`/`delete` падают с
 *   ошибкой [GOLDEN_FAILURE]; `delete` сверяет название удаляемого события.
 */
class GoldenCalendar(
    system: System,
    events: Iterable<CalendarEvent> = emptyList(),
    failures: Collection<Pair<String, String>> = emptyList(),
) : InMemoryCalendar(system) {
    private val failures = failures.toSet()
    private var created = 0

    init {
        // Как в Python: начальные события кладутся как есть, без create.
        stored += events
    }

    private fun check(operation: String, title: String) {
        if ((operation to title) in failures) throw RuntimeException(GOLDEN_FAILURE)
    }

    override suspend fun create(event: CalendarEvent): String {
        check("create", event.title)
        created += 1
        val eventId = "${system.value}-new-$created"
        stored += event.copy(id = eventId)
        return eventId
    }

    override suspend fun update(eventId: String, event: CalendarEvent) {
        check("update", event.title)
        super.update(eventId, event)
    }

    override suspend fun delete(eventId: String) {
        get(eventId)?.let { check("delete", it.title) }
        super.delete(eventId)
    }
}

/** Связи по ключу: `save` заменяет связь с тем же ключом, `drop` удаляет. */
class MemoryLinkStore(links: Iterable<Link> = emptyList()) : LinkStore {
    private val rows = LinkedHashMap<String, Link>().apply { links.forEach { put(it.key(), it) } }

    val links: List<Link> get() = rows.values.toList()

    override fun save(link: Link) {
        rows[link.key()] = link
    }

    override fun drop(link: Link) {
        rows.remove(link.key())
    }
}

/**
 * Эталонный прогон по входу сценария (`run_input` в `src/sync/golden.py`, spec/README.md
 * «Как прогонять»): collect → plan → select → apply и повторно collect → plan на результате.
 * Возвращает объект того же вида, что `expected`.
 */
suspend fun runGolden(input: GoldenInput): JsonObject {
    val pairs = input.pairs.map { setOf(it.first, it.second) }
    val calendars = loadCalendars(input.calendars, input.failures)
    val links = input.links

    val collected = collect(calendars, input.window, links)
    val unavailable = input.unavailable.toSet()
    val result = plan(collected.snapshot, pairs, links, collected.found, unavailable)
    val chosen = select(result.actions, input.selectRoutes?.toSet(), input.selectKeys, result.refused)
    val store = MemoryLinkStore(links)
    val report = apply(chosen, calendars, store)

    val afterJson = GoldenInput.calendarsToJson(calendars.mapValues { (_, c) -> byId(c.events) })
    val afterLinks = SyncJson.linksToJson(store.links)

    // Повторный запуск на результате (через JSON, как в Python): обычно пустой план.
    val again = loadCalendars(GoldenInput.calendarsFromJson(afterJson), emptyList())
    val againLinks = afterLinks.map(SyncJson::linkFromJson)
    val collected2 = collect(again, input.window, againLinks)
    val replan = plan(collected2.snapshot, pairs, againLinks, collected2.found, unavailable)

    return buildJsonObject {
        put("collect", collectToJson(collected))
        put("plan", SyncJson.planToJson(result))
        put("select", JsonArray(chosen.map { JsonPrimitive(it.key) }))
        put(
            "apply",
            buildJsonObject {
                put("report", reportToJson(report))
                put("calendars", afterJson)
                put("links", afterLinks)
            },
        )
        put("replan", SyncJson.planToJson(replan))
    }
}

/** Календари в каноническом порядке систем: от него зависит порядок действий. */
private fun loadCalendars(
    calendars: Map<System, List<CalendarEvent>>,
    failures: List<GoldenFailure>,
): Map<System, GoldenCalendar> {
    val result = LinkedHashMap<System, GoldenCalendar>()
    for (system in System.ORDER) {
        val events = calendars[system] ?: continue
        val fails = failures.filter { it.system == system }.map { it.operation to it.title }
        result[system] = GoldenCalendar(system, events, fails)
    }
    return result
}

private fun byId(events: List<CalendarEvent>): List<CalendarEvent> =
    events.sortedWith { a, b -> pyCompare(a.id ?: "", b.id ?: "") }

private fun collectToJson(collected: Collected): JsonObject = buildJsonObject {
    put(
        "listed",
        JsonObject(
            collected.snapshot.entries.associate { (s, events) ->
                s.value to JsonArray(events.map { JsonPrimitive(it.id) })
            },
        ),
    )
    val fetched = collected.found.entries.sortedWith(
        compareBy<Map.Entry<Member, CalendarEvent?>> { it.key.system.value }.thenComparator { a, b ->
            pyCompare(a.key.id ?: "", b.key.id ?: "")
        },
    )
    put(
        "fetched",
        buildJsonArray {
            for ((member, event) in fetched) {
                add(
                    buildJsonObject {
                        put("system", member.system.value)
                        put("id", member.id)
                        put("event", event?.let(SyncJson::eventToJson) ?: kotlinx.serialization.json.JsonNull)
                    },
                )
            }
        },
    )
}

private fun reportToJson(report: SyncReport): JsonArray = buildJsonArray {
    val rows = report.pairs.entries
        .filter { (_, r) -> r.created != 0 || r.updated != 0 || r.deleted != 0 || r.linked != 0 || r.errors.isNotEmpty() }
        .sortedWith(compareBy({ it.key.first.value }, { it.key.second.value }))
    for ((route, r) in rows) {
        add(
            buildJsonObject {
                put("origin", route.first.value)
                put("target", route.second.value)
                put("created", r.created)
                put("updated", r.updated)
                put("deleted", r.deleted)
                put("linked", r.linked)
                put("errors", JsonArray(r.errors.map(::JsonPrimitive)))
            },
        )
    }
}

/** Первое расхождение двух деревьев JSON (путь и значения) или `null`. */
fun firstDifference(expected: JsonElement, actual: JsonElement, path: String = "$"): String? {
    if (expected is JsonObject && actual is JsonObject) {
        for (k in (expected.keys + actual.keys).sorted()) {
            val e = expected[k] ?: return "$path.$k: лишний ключ"
            val a = actual[k] ?: return "$path.$k: нет ключа"
            firstDifference(e, a, "$path.$k")?.let { return it }
        }
        return null
    }
    if (expected is JsonArray && actual is JsonArray) {
        for (i in 0 until minOf(expected.size, actual.size)) {
            firstDifference(expected[i], actual[i], "$path[$i]")?.let { return it }
        }
        if (expected.size != actual.size) return "$path: длина ${expected.size} != ${actual.size}"
        return null
    }
    return if (expected == actual) null else "$path: ожидалось $expected, получено $actual"
}
