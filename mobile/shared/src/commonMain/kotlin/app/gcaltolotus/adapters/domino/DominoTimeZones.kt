package app.gcaltolotus.adapters.domino

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.json.JsonObject

/*
 * Описание зоны из массива `timezones` детального ответа Domino (аналог VTIMEZONE):
 * `standard`/`daylight` с `offsetTo` и `recurrenceRule` вида
 * `FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU;BYHOUR=2;BYMINUTE=0`. Порт `_yearly_transition`,
 * `_offset`, `_utc_offset` из src/adapters/domino_rest.py.
 */

private val WEEKDAYS = mapOf("MO" to 0, "TU" to 1, "WE" to 2, "TH" to 3, "FR" to 4, "SA" to 5, "SU" to 6)

private fun LocalDate.weekday(): Int = dayOfWeek.isoDayNumber - 1

private fun Int.floorMod(m: Int): Int = ((this % m) + m) % m

/** Момент перехода (местное время) по правилу `FREQ=YEARLY;BYMONTH=..;BYDAY=-1SU;BYHOUR=..`. */
internal fun yearlyTransition(rule: JsonObject?, year: Int): LocalDateTime? {
    val parts = (rule?.str("recurrenceRule") ?: "").split(";")
        .filter { '=' in it }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
    val month = parts["BYMONTH"]?.toInt() ?: return null
    val byDay = parts["BYDAY"] ?: return null
    val match = Regex("([+-]?\\d+)([A-Z]{2})").matchEntire(byDay) ?: return null
    val nth = match.groupValues[1].toInt()
    val weekday = WEEKDAYS[match.groupValues[2]]
        ?: throw IllegalArgumentException("Неизвестный день недели в правиле зоны: $byDay")
    val day = if (nth > 0) {
        val first = LocalDate(year, month, 1)
        first.plus((weekday - first.weekday()).floorMod(7) + 7 * (nth - 1), DateTimeUnit.DAY)
    } else {
        val last = LocalDate(year + if (month == 12) 1 else 0, month % 12 + 1, 1).minus(1, DateTimeUnit.DAY)
        last.minus((last.weekday() - weekday).floorMod(7) + 7 * (-nth - 1), DateTimeUnit.DAY)
    }
    val clock = LocalTime(parts["BYHOUR"]?.toInt() ?: 0, parts["BYMINUTE"]?.toInt() ?: 0)
    return LocalDateTime(day, clock)
}

/** `+0200` / `-0330` -> смещение. */
internal fun parseOffset(value: String): UtcOffset {
    val sign = if (value.startsWith("-")) -1 else 1
    val digits = value.trimStart('+', '-')
    val hours = digits.take(2).toInt()
    val minutes = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
    return UtcOffset(seconds = sign * (hours * 3600 + minutes * 60))
}

private fun offsetTo(part: JsonObject?): UtcOffset =
    parseOffset(part?.str("offsetTo") ?: throw IllegalArgumentException("Нет offsetTo в описании зоны"))

/** Смещение зоны для местного времени [local]: летнее между переходами, иначе стандартное. */
internal fun utcOffset(zone: JsonObject, local: LocalDateTime): UtcOffset {
    val standard = zone["standard"] as? JsonObject
    val daylight = zone["daylight"] as? JsonObject
    if (daylight.isNullOrEmpty()) return offsetTo(standard)
    val toDaylight = yearlyTransition(daylight, local.year)
    val toStandard = yearlyTransition(standard, local.year)
    if (toDaylight == null || toStandard == null) return offsetTo(standard)
    val summer = if (toDaylight < toStandard) {
        local >= toDaylight && local < toStandard
    } else { // южное полушарие
        !(local >= toStandard && local < toDaylight)
    }
    return offsetTo(if (summer) daylight else standard)
}
