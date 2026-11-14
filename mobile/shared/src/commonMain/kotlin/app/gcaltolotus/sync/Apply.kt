package app.gcaltolotus.sync

import kotlin.coroutines.cancellation.CancellationException

/** Куда [apply] сохраняет связи сразу после каждого действия. */
interface LinkStore {
    /** Сохранить связь, заменив связь с тем же [Link.key]. */
    fun save(link: Link)

    /** Удалить связь с тем же [Link.key]; нет такой — не ошибка. */
    fun drop(link: Link)
}

/** Счётчики одного маршрута (origin → target). [linked] считает только вопросы `twin`. */
data class PairResult(
    var created: Int = 0,
    var updated: Int = 0,
    var deleted: Int = 0,
    var linked: Int = 0,
    val errors: MutableList<String> = mutableListOf(),
)

/** Отчёт [apply] по маршрутам `(origin, target)` в порядке первого действия маршрута. */
class SyncReport {
    private val byRoute = LinkedHashMap<Pair<System, System>, PairResult>()

    val pairs: Map<Pair<System, System>, PairResult> get() = byRoute

    fun pair(origin: System, target: System): PairResult = byRoute.getOrPut(origin to target) { PairResult() }
}

/**
 * Выполнить действия по порядку. Ошибка одного действия не останавливает остальные:
 * она попадает в [PairResult.errors] как `"<название события>: <сообщение>"`.
 * Действие, у которого не выполнено что-то из [Action.dependsOn], пропускается и не считается.
 * Связи сохраняются в [store] сразу после каждого действия.
 */
suspend fun apply(actions: Iterable<Action>, calendars: Map<System, Calendar>, store: LinkStore): SyncReport {
    val report = SyncReport()
    val done = mutableSetOf<String>()
    for (action in actions) {
        if (action.dependsOn.any { it !in done }) continue // не выполнено предыдущее действие
        val result = report.pair(action.origin, action.target)
        try {
            execute(action, calendars, store)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result.errors += "${action.event.title}: ${e.message ?: e.toString()}"
            continue
        }
        done += action.key
        when (action.kind) {
            ActionKind.CREATE -> result.created += 1
            ActionKind.UPDATE -> result.updated += 1
            ActionKind.DELETE -> result.deleted += 1
            ActionKind.TWIN -> result.linked += 1
            ActionKind.LINK, ActionKind.UNLINK -> Unit
        }
    }
    return report
}

private suspend fun execute(action: Action, calendars: Map<System, Calendar>, store: LinkStore) {
    fun calendar(): Calendar = checkNotNull(calendars[action.target]) { "Нет календаря ${action.target}" }
    when (action.kind) {
        ActionKind.CREATE -> {
            val targetId = calendar().create(action.event)
            val originId = action.event.id
            if (originId != null) {
                val snap = action.event.fields()
                store.save(Link(action.origin, originId, action.target, targetId, sourceFields = snap, targetFields = snap))
            }
        }
        ActionKind.UPDATE -> calendar().update(checkNotNull(action.event.id), action.event)
        ActionKind.DELETE -> {
            calendar().delete(checkNotNull(action.event.id))
            action.link?.let(store::drop)
        }
        ActionKind.TWIN, ActionKind.LINK -> store.save(checkNotNull(action.link))
        ActionKind.UNLINK -> store.drop(checkNotNull(action.link))
    }
}
