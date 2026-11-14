package app.gcaltolotus.adapters.teams

import app.gcaltolotus.sync.PyText

private val BLOCKS = setOf("br", "p", "div", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6")
private val SKIP = setOf("style", "script", "head", "title")

/** Элементы, содержимое которых — сырой текст до закрывающего тега (как CDATA_CONTENT_ELEMENTS). */
private val RAW_TEXT = setOf("script", "style")

/**
 * Минимальное преобразование HTML-тела письма в текст. Порт `html_to_text` (на Python
 * `HTMLParser`):
 * - блочные теги (`br`, `p`, `div`, `li`, `tr`, `h1`–`h6`) дают перевод строки при открытии и
 *   (кроме `br`) при закрытии; `<br/>` — как открытие и закрытие;
 * - текст внутри `style`, `script`, `head`, `title` выбрасывается, комментарии и `<!...>` тоже;
 * - сущности раскрываются: числовые и распространённые именованные. Неизвестная сущность
 *   остаётся как есть. Отличие от Python: у Python полная таблица HTML5 и сущности без `;`.
 * - неразрывный пробел становится пробелом, пробелы в строке схлопываются, пустые строки
 *   выбрасываются.
 */
fun htmlToText(content: String): String {
    val parts = StringBuilder()
    var skip = 0
    var i = 0
    val n = content.length

    fun text(from: Int, to: Int) {
        if (skip == 0 && to > from) parts.append(unescape(content.substring(from, to)))
    }

    fun start(tag: String) {
        if (tag in SKIP) skip++ else if (tag in BLOCKS) parts.append('\n')
    }

    fun end(tag: String) {
        if (tag in SKIP) skip = maxOf(0, skip - 1) else if (tag in BLOCKS && tag != "br") parts.append('\n')
    }

    var textStart = 0
    while (i < n) {
        if (content[i] != '<') {
            i++
            continue
        }
        val next = content.getOrNull(i + 1)
        when {
            next != null && next.isLetter() -> {
                text(textStart, i)
                val close = tagEnd(content, i + 1)
                val inner = content.substring(i + 1, close)
                val tag = tagName(inner)
                val selfClosing = inner.endsWith("/")
                i = minOf(close + 1, n)
                start(tag)
                if (selfClosing) {
                    end(tag)
                } else if (tag in RAW_TEXT) {
                    // Сырой текст до </tag: теги внутри не разбираются.
                    val stop = content.indexOf("</$tag", i, ignoreCase = true).let { if (it < 0) n else it }
                    if (skip == 0) parts.appendRange(content, i, stop)
                    i = stop
                }
                textStart = i
            }
            next == '/' && content.getOrNull(i + 2)?.isLetter() == true -> {
                text(textStart, i)
                val close = tagEnd(content, i + 2)
                end(tagName(content.substring(i + 2, close)))
                i = minOf(close + 1, n)
                textStart = i
            }
            content.startsWith("<!--", i) -> {
                text(textStart, i)
                val close = content.indexOf("-->", i + 4)
                i = if (close < 0) n else close + 3
                textStart = i
            }
            next == '!' || next == '?' -> {
                text(textStart, i)
                val close = content.indexOf('>', i + 2)
                i = if (close < 0) n else close + 1
                textStart = i
            }
            else -> i++ // одиночный '<' — обычный текст
        }
    }
    text(textStart, n)

    val plain = parts.toString().replace(' ', ' ')
    return PyText.splitlines(plain)
        .map { PyText.collapseSpaces(it) }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

/** Индекс '>' конца тега (кавычки в атрибутах учитываются) или конец строки. */
private fun tagEnd(s: String, from: Int): Int {
    var quote: Char? = null
    var i = from
    while (i < s.length) {
        val c = s[i]
        if (quote != null) {
            if (c == quote) quote = null
        } else if (c == '"' || c == '\'') {
            quote = c
        } else if (c == '>') {
            return i
        }
        i++
    }
    return s.length
}

private fun tagName(inner: String): String =
    inner.takeWhile { !it.isWhitespace() && it != '/' && it != '>' }.lowercase()

private val NAMED_ENTITIES = mapOf(
    "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "ndash" to "–", "mdash" to "—", "hellip" to "…", "laquo" to "«",
    "raquo" to "»", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“",
    "rdquo" to "”", "bdquo" to "„", "bull" to "•", "middot" to "·",
    "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€",
    "deg" to "°", "times" to "×", "shy" to "­", "zwnj" to "‌",
    "zwj" to "‍", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
)

private val ENTITY = Regex("""&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);""")

private fun unescape(text: String): String {
    if ('&' !in text) return text
    return ENTITY.replace(text) { m ->
        val name = m.groupValues[1]
        if (name.startsWith("#")) {
            val code = if (name[1] == 'x' || name[1] == 'X') {
                name.substring(2).toIntOrNull(16)
            } else {
                name.substring(1).toIntOrNull()
            }
            codePointToString(code) ?: m.value
        } else {
            NAMED_ENTITIES[name] ?: m.value
        }
    }
}

/** Символ по коду; 0, суррогаты и коды вне Unicode — U+FFFD, как в Python `html.unescape`. */
private fun codePointToString(code: Int?): String? {
    if (code == null) return "�"
    if (code == 0 || code in 0xD800..0xDFFF || code > 0x10FFFF) return "�"
    if (code < 0x10000) return code.toChar().toString()
    val v = code - 0x10000
    return charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
}
