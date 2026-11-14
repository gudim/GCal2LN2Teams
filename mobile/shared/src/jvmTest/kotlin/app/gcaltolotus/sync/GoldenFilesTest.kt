package app.gcaltolotus.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Каталог `spec/` репозитория. Путь передаёт Gradle (`shared/build.gradle.kts`, свойство
 * `gcaltolotus.specDir`). `java.lang.System` — потому что `System` в этом пакете — enum систем.
 */
internal val specDir: File by lazy {
    val path = java.lang.System.getProperty("gcaltolotus.specDir")
        ?: error("Нет системного свойства gcaltolotus.specDir (задаётся в shared/build.gradle.kts)")
    File(path).also { require(it.isDirectory) { "Нет каталога spec: $it" } }
}

internal fun goldenFiles(): List<File> =
    (File(specDir, "golden").listFiles { f -> f.extension == "json" } ?: emptyArray())
        .sortedBy { it.name }
        .also { require(it.isNotEmpty()) { "В spec/golden нет файлов" } }

/**
 * Проверки фундамента на всех эталонах spec/golden: события и связи разбираются, обратно
 * пишутся в тот же JSON, а `fields()` событий совпадает со снимками в связях.
 */
class GoldenFilesTest {
    private val files = goldenFiles()

    @Test
    fun everyScenarioFileIsReadAndNamedAfterItsScenario() {
        for (file in files) {
            val scenario = GoldenScenario.parse(file.readText())
            assertEquals(file.nameWithoutExtension, scenario.name, file.name)
        }
        println("Эталонов: ${files.size}")
    }

    @Test
    fun inputRoundTripsToEqualJson() {
        for (file in files) {
            val original = root(file).getValue("input")
            val parsed = GoldenInput.fromJson(original)
            assertEquals(original, parsed.toJson(), "${file.name}: input")
            assertEquals(parsed, GoldenInput.fromJson(parsed.toJson()), "${file.name}: input")
        }
    }

    @Test
    fun everyEventAndLinkInExpectedRoundTrips() {
        var events = 0
        var links = 0
        for (file in files) {
            val expected = root(file).getValue("expected") as JsonObject
            forEachEvent(expected) { json ->
                assertEquals(json, SyncJson.eventToJson(SyncJson.eventFromJson(json)), file.name)
                events++
            }
            forEachLink(root(file)) { json ->
                assertEquals(json, SyncJson.linkToJson(SyncJson.linkFromJson(json)), file.name)
                links++
            }
        }
        println("Событий в expected: $events, связей (вход и выход): $links")
        assertTrue(events > 0 && links > 0)
    }

    @Test
    fun linkKeysAreComputedLikePython() {
        for (file in files) {
            forEachLink(root(file)) { json ->
                val stored = ((json as JsonObject).getValue("key") as JsonPrimitive).content
                assertEquals(stored, SyncJson.linkFromJson(json).key(), file.name)
            }
        }
    }

    @Test
    fun snapshotTimesAreFormattedLikePythonIsoformat() {
        var checked = 0
        for (file in files) {
            forEachLink(root(file)) { json ->
                val link = SyncJson.linkFromJson(json)
                for (fields in listOfNotNull(link.sourceFields, link.targetFields)) {
                    assertEquals(FIELD_NAMES.toSet(), fields.keys, file.name)
                    for (name in listOf("start", "end")) {
                        val text = fields.getValue(name)
                        val reparsed = When.parse(if (text.length == 10) text else text.replace("+00:00", "Z"))
                        assertEquals(text, reparsed.isoformat(), "${file.name}: $name")
                        checked++
                    }
                }
            }
        }
        assertTrue(checked > 0)
    }

    /**
     * Снимок связи — это `fields()` события на момент прошлой синхронизации. Где снимок и
     * событие должны совпасть:
     * - на входе — у каждой связи, которую план не обновляет действием `link` (если обновляет,
     *   сценарий как раз меняет событие после снимка) и которая не касается недоступной системы
     *   (`unavailable`: такие связи заморожены, сценарий может менять их события);
     * - после `apply` — у всех связей, кроме касающихся недоступной системы, если повторный
     *   план пуст (всё выполнено, эха нет).
     * Совпадение проверяется посимвольно — так их сравнивает движок.
     */
    @Test
    fun fieldsEqualStoredSnapshotsWhereTheyCorrespond() {
        var matched = 0
        for (file in files) {
            val scenario = GoldenScenario.parse(file.readText())
            val refreshed = linkActionKeys(scenario.expected)
            val down = scenario.input.unavailable
            matched += checkSnapshots(
                file.name + " input", scenario.input.calendars, scenario.input.links,
            ) { it.key() !in refreshed && it.sourceSystem !in down && it.targetSystem !in down }

            val apply = scenario.expected.getValue("apply") as JsonObject
            val replan = (scenario.expected.getValue("replan") as JsonObject).getValue("actions") as JsonArray
            if (replan.isEmpty()) {
                matched += checkSnapshots(
                    file.name + " after apply",
                    GoldenInput.calendarsFromJson(apply.getValue("calendars")),
                    (apply.getValue("links") as JsonArray).map(SyncJson::linkFromJson),
                ) { it.sourceSystem !in down && it.targetSystem !in down }
            }
        }
        println("Снимков, совпавших с fields(): $matched")
        assertTrue(matched > 100, "слишком мало сопоставленных снимков: $matched")
    }

    // --- помощники ---

    private fun root(file: File): JsonObject = Json.parseToJsonElement(file.readText()) as JsonObject

    private fun checkSnapshots(
        label: String,
        calendars: Map<System, List<CalendarEvent>>,
        links: List<Link>,
        applies: (Link) -> Boolean,
    ): Int {
        val byId = calendars.flatMap { (system, events) -> events.map { (system to it.id) to it } }.toMap()
        var matched = 0
        for (link in links.filter(applies)) {
            val sides = listOf(
                Triple("source", link.sourceSystem to link.sourceId, link.sourceFields),
                Triple("target", link.targetSystem to link.targetId, link.targetFields),
            )
            for ((side, ref, snapshot) in sides) {
                val event = byId[ref] ?: continue
                if (snapshot == null) continue
                assertEquals(snapshot, event.fields(), "$label: ${link.key()} $side")
                matched++
            }
        }
        return matched
    }

    /** Ключи связей, которым план сохраняет новый снимок (служебные действия `link`). */
    private fun linkActionKeys(expected: JsonObject): Set<String> {
        val result = mutableSetOf<String>()
        fun walk(actions: JsonElement) {
            for (a in actions as JsonArray) {
                val action = a as JsonObject
                val link = action["link"]
                if ((action.getValue("kind") as JsonPrimitive).content == "link" && link is JsonObject) {
                    result += SyncJson.linkFromJson(link).key()
                }
                walk(action.getValue("fallback"))
            }
        }
        walk((expected.getValue("plan") as JsonObject).getValue("actions"))
        return result
    }

    /**
     * Все события в `expected`: календари после apply, найденные через get, события действий
     * (плана, повторного плана и их fallback).
     */
    private fun forEachEvent(expected: JsonObject, block: (JsonElement) -> Unit) {
        val apply = expected.getValue("apply") as JsonObject
        for ((_, events) in apply.getValue("calendars") as JsonObject) (events as JsonArray).forEach(block)
        val collect = expected.getValue("collect") as JsonObject
        for (f in collect.getValue("fetched") as JsonArray) {
            (f as JsonObject).getValue("event").takeUnless { it is JsonNull }?.let(block)
        }
        forEachAction(expected) { action -> action.getValue("event").takeUnless { it is JsonNull }?.let(block) }
    }

    /** Все связи файла: вход, после apply, связи действий. */
    private fun forEachLink(root: JsonObject, block: (JsonElement) -> Unit) {
        val input = root.getValue("input") as JsonObject
        (input.getValue("links") as JsonArray).forEach(block)
        val expected = root.getValue("expected") as JsonObject
        ((expected.getValue("apply") as JsonObject).getValue("links") as JsonArray).forEach(block)
        forEachAction(expected) { action -> action.getValue("link").takeUnless { it is JsonNull }?.let(block) }
    }

    private fun forEachAction(expected: JsonObject, block: (JsonObject) -> Unit) {
        fun walk(actions: JsonElement) {
            for (a in actions as JsonArray) {
                block(a as JsonObject)
                walk(a.getValue("fallback"))
            }
        }
        walk((expected.getValue("plan") as JsonObject).getValue("actions"))
        walk((expected.getValue("replan") as JsonObject).getValue("actions"))
    }
}
