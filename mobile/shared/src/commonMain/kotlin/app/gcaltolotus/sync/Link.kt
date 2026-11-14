package app.gcaltolotus.sync

/** Вид связи (`Link.kind` в Python). [value] — строка в JSON и в ключе. */
enum class LinkKind(val value: String) {
    /** Оригинал (source) и его копия (target). */
    COPY("copy"),

    /** Двойники: одна встреча, созданная независимо в двух системах. */
    TWIN("twin"),

    /** Пользователь отказался считать события двойниками. */
    DISTINCT("distinct");

    override fun toString(): String = value

    companion object {
        fun fromValue(value: String): LinkKind =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Неизвестный вид связи: '$value'")
    }
}

/**
 * Связь между событиями двух систем. Порт `Link` из `src/sync/planner.py` (только тип и
 * ключ; хранение — отдельно).
 *
 * @property sourceFields снимок полей source после прошлой синхронизации ([CalendarEvent.fields]);
 *   `null` у старых связей и у `distinct`. Строки хранятся как есть.
 * @property targetFields то же для target.
 * @property kept одна сторона удалена, а другую пользователь оставил: больше не спрашивать и не
 *   воссоздавать. Связь `copy` с `kept` и без [targetId] — отказ от копирования ([isRefusal]).
 */
data class Link(
    val sourceSystem: System,
    val sourceId: String,
    val targetSystem: System,
    val targetId: String? = null,
    val kind: LinkKind = LinkKind.COPY,
    val sourceFields: Fields? = null,
    val targetFields: Fields? = null,
    val kept: Boolean = false,
) {
    /**
     * Ключ связи (уникален в хранилище):
     * - `copy`: `<source_system>:<source_id>-><target_system>`, например `google:g1->domino`;
     * - иначе: `<kind>:<source_system>:<source_id>-><target_system>:<target_id>`, например
     *   `twin:google:g1->domino:d1`. Пустой `target_id` пишется как `None` — так делает Python
     *   (f-строка от `None`).
     */
    fun key(): String {
        val base = "${sourceSystem.value}:$sourceId->${targetSystem.value}"
        return if (kind == LinkKind.COPY) base else "${kind.value}:$base:${targetId ?: "None"}"
    }

    /**
     * Отказ от копирования (`is_refusal`): связь `copy` с [kept] и без [targetId] — пользователь
     * снял галочку с создания копии при ручном применении, копию не создавать.
     */
    val isRefusal: Boolean get() = kind == LinkKind.COPY && kept && targetId == null

    companion object {
        /**
         * Связь-отказ (`refusal`): оригинал [originId] из [origin] не копировать в [target].
         * Ключ тот же, что у будущей связи копии, поэтому созданная позже копия её заменяет.
         */
        fun refusal(origin: System, originId: String, target: System): Link =
            Link(origin, originId, target, null, kept = true)
    }
}
