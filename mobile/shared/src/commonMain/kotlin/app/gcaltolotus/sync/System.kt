package app.gcaltolotus.sync

/**
 * Система календаря. Порт `System` из `src/sync/event.py`.
 *
 * Порядок констант — канонический порядок систем (google, domino, teams; `ORDER` в движке):
 * в нём обходятся календари, по нему определяется первая система пары и корень группы связей.
 * Используйте [System.ORDER] или `entries`, а не сортировку по [value].
 *
 * @property value строка системы в JSON и в ключах (`"google"`, `"domino"`, `"teams"`).
 * @property marker маркер в названии копии, сделанной из этой системы (`MARKERS` в Python).
 */
enum class System(val value: String, val marker: String) {
    GOOGLE("google", "[GC]"),
    DOMINO("domino", "[LN]"),
    TEAMS("teams", "[MT]");

    override fun toString(): String = value

    companion object {
        /** Канонический порядок: google, domino, teams. */
        val ORDER: List<System> = entries.toList()

        /** Система по строке `value`; неизвестная строка — [IllegalArgumentException]. */
        fun fromValue(value: String): System =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Неизвестная система: '$value'")
    }
}
