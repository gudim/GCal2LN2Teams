package app.gcaltolotus.sync

import kotlinx.datetime.LocalDate

/** Вид действия (`Action.kind` в Python). [value] — строка в JSON и в ключе. */
enum class ActionKind(val value: String) {
    /** Создать копию оригинала в [Action.target]. */
    CREATE("create"),

    /** Переписать синхронизируемые поля события. */
    UPDATE("update"),

    /** Удалить событие. */
    DELETE("delete"),

    /** Вопрос «это одна встреча?»: связать двойников. */
    TWIN("twin"),

    /** Служебное: сохранить связь. */
    LINK("link"),

    /** Служебное: удалить связь. */
    UNLINK("unlink");

    /** Служебное действие: без события, выполняется всегда. */
    val isBookkeeping: Boolean get() = this == LINK || this == UNLINK

    override fun toString(): String = value
}

/** Что удаляет действие `delete` (`Action.subject` в Python). */
enum class DeleteSubject(val value: String) {
    /** Копию, чей оригинал удалён. */
    COPY("copy"),

    /** Лишнюю копию: в календаре есть двойник её оригинала. */
    EXTRA("extra"),

    /** Оригинал, чья копия удалена (только с галочкой). */
    ORIGINAL("original"),

    /** Двойника, чей двойник удалён (только с галочкой). */
    TWIN("twin");

    override fun toString(): String = value
}

/**
 * Одно действие синхронизации. Порт `Action` из `src/sync/planner.py`.
 *
 * - `create`: [event] — будущая копия (`source` = система оригинала, `id` = id оригинала);
 * - `update`: [event] — новое содержимое события с id цели; [changed] — изменённые поля;
 * - `delete`: [event] — удаляемое событие; [link] — связь, которую убрать после удаления;
 * - `twin`: [event] — двойник в [target]; [link] — связь двойников;
 * - `link`/`unlink`: [link] — связь, которую сохранить/удалить; [event] — пустая заглушка.
 *
 * @property origin откуда изменение (система оригинала или победившей стороны).
 * @property selected галочка по умолчанию в предпросмотре.
 * @property reason текст для пользователя (на русском), может быть пустым.
 * @property dependsOn выполнять, только если действия с этими ключами уже выполнены.
 * @property fallback выполнить вместо этого, если пользователь снял галочку.
 * @property silent не требует подтверждения и не показывается в предпросмотре.
 * @property subject только у `delete`: что удаляется.
 */
data class Action(
    val kind: ActionKind,
    val target: System,
    val event: CalendarEvent,
    val origin: System,
    val selected: Boolean = true,
    val reason: String = "",
    val changed: List<String> = emptyList(),
    val link: Link? = null,
    val dependsOn: List<String> = emptyList(),
    val fallback: List<Action> = emptyList(),
    val silent: Boolean = false,
    val subject: DeleteSubject? = null,
) {
    /**
     * Стабильный ключ между предпросмотром и запуском:
     * - `create`: `<origin>:<event.id>><target>`; без id — `<origin>:<title>@<start isoformat>><target>`;
     * - `link`, `unlink`, `twin`: `<kind>:<ключ связи>`, например `twin:twin:google:g1->domino:d1`;
     * - `update`, `delete`: `<kind>:<target>:<event.id>`.
     */
    val key: String
        get() = when (kind) {
            ActionKind.CREATE -> {
                val ident = event.id ?: "${event.title}@${event.start.isoformat()}"
                "${origin.value}:$ident>${target.value}"
            }
            ActionKind.LINK, ActionKind.UNLINK, ActionKind.TWIN ->
                "${kind.value}:${checkNotNull(link) { "У действия $kind нет связи" }.key()}"
            ActionKind.UPDATE, ActionKind.DELETE -> "${kind.value}:${target.value}:${event.id ?: "None"}"
        }

    companion object {
        /** Заглушка события служебных действий (`date.min` в Python). */
        internal val NO_EVENT: CalendarEvent = LocalDate(1, 1, 1).let {
            CalendarEvent(title = "", start = When.AllDay(it), end = When.AllDay(it))
        }

        /** Служебное действие `link`/`unlink` над связью: тихое, без события. */
        internal fun bookkeeping(kind: ActionKind, link: Link): Action =
            Action(kind, link.targetSystem, NO_EVENT, link.sourceSystem, link = link, silent = true)
    }
}

/**
 * План: действия в порядке выполнения и предупреждения (на русском).
 *
 * @property refused отказы в окне (`Plan.refused` в Python): создания копий, которые план
 *   предложил бы, если бы не отказ; у каждого [Action.link] — связь-отказ. Ключ такого действия,
 *   переданный в [select] вместе с [refused], создаёт копию (связь копии заменяет отказ).
 */
data class Plan(
    val actions: List<Action>,
    val warnings: List<String> = emptyList(),
    val refused: List<Action> = emptyList(),
)

/** Участник связи: событие [id] в системе [system] (`Member` в Python). */
data class Member(val system: System, val id: String?)
