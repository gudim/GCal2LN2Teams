package app.gcaltolotus.sync

/**
 * Строковые операции Python (`str.split()`, `strip()`, `rstrip()`, `splitlines()`,
 * `casefold()`), повторённые символ в символ: снимок полей сравнивается как строки, и Kotlin
 * обязан нормализовать текст ровно так же, как Python.
 *
 * Kotlin `isWhitespace()`/`trim()`/`lines()` отличаются от Python (другой набор пробельных
 * символов и разделителей строк), поэтому здесь свои наборы.
 */
internal object PyText {
    /** Символы, для которых Python `str.isspace()` истинно (все в BMP). */
    private const val SPACES =
        "\u0009\u000a\u000b\u000c\u000d\u001c\u001d\u001e\u001f \u0085  " +
            "           " +
            "    　"

    /** Разделители строк Python `str.splitlines()` (кроме пары `\r\n`, она обрабатывается отдельно). */
    private const val LINE_BREAKS =
        "\u000a\u000b\u000c\u000d\u001c\u001d\u001e\u0085  "

    fun isSpace(c: Char): Boolean = SPACES.indexOf(c) >= 0

    /** `str.split()` без аргументов: слова между отрезками пробельных символов, без пустых. */
    fun split(s: String): List<String> {
        val words = mutableListOf<String>()
        var start = -1
        for (i in s.indices) {
            if (isSpace(s[i])) {
                if (start >= 0) {
                    words += s.substring(start, i)
                    start = -1
                }
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) words += s.substring(start)
        return words
    }

    /** `" ".join(s.split())`: пробелы схлопнуты в один, края обрезаны. */
    fun collapseSpaces(s: String): String = split(s).joinToString(" ")

    fun strip(s: String): String = s.trim(::isSpace)

    fun rstrip(s: String): String = s.trimEnd(::isSpace)

    /** `str.splitlines()`: `\r\n` — один разделитель; пустой строки в конце нет. */
    fun splitlines(s: String): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (LINE_BREAKS.indexOf(c) >= 0) {
                lines += s.substring(start, i)
                i += if (c == '\r' && i + 1 < s.length && s[i + 1] == '\n') 2 else 1
                start = i
            } else {
                i++
            }
        }
        if (start < s.length) lines += s.substring(start)
        return lines
    }

    /**
     * Приближение `str.casefold()`: `lowercase().uppercase().lowercase()` без учёта локали.
     * Совпадает с Python для кириллицы, латиницы и `ß`/`ẞ` → `ss`; полной таблицы
     * Unicode case folding в Kotlin нет, редкие письменности (например, чероки) могут
     * отличаться.
     */
    fun casefold(s: String): String = s.lowercase().uppercase().lowercase()
}
