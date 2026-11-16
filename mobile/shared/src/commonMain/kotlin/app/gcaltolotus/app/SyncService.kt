package app.gcaltolotus.app

import app.gcaltolotus.adapters.domino.DominoAuthException
import app.gcaltolotus.adapters.domino.DominoException
import app.gcaltolotus.adapters.domino.DominoHttpException
import app.gcaltolotus.adapters.google.GoogleCalendarDirectory
import app.gcaltolotus.adapters.google.GoogleCalendarInfo
import app.gcaltolotus.sync.Action
import app.gcaltolotus.sync.ActionKind
import app.gcaltolotus.sync.Calendar
import app.gcaltolotus.sync.CalendarEvent
import app.gcaltolotus.sync.Collected
import app.gcaltolotus.sync.DIRECTIONS
import app.gcaltolotus.sync.Link
import app.gcaltolotus.sync.Plan
import app.gcaltolotus.sync.REASON_ATTENDEES
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.Window
import app.gcaltolotus.sync.apply
import app.gcaltolotus.sync.collect
import app.gcaltolotus.sync.plan
import app.gcaltolotus.sync.select
import app.gcaltolotus.sync.undoRefusal
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Запуск невозможен: например, нужен вход в систему. Текст — для пользователя (по-русски). */
class SyncSetupException(message: String) : Exception(message)

/**
 * Оркестрация уровня приложения: то, что делают эндпоинты `src/web/app.py`, для UI и фонового
 * обработчика. Без UI и без платформенного кода.
 *
 * - ручной запуск: [preview] (collect + plan → [Preview]), затем [applySelected] (select + apply
 *   по отмеченным ключам; у снятых вопросов и созданий копий запоминается отказ); отказы от
 *   копирования — [Preview.refused], вернуть — ключом в [applySelected] или [undoRefusal];
 * - фоновый: [runBackground] — только действия, отмеченные по умолчанию и не являющиеся вопросами;
 *   отказы не запоминаются, вопросы остаются открытыми;
 * - [googleSwitchPreview]/[switchGoogleCalendar] — смена календаря Google.
 *
 * 401 от Domino в любом запуске: Lotus выключается ([SyncSettings.dominoDisabled]), к Domino больше
 * не обращаемся (повтор может заблокировать учётную запись), Google ↔ Teams продолжаются, в отчёте
 * `dominoDisabledNow`.
 *
 * Domino не ответил по другой причине (сеть, таймаут, 5xx; [isDominoOutage]): Lotus пропускается
 * только в этом запуске — к Domino в нём больше не обращаемся, остальные системы при
 * необходимости читаются заново без него, [SyncSettings.dominoDisabled] не ставится; в отчёте и
 * предпросмотре `dominoUnavailable` с текстом ошибки. Каждый запуск, где Domino прочитан без
 * ошибок, записывает [SyncSettings.lastDominoSuccess].
 *
 * В обоих случаях (и когда Lotus выключен после 401 в прошлых запусках) Lotus передаётся в
 * `plan` как недоступный: события, связанные с ним связями (копии, двойники, транзитивно), в
 * этом запуске не создаются, не обновляются, не удаляются и не попадают в вопросы. Lotus,
 * исключённый направлением или маршрутами, недоступным не считается.
 */
class SyncService(
    private val settings: SettingsStore,
    private val calendars: CalendarSource,
    private val links: LinkRepository,
    private val clock: Clock = Clock.System,
) {
    /** Предпросмотр ручного запуска (`POST /api/sync` с `dry_run = true`). Ничего не пишет. */
    suspend fun preview(): Preview {
        val run = prepare()
        val actions = run.plan.actions
        // Показываем всё, что требует решения: все ключи выбраны, «тихие» и служебные скрыты.
        val shown = select(actions, run.routes, actions.map { it.key })
            .filter { !it.silent && !it.kind.isBookkeeping }
            .map(PreviewAction::of)
        // Что выполнится по умолчанию — по маршрутам, с «тихими» действиями.
        val counts = LinkedHashMap<Route, RouteCounts>()
        for (a in select(actions, run.routes)) {
            if (a.kind.isBookkeeping) continue
            val route = Route(a.origin, a.target)
            counts[route] = (counts[route] ?: RouteCounts()).count(a.kind)
        }
        val byRoute = shown.groupBy { it.route }
        val groups = (byRoute.keys + counts.keys).distinct().sortedWith(ROUTE_ORDER).map { route ->
            RouteGroup(route, byRoute[route].orEmpty(), counts[route] ?: RouteCounts())
        }
        return Preview(
            window = run.window,
            actions = shown,
            routes = groups,
            defaultKeys = shown.filter { it.selected }.mapTo(LinkedHashSet()) { it.key },
            warnings = run.plan.warnings,
            systems = run.calendars.keys,
            skipped = run.skipped,
            dominoDisabledNow = run.dominoDisabledNow,
            prepared = run,
            dominoUnavailable = run.dominoUnavailable,
            refused = refusedInRoutes(run).map { PreviewAction.of(it, selected = false, remembersRefusal = false) },
        )
    }

    /**
     * Снять отказ от копирования (`undo_refusal`): [key] — ключ строки из [Preview.refused].
     * Копия не создаётся сейчас, а предлагается (с галочкой) в следующем предпросмотре и
     * создаётся следующим фоновым запуском. Предпросмотр [preview] после этого устарел: постройте
     * новый. Возвращает `false`, если такого отказа в предпросмотре нет.
     */
    fun undoRefusal(preview: Preview, key: String): Boolean {
        val run = preview.prepared
        val action = run.plan.refused.firstOrNull { it.key == key } ?: return false
        undoRefusal(checkNotNull(action.link), links.store(run.googleCalendar))
        return true
    }

    private fun refusedInRoutes(run: PreparedRun): List<Action> =
        run.plan.refused.filter { run.routes == null || (it.origin to it.target) in run.routes }

    /**
     * Выполнить предпросмотр (`POST /api/sync` с `keys`): ровно отмеченные [keys], «тихие» и
     * служебные действия — всегда; для снятых вопросов и созданий копий выполняется их fallback
     * (запомнить отказ связью `kept`/`distinct` или отказом от копирования, скопировать
     * недвойников). Ключ из [Preview.refused] в [keys] создаёт копию: связь копии заменяет отказ.
     *
     * В отличие от веб-версии план не строится заново: выполняется тот, что показан в [preview],
     * поэтому отказ запоминается только по вопросам, которые пользователь видел. Предпросмотр
     * выполняется один раз; повтор — [IllegalStateException].
     *
     * Ответы на «правку встречи с участниками» запоминаются ([SyncSettings.attendeeAnswers], ADR 0004).
     */
    suspend fun applySelected(preview: Preview, keys: Collection<String>): RunReport {
        check(!preview.applied) { "Этот предпросмотр уже выполнен: постройте новый" }
        preview.applied = true
        val run = preview.prepared
        val chosen = keys.toSet()
        val report = execute(run, select(run.plan.actions, run.routes, chosen, run.plan.refused))
        val asked = preview.actions.filter { it.kind == ActionKind.UPDATE && it.reason == REASON_ATTENDEES }
        if (asked.isNotEmpty()) {
            settings.update { s ->
                s.copy(attendeeAnswers = s.attendeeAnswers + asked.associate { it.key to AttendeeAnswer(it.title, it.key in chosen) })
            }
        }
        return report
    }

    /**
     * Фоновая синхронизация: collect + plan, затем только действия, отмеченные по умолчанию и не
     * являющиеся вопросами. Отказы не запоминаются (fallback не выполняется, ключей нет) и не
     * отменяются (копий из [Plan.refused] фон не создаёт), вопросы остаются до ручного запуска;
     * их число — в [BackgroundReport.openQuestions].
     */
    suspend fun runBackground(): BackgroundReport {
        val run = prepare()
        val actions = run.plan.actions
        val chosen = select(actions, run.routes).filter { !it.isQuestion() }
        val questions = select(actions, run.routes, actions.map { it.key })
            .count { !it.silent && !it.kind.isBookkeeping && it.isQuestion() }
        return BackgroundReport(execute(run, chosen), questions)
    }

    /** `GET /api/google/switch-preview`: сколько наших копий в текущем календаре Google. */
    suspend fun googleSwitchPreview(calendarId: String): GoogleSwitchPreview {
        val current = settings.load().googleCalendarId
        val old = if (calendarId == current) 0 else links.googleCopies(current).size
        return GoogleSwitchPreview(current, old)
    }

    /**
     * `POST /api/google/switch`: сменить календарь Google. Связи привязаны к календарю, поэтому
     * копии в прежнем календаре либо удаляются ([deleteOld]; в новом они создадутся заново при
     * следующем запуске), либо остаются как есть вместе со своими связями. Копия, которую не
     * удалось удалить, остаётся со связью и попадает в ошибки.
     *
     * @throws SyncSetupException копии нужно удалить, а входа в Google нет; календарь не меняется.
     */
    suspend fun switchGoogleCalendar(calendarId: String, deleteOld: Boolean): GoogleSwitchResult {
        val current = settings.load()
        var deleted = 0
        val errors = mutableListOf<String>()
        if (deleteOld && calendarId != current.googleCalendarId) {
            val copies = links.googleCopies(current.googleCalendarId)
            if (copies.isNotEmpty()) {
                val google = calendars.open(current, setOf(System.GOOGLE)).calendars[System.GOOGLE]
                    ?: throw SyncSetupException("Требуется вход в Google")
                val store = links.store(current.googleCalendarId)
                for (link in copies) {
                    val targetId = checkNotNull(link.targetId)
                    try {
                        google.delete(targetId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors += "$targetId: ${e.message ?: e.toString()}"
                        continue
                    }
                    store.drop(link)
                    deleted += 1
                }
            }
        }
        settings.update { it.copy(googleCalendarId = calendarId) }
        return GoogleSwitchResult(deleted, errors)
    }

    /**
     * `GET /api/google/calendars`: календари аккаунта Google и текущий из настроек.
     *
     * @throws SyncSetupException нет входа в Google.
     */
    suspend fun googleCalendars(): GoogleCalendars {
        val current = settings.load()
        return GoogleCalendars(current.googleCalendarId, googleDirectory(current).listCalendars())
    }

    /**
     * `POST /api/google/calendars`: создать дополнительный календарь (владелец — пользователь,
     * часовой пояс основного). Пустое название — «Notes», как в веб-версии. Календарь
     * синхронизации не меняется: выбор подтверждают через [switchGoogleCalendar].
     *
     * @throws SyncSetupException нет входа в Google.
     */
    suspend fun createGoogleCalendar(summary: String): GoogleCalendarInfo =
        googleDirectory(settings.load()).createCalendar(summary.trim().ifEmpty { DEFAULT_NEW_CALENDAR })

    private suspend fun googleDirectory(s: SyncSettings): GoogleCalendarDirectory =
        calendars.open(s, setOf(System.GOOGLE)).calendars[System.GOOGLE] as? GoogleCalendarDirectory
            ?: throw SyncSetupException("Требуется вход в Google")

    // --- общий запуск ---

    private suspend fun prepare(): PreparedRun {
        val s = settings.load()
        val now = clock.now()
        val window = Window(now - s.daysBack.days, now + s.daysForward.days)
        val opened = calendars.open(s, s.activeSystems())
        val guarded = LinkedHashMap<System, Calendar>()
        for ((system, calendar) in opened.calendars) {
            guarded[system] = if (system == System.DOMINO) DominoGuard(calendar) else calendar
        }
        val skipped = opened.skipped.toMutableList()
        val known = links.load(s.googleCalendarId)

        var collected = collectOrNull(guarded, window, known)
        var disabledNow = false
        var unavailable: String? = null
        val guard = guarded.dominoGuard()
        if (guard?.tripped == true) {
            // 401 от Domino: Lotus выключаем, читаем остальные заново (без Domino).
            disableDomino()
            disabledNow = true
            guarded.remove(System.DOMINO)
            skipped += SkippedSystem(System.DOMINO, SkipReason.DOMINO_DISABLED)
            collected = collect(guarded, window, known)
        } else if (guard?.outage != null) {
            // Domino не ответил: только в этом запуске без Lotus, остальные читаем заново.
            unavailable = guard.outageText()
            guarded.remove(System.DOMINO)
            collected = collect(guarded, window, known)
        } else if (guard != null) {
            settings.update { it.copy(lastDominoSuccess = now) }
        }
        // Lotus, выключенный после 401 (сейчас или раньше) или не ответивший, — недоступен, а не
        // исключён направлением: всё, что с ним связано, в этом запуске заморожено.
        val down = buildSet {
            if (unavailable != null) add(System.DOMINO)
            skipped.filter { it.reason == SkipReason.DOMINO_DISABLED }.forEach { add(it.system) }
        }
        val result = plan(
            checkNotNull(collected).snapshot, DIRECTIONS.getValue(s.direction), known, collected.found, down,
        ).withAttendeeAnswers(s.attendeeAnswers)
        return PreparedRun(
            window = window,
            routes = s.routes?.map { it.pair },
            calendars = guarded,
            skipped = skipped.sortedBy { it.system.ordinal },
            plan = result,
            googleCalendar = s.googleCalendarId,
            dominoDisabledNow = disabledNow,
            dominoUnavailable = unavailable,
        )
    }

    private suspend fun collectOrNull(calendars: Map<System, Calendar>, window: Window, known: List<Link>): Collected? =
        try {
            collect(calendars, window, known)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ошибку Domino разбирает prepare; ошибку другой системы — наверх.
            val guard = calendars.dominoGuard()
            if (guard == null || (!guard.tripped && guard.outage == null)) throw e
            null
        }

    private suspend fun execute(run: PreparedRun, chosen: List<Action>): RunReport {
        val trippedBefore = run.calendars.tripped()
        val report = apply(chosen, run.calendars, links.store(run.googleCalendar))
        var disabledNow = run.dominoDisabledNow
        if (!trippedBefore && run.calendars.tripped()) {
            // Пароль сменили между предпросмотром и запуском: остальные действия уже выполнены.
            disableDomino()
            disabledNow = true
        }
        val routes = LinkedHashMap<Route, RouteResult>()
        for ((pair, r) in report.pairs) {
            routes[Route(pair.first, pair.second)] =
                RouteResult(RouteCounts(r.created, r.updated, r.deleted, r.linked), r.errors.toList())
        }
        val unavailable = run.dominoUnavailable ?: run.calendars.dominoGuard()?.outageText()
        return RunReport(routes, run.skipped, disabledNow, run.plan.warnings, unavailable)
    }

    private suspend fun disableDomino() {
        settings.update { it.copy(dominoDisabled = true) }
    }

    private fun Map<System, Calendar>.tripped(): Boolean = dominoGuard()?.tripped == true

    private fun Map<System, Calendar>.dominoGuard(): DominoGuard? = this[System.DOMINO] as? DominoGuard

    private companion object {
        val ROUTE_ORDER: Comparator<Route> = compareBy({ it.origin.ordinal }, { it.target.ordinal })
        const val DEFAULT_NEW_CALENDAR = "Notes"
    }
}

/**
 * Сохранить настройки Domino (`POST /api/config/domino`): пустой [password] — оставить
 * сохранённый. Ввод пароля снимает выключение Lotus после 401.
 */
suspend fun saveDominoCredentials(
    settings: SettingsStore,
    secrets: SecretStore,
    url: String,
    user: String,
    password: String?,
) {
    val newPassword = password?.takeIf { it.isNotEmpty() }
    if (newPassword != null) secrets.setDominoPassword(newPassword)
    settings.update {
        it.copy(dominoUrl = url.trim(), dominoUser = user.trim(), dominoDisabled = it.dominoDisabled && newPassword == null)
    }
}

/** Всё, что нужно, чтобы выполнить план: календари, маршруты, план и куда писать связи. */
/**
 * Подставить запомненные ответы на «правку встречи с участниками» (ADR 0004): «да» — правка
 * выполняется без вопроса, «нет» — правки в этом запуске нет. Ответ действует, пока название
 * встречи то же, что при ответе, и правка его не меняет; иначе вопрос задаётся снова.
 */
internal fun Plan.withAttendeeAnswers(answers: Map<String, AttendeeAnswer>): Plan {
    if (answers.isEmpty()) return this
    return copy(
        actions = actions.mapNotNull { a ->
            val answer = answers[a.key]
            when {
                a.kind != ActionKind.UPDATE || a.reason != REASON_ATTENDEES || answer == null -> a
                answer.title != a.event.title || "title" in a.changed -> a
                answer.allow -> a.copy(selected = true)
                else -> null
            }
        },
    )
}

internal class PreparedRun(
    val window: Window,
    val routes: List<Pair<System, System>>?,
    val calendars: Map<System, Calendar>,
    val skipped: List<SkippedSystem>,
    val plan: Plan,
    val googleCalendar: String,
    val dominoDisabledNow: Boolean,
    val dominoUnavailable: String? = null,
)

/**
 * Обёртка календаря Domino: запоминает, что сервер ответил 401 ([DominoAuthException]) или не
 * ответил вовсе ([outage], см. [isDominoOutage]), в том числе там, где движок глотает ошибки (`get` в
 * `collect`, действия в `apply`). После сбоя связи к Domino больше не обращается: каждый вызов —
 * [DominoUnavailableException].
 */
private class DominoGuard(private val inner: Calendar) : Calendar {
    override val system: System get() = inner.system
    var tripped: Boolean = false
        private set

    /** Первая ошибка связи с Domino в этом запуске. */
    var outage: Exception? = null
        private set

    fun outageText(): String? = outage?.let { it.message ?: it.toString() }

    private inline fun <T> watch(listing: Boolean = false, block: () -> T): T {
        outage?.let { throw DominoUnavailableException(it) }
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DominoAuthException) {
            tripped = true
            throw e
        } catch (e: Exception) {
            // Без списка Domino в запуске участвовать не может, какой бы ни была ошибка.
            if (listing || isDominoOutage(e)) outage = e
            throw e
        }
    }

    override suspend fun list(window: Window): List<CalendarEvent> = watch(listing = true) { inner.list(window) }
    override suspend fun create(event: CalendarEvent): String = watch { inner.create(event) }
    override suspend fun get(eventId: String): CalendarEvent? = watch { inner.get(eventId) }
    override suspend fun update(eventId: String, event: CalendarEvent) = watch { inner.update(eventId, event) }
    override suspend fun delete(eventId: String) = watch { inner.delete(eventId) }
}

/** Domino в этом запуске уже не ответил: к нему не обращаемся. */
private class DominoUnavailableException(cause: Exception) :
    Exception("Lotus недоступен в этом запуске: ${cause.message ?: cause}", cause)

/**
 * Ошибка значит «Domino недоступен», а не «плохо это событие»: сеть, таймаут, ввод-вывод, ответ
 * 5xx, 408 или 429, непонятный ответ. Не сбой: 401 ([DominoAuthException] — у него своя защита),
 * прочие 4xx и ошибки разбора одного события ([DominoException]) — они остаются ошибками действия.
 */
internal fun isDominoOutage(e: Exception): Boolean = when (e) {
    is DominoAuthException -> false
    is DominoHttpException -> e.status !in 400..499 || e.status == 408 || e.status == 429
    is DominoException -> false
    is CancellationException -> false
    else -> true
}

/** Через сколько без связи с Domino фоновый обработчик сообщает, что Lotus недоступен. */
val DOMINO_UNREACHABLE_NOTIFY_AFTER: Duration = 24.hours

/**
 * Сообщить ли пользователю, что Lotus недоступен: в запуске [report] Domino не ответил
 * ([RunReport.dominoUnavailable]), а последний удачный обмен ([SyncSettings.lastDominoSuccess],
 * настройки читать после запуска) был больше [after] назад. Если Domino ещё ни разу не работал,
 * сравнивать не с чем — `false` (ошибку видно в ручном запуске и в проверке подключения).
 * 401 сюда не относится: о нём говорит [RunReport.dominoDisabledNow].
 */
fun shouldNotifyDominoUnreachable(
    report: RunReport,
    settings: SyncSettings,
    now: Instant,
    after: Duration = DOMINO_UNREACHABLE_NOTIFY_AFTER,
): Boolean {
    if (report.dominoUnavailable == null) return false
    val last = settings.lastDominoSuccess ?: return false
    return now - last > after
}
