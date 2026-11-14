package app.gcaltolotus.sync

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Начало/конец события: момент (timed-событие) или дата (событие на весь день).
 * Порт `When = Union[datetime, date]` из `src/sync/event.py`.
 *
 * Два текстовых вида:
 * - [isoformat] — как Python `isoformat()` в UTC: `2026-11-13T10:00:00+00:00`, с
 *   `.ffffff`, только если микросекунды не нулевые; дата — `2026-11-13`. Так пишутся снимки
 *   полей (`CalendarEvent.fields()`) и ключи действий.
 * - [toJson] — формат JSON (`serialize.when_to_json`): `2026-11-13T10:00:00Z`; дата — так же.
 *
 * Сравнение ([compareTo]) — только внутри одного вида, как в Python (`datetime < date` — ошибка).
 */
sealed interface When : Comparable<When> {
    /** Момент в UTC. Точность Python — микросекунды; наносекунды в текст не попадают. */
    data class Timed(val instant: Instant) : When {
        override fun toString(): String = toJson()
    }

    /** Дата события на весь день (конец события — исключённая дата). */
    data class AllDay(val date: LocalDate) : When {
        override fun toString(): String = toJson()
    }

    val isAllDay: Boolean get() = this is AllDay

    /** Текст как у Python `isoformat()` после приведения к UTC. */
    fun isoformat(): String = when (this) {
        is AllDay -> formatDate(date)
        is Timed -> formatInstant(instant) + "+00:00"
    }

    /** Текст для JSON: момент с `Z`, дата `YYYY-MM-DD`. */
    fun toJson(): String = when (this) {
        is AllDay -> formatDate(date)
        is Timed -> formatInstant(instant) + "Z"
    }

    override fun compareTo(other: When): Int = when {
        this is Timed && other is Timed -> instant.compareTo(other.instant)
        this is AllDay && other is AllDay -> date.compareTo(other.date)
        else -> throw IllegalArgumentException("Нельзя сравнить момент и дату: $this, $other")
    }

    companion object {
        fun of(instant: Instant): When = Timed(instant)

        fun of(date: LocalDate): When = AllDay(date)

        /**
         * Разбор JSON-значения (`serialize.when_from_json`): 10 символов — дата, иначе момент
         * ISO 8601 со смещением (`Z` или `+03:00`), приводится к UTC. Момент без смещения —
         * ошибка (в Python такое событие тоже не создаётся: naive datetime).
         */
        fun parse(text: String): When =
            if (text.length == 10) AllDay(LocalDate.parse(text)) else Timed(Instant.parse(text))

        private fun formatDate(date: LocalDate): String =
            "${pad(date.year, 4)}-${pad(date.month.ordinal + 1, 2)}-${pad(date.day, 2)}"

        private fun formatInstant(instant: Instant): String {
            val t = instant.toLocalDateTime(TimeZone.UTC)
            val base = formatDate(t.date) + "T" + pad(t.hour, 2) + ":" + pad(t.minute, 2) + ":" +
                pad(t.second, 2)
            val micros = t.nanosecond / 1000
            return if (micros != 0) "$base.${pad(micros, 6)}" else base
        }

        private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')
    }
}
