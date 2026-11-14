package app.gcaltolotus.sync

import kotlin.time.Instant

/**
 * Снимок полей: результат [CalendarEvent.fields]. Ключи — [FIELD_NAMES], значения — строки,
 * байт в байт совпадающие с Python `CalendarEvent.fields()`. Хранится в связях как есть.
 */
typealias Fields = Map<String, String>

/** Синхронизируемые поля в каноническом порядке (так же перечисляются `changed` у действий). */
val FIELD_NAMES: List<String> = listOf("title", "start", "end", "location", "description")

/**
 * Единая модель события, которую адаптеры отдают движку. Порт `CalendarEvent` из
 * `src/sync/event.py`.
 *
 * Адаптер обязан на своей стороне:
 * - убрать маркер `[GC]`/`[LN]`/`[MT]` из названия и указать систему-источник в [source]
 *   (см. [splitMarker]);
 * - передать время как [When.Timed] (момент, UTC) или, для событий «весь день», [When.AllDay]
 *   с исключённым концом (однодневное событие 13.11 — `start` 13.11, `end` 14.11).
 *
 * Конструктор, как `__post_init__` в Python, схлопывает пробелы в [title] и требует, чтобы
 * [start] и [end] были одного вида (оба момента или обе даты), иначе
 * [IllegalArgumentException]. [copy] проходит через тот же конструктор.
 *
 * Равенство — по всем полям (как у frozen dataclass).
 *
 * @property source система, из которой событие скопировано; `null` — родное (оригинал).
 * @property id id события в его системе.
 * @property uid iCalendar UID, общий для одной встречи в разных системах (у серии — общий для
 *   экземпляров).
 * @property originalStart исходное начало экземпляра повторяющейся встречи (RECURRENCE-ID);
 *   `null` у одиночных.
 * @property organizerIsMe `false` — чужая встреча: синхронизатор её только читает.
 * @property hasAttendees правка такого оригинала разошлёт письма участникам.
 * @property isPrivate частное событие; копия тоже частная (`private` в Python и JSON).
 * @property modified время последнего изменения на сервере.
 * @property ignored служебное / отклонённое / отменённое: не копируется, копии удаляются.
 */
class CalendarEvent(
    title: String,
    val start: When,
    val end: When,
    val location: String = "",
    val description: String = "",
    val source: System? = null,
    val id: String? = null,
    val uid: String? = null,
    val originalStart: When? = null,
    val organizerIsMe: Boolean = true,
    val hasAttendees: Boolean = false,
    val isPrivate: Boolean = false,
    val modified: Instant? = null,
    val ignored: Boolean = false,
) {
    /** Название без маркера; пробелы схлопнуты в одиночные, края обрезаны. */
    val title: String = PyText.collapseSpaces(title)

    init {
        require(start.isAllDay == end.isAllDay) {
            "start и end должны быть оба моментами или обе датами: $start, $end"
        }
    }

    /** Событие на весь день. */
    val allDay: Boolean get() = start.isAllDay

    /** Ключ для распознавания копии: название + начало (момент UTC или дата). */
    fun matchKey(): EventKey = EventKey(title, start)

    /** Ключ кандидата в двойники: название без учёта регистра (Python `casefold`) + начало. */
    fun twinKey(): EventKey = EventKey(PyText.casefold(title), start)

    /**
     * Ключ кандидата в двойники по времени: начало + конец (`time_key`). У событий на весь день
     * ключа нет (`null`): одинаковые даты у разных событий — обычное дело.
     */
    fun timeKey(): Pair<When, When>? = if (allDay) null else start to end

    /** Ключ двойника по UID: UID + исходное начало экземпляра (или начало); без UID — `null`. */
    fun uidKey(): EventKey? {
        if (uid.isNullOrEmpty()) return null
        return EventKey(uid, originalStart ?: start)
    }

    /**
     * Синхронизируемые поля в нормализованном виде — для снимка и сравнения.
     * Строки совпадают с Python `fields()` байт в байт (spec/README.md, «Снимок полей»):
     * - `title` — как есть (уже нормализовано);
     * - `start`/`end` — [When.isoformat]: `2026-11-13T10:00:00+00:00` или `2026-11-13`;
     * - `location` — пробельные символы схлопнуты, края обрезаны;
     * - `description` — края обрезаны, у каждой строки обрезаны пробелы справа, строки
     *   соединены `\n`.
     */
    fun fields(): Fields = linkedMapOf(
        "title" to title,
        "start" to start.isoformat(),
        "end" to end.isoformat(),
        "location" to PyText.collapseSpaces(location),
        "description" to PyText.splitlines(PyText.strip(description))
            .joinToString("\n") { PyText.rstrip(it) },
    )

    /** Название с маркером системы-источника, как его видит пользователь. */
    fun markedTitle(): String = if (source == null) title else "${source.marker} $title"

    fun copy(
        title: String = this.title,
        start: When = this.start,
        end: When = this.end,
        location: String = this.location,
        description: String = this.description,
        source: System? = this.source,
        id: String? = this.id,
        uid: String? = this.uid,
        originalStart: When? = this.originalStart,
        organizerIsMe: Boolean = this.organizerIsMe,
        hasAttendees: Boolean = this.hasAttendees,
        isPrivate: Boolean = this.isPrivate,
        modified: Instant? = this.modified,
        ignored: Boolean = this.ignored,
    ): CalendarEvent = CalendarEvent(
        title, start, end, location, description, source, id, uid, originalStart,
        organizerIsMe, hasAttendees, isPrivate, modified, ignored,
    )

    private fun parts(): List<Any?> = listOf(
        title, start, end, location, description, source, id, uid, originalStart,
        organizerIsMe, hasAttendees, isPrivate, modified, ignored,
    )

    override fun equals(other: Any?): Boolean =
        this === other || (other is CalendarEvent && parts() == other.parts())

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String =
        "CalendarEvent(title=$title, start=$start, end=$end, location=$location, " +
            "description=$description, source=$source, id=$id, uid=$uid, " +
            "originalStart=$originalStart, organizerIsMe=$organizerIsMe, " +
            "hasAttendees=$hasAttendees, isPrivate=$isPrivate, modified=$modified, " +
            "ignored=$ignored)"
}

/**
 * Ключ события (`match_key`, `twin_key`, `uid_key`): строка + момент/дата.
 * В Python это кортеж `(str, When)`; момент и дата с одинаковым днём не равны.
 */
data class EventKey(val text: String, val start: When)

/**
 * Отделить маркер чужой системы от названия (`split_marker`).
 *
 * Возвращает нормализованное название и систему маркера; маркер собственной системы [own]
 * не считается признаком копии и остаётся в названии.
 */
fun splitMarker(rawTitle: String, own: System): Pair<String, System?> {
    val title = PyText.strip(rawTitle)
    for (system in System.ORDER) {
        if (system != own && title.startsWith(system.marker)) {
            return PyText.collapseSpaces(title.substring(system.marker.length)) to system
        }
    }
    return PyText.collapseSpaces(title) to null
}
