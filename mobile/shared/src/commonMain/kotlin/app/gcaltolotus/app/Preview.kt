package app.gcaltolotus.app

import app.gcaltolotus.adapters.google.GoogleCalendarInfo
import app.gcaltolotus.sync.Action
import app.gcaltolotus.sync.ActionKind
import app.gcaltolotus.sync.DeleteSubject
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.When
import app.gcaltolotus.sync.Window

/** Счётчики маршрута по видам действий (`COUNTERS` в Python: create, update, delete, twin). */
data class RouteCounts(val created: Int = 0, val updated: Int = 0, val deleted: Int = 0, val linked: Int = 0) {
    operator fun plus(other: RouteCounts) = RouteCounts(
        created + other.created, updated + other.updated, deleted + other.deleted, linked + other.linked,
    )

    val isEmpty: Boolean get() = this == RouteCounts()

    internal fun count(kind: ActionKind): RouteCounts = when (kind) {
        ActionKind.CREATE -> copy(created = created + 1)
        ActionKind.UPDATE -> copy(updated = updated + 1)
        ActionKind.DELETE -> copy(deleted = deleted + 1)
        ActionKind.TWIN -> copy(linked = linked + 1)
        ActionKind.LINK, ActionKind.UNLINK -> this
    }
}

/**
 * Строка предпросмотра: то же, что `_action_json` в `src/web/app.py`.
 *
 * @property key ключ действия; отмеченные ключи передаются в [SyncService.applySelected].
 * @property selected галочка по умолчанию.
 * @property remembersRefusal без галочки отказ запомнится (связь `kept` или `distinct`) и действие
 *   больше не предлагается: «удалить оригинал?», «удалить второго двойника?», «это одна встреча?»,
 *   а также создание копии (ADR 0003: снятая галочка у создания — «не копировать»).
 * @property partnerTitle только у `twin`: название второго двойника (в [origin]); [title] — двойник
 *   в [target]. У вопроса по времени (ADR 0003) названия разные, и показать нужно оба.
 */
data class PreviewAction(
    val key: String,
    val kind: ActionKind,
    val origin: System,
    val target: System,
    val title: String,
    val start: When,
    val end: When,
    val allDay: Boolean,
    val location: String,
    val selected: Boolean,
    val reason: String,
    val subject: DeleteSubject?,
    val changed: List<String>,
    val remembersRefusal: Boolean,
    val partnerTitle: String = "",
) {
    val route: Route get() = Route(origin, target)

    /**
     * Вопрос (GLOSSARY.md): действие, которое не выполняется без решения пользователя —
     * запоминаемые отказы (кроме создания копии: оно отмечено и фоном выполняется) и всё, что
     * не отмечено по умолчанию (правка встречи с участниками, удаления при подозрении на
     * массовое удаление, «одна встреча?» по времени). Фоновая синхронизация их не выполняет.
     */
    val isQuestion: Boolean get() = isQuestion(kind, selected, remembersRefusal)

    internal companion object {
        fun of(action: Action, selected: Boolean = action.selected, remembersRefusal: Boolean = action.remembersRefusal()) = PreviewAction(
            key = action.key,
            kind = action.kind,
            origin = action.origin,
            target = action.target,
            title = action.event.title,
            start = action.event.start,
            end = action.event.end,
            allDay = action.event.allDay,
            location = action.event.location,
            selected = selected,
            reason = action.reason,
            subject = action.subject,
            changed = action.changed,
            remembersRefusal = remembersRefusal,
            partnerTitle = if (action.kind == ActionKind.TWIN) action.link?.sourceFields?.get("title").orEmpty() else "",
        )
    }
}

/** Действие с запоминаемым отказом: у него есть fallback (связь `kept`/`distinct`/отказ от копирования). */
internal fun Action.remembersRefusal(): Boolean = fallback.isNotEmpty()

/** Вопрос по глоссарию: запоминаемый отказ (кроме создания копии) или снятая по умолчанию галочка. */
internal fun Action.isQuestion(): Boolean = isQuestion(kind, selected, remembersRefusal())

private fun isQuestion(kind: ActionKind, selected: Boolean, remembersRefusal: Boolean): Boolean =
    !selected || (remembersRefusal && kind != ActionKind.CREATE)

/**
 * Действия одного маршрута.
 *
 * @property defaultCounts что выполнится при выборе по умолчанию, включая «тихие» действия,
 *   которых нет в [actions] (как счётчики `<origin>_to_<target>` в dry run веб-версии).
 */
data class RouteGroup(val route: Route, val actions: List<PreviewAction>, val defaultCounts: RouteCounts)

/**
 * Предпросмотр ручного запуска. Хранит план, по которому его построили: [SyncService.applySelected]
 * выполняет ровно его (один раз).
 *
 * @property actions все действия, требующие показа (без «тихих» и служебных), в порядке плана.
 * @property routes те же действия по маршрутам, в каноническом порядке систем.
 * @property defaultKeys ключи, отмеченные по умолчанию.
 * @property systems системы, календари которых прочитаны.
 * @property refused «Не копируется»: отказы от копирования в окне и в маршрутах — создания копий,
 *   которые предлагались бы, если бы пользователь не снял с них галочку при прошлом ручном
 *   применении. Строки как у создания (`kind = CREATE`, тот же ключ), `selected = false`.
 *   Вернуть: ключ в [SyncService.applySelected] — копия создаётся сейчас; или
 *   [SyncService.undoRefusal] — отказ снимается, копия предлагается в следующем предпросмотре.
 *   Ключей отказов нет в [actions] и [defaultKeys]; фоновая синхронизация их не трогает.
 * @property skipped системы, не участвующие в запуске.
 * @property dominoDisabledNow в этот раз Domino ответил 401: Lotus выключен, запуск продолжен без него.
 * @property dominoUnavailable в этот раз Domino не ответил (сеть, таймаут, 5xx): текст ошибки. Lotus
 *   пропущен только в этом запуске и не выключается; `null` — Domino ответил или не участвовал.
 */
class Preview internal constructor(
    val window: Window,
    val actions: List<PreviewAction>,
    val routes: List<RouteGroup>,
    val defaultKeys: Set<String>,
    val warnings: List<String>,
    val systems: Set<System>,
    val skipped: List<SkippedSystem>,
    val dominoDisabledNow: Boolean,
    internal val prepared: PreparedRun,
    val dominoUnavailable: String? = null,
    val refused: List<PreviewAction> = emptyList(),
) {
    /** Число вопросов (для бейджа и уведомления). */
    val openQuestions: Int get() = actions.count { it.isQuestion }

    /**
     * Вопросы, которые останутся открытыми после применения [selected]: без галочки и без
     * запоминаемого отказа (снятая галочка у такого вопроса становится отказом — он закрыт).
     */
    fun openQuestionsAfter(selected: Set<String>): Int =
        actions.count { it.isQuestion && !it.remembersRefusal && it.key !in selected }

    internal var applied: Boolean = false
}

/** Итог одного маршрута: выполненные действия и ошибки (`"<название>: <сообщение>"`). */
data class RouteResult(val counts: RouteCounts, val errors: List<String>)

/**
 * Отчёт запуска (`<origin>_to_<target>` в ответе `/api/sync`).
 *
 * @property routes маршруты в порядке первого действия.
 * @property dominoDisabledNow в этот раз Domino ответил 401: Lotus выключен до ввода пароля.
 * @property dominoUnavailable в этот раз Domino не ответил (сеть, таймаут, 5xx; при чтении или при
 *   записи): текст ошибки. Lotus пропущен только в этом запуске, остальные системы синхронизированы;
 *   `null` — Domino ответил или не участвовал. Уведомлять ли — [shouldNotifyDominoUnreachable].
 */
data class RunReport(
    val routes: Map<Route, RouteResult>,
    val skipped: List<SkippedSystem>,
    val dominoDisabledNow: Boolean,
    val warnings: List<String>,
    val dominoUnavailable: String? = null,
) {
    val totals: RouteCounts get() = routes.values.fold(RouteCounts()) { acc, r -> acc + r.counts }

    /** Все ошибки с маршрутом: `google>teams: Обзор: HTTP 500`. */
    val errors: List<String> get() = routes.flatMap { (route, r) -> r.errors.map { "$route: $it" } }
}

/**
 * Отчёт фоновой синхронизации.
 *
 * @property openQuestions вопросы, оставленные до ручного запуска (для уведомления).
 */
data class BackgroundReport(val run: RunReport, val openQuestions: Int)

/** Сколько копий синхронизатора лежит в текущем календаре Google ([current]); их предлагаем удалить. */
data class GoogleSwitchPreview(val current: String, val oldCopies: Int)

/** Итог смены календаря Google: удалено копий и ошибки (`"<id копии>: <сообщение>"`). */
data class GoogleSwitchResult(val deleted: Int, val errors: List<String>)

/**
 * Календари аккаунта Google для выбора (`GET /api/google/calendars`).
 *
 * @property current календарь из настроек (может быть псевдонимом `primary`).
 * @property calendars все календари аккаунта; копии можно писать только в [GoogleCalendarInfo.writable].
 */
data class GoogleCalendars(val current: String, val calendars: List<GoogleCalendarInfo>) {
    /** Календари, куда можно писать копии. */
    val writable: List<GoogleCalendarInfo> get() = calendars.filter { it.writable }

    /**
     * Что можно выбрать в списке, как в веб-версии /v2: календари с правом записи и текущий,
     * даже если он только для чтения (его показывают выбранным).
     */
    val selectable: List<GoogleCalendarInfo> get() = calendars.filter { it.writable || isCurrent(it) }

    /** Текущий календарь из списка; `null` — его в аккаунте нет (удалён или нет доступа). */
    val currentCalendar: GoogleCalendarInfo? get() = calendars.firstOrNull { it.id == current } ?: calendars.firstOrNull { isCurrent(it) }

    /** Это текущий календарь: совпадает id или в настройках `primary`, а календарь основной. */
    fun isCurrent(calendar: GoogleCalendarInfo): Boolean =
        calendar.id == current || (current == PRIMARY_CALENDAR_ID && calendar.primary)
}
