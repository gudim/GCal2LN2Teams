package app.gcaltolotus.sync

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest

/**
 * Приёмка движка: на каждом файле spec/golden прогон collect → plan → select → apply → replan
 * ([runGolden]) даёт ровно `expected` (сравнивается разобранный JSON).
 */
class GoldenPlansTest {
    @Test
    fun everyGoldenScenarioProducesExpected() = runTest {
        val files = goldenFiles()
        val failed = mutableListOf<String>()
        for (file in files) {
            val scenario = GoldenScenario.parse(file.readText())
            val actual = try {
                runGolden(scenario.input)
            } catch (e: Exception) {
                failed += "${scenario.name}: исключение $e"
                continue
            }
            if (actual != scenario.expected) {
                failed += "${scenario.name}: " + (firstDifference(scenario.expected, actual) ?: "?")
            }
        }
        println("Эталонов совпало: ${files.size - failed.size}/${files.size}")
        if (failed.isNotEmpty()) fail("Не совпали ${failed.size}/${files.size}:\n" + failed.joinToString("\n"))
        assertTrue(files.size >= 44, "эталонов меньше, чем при переносе: ${files.size}")
    }
}
