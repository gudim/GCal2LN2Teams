package app.gcaltolotus.ui

import app.gcaltolotus.app.Preview
import app.gcaltolotus.app.PreviewAction
import app.gcaltolotus.app.Route
import app.gcaltolotus.app.RunReport
import app.gcaltolotus.app.SettingsStore
import app.gcaltolotus.app.SyncSettings
import app.gcaltolotus.sync.System
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Этап ручной синхронизации: выбор маршрутов → предпросмотр → применение → отчёт. */
sealed interface SyncPhase {
    data object Idle : SyncPhase

    data object Loading : SyncPhase

    /**
     * Предпросмотр показан; [selected] — отмеченные ключи: действия из [Preview.actions] и отказы
     * из [Preview.refused], которые пользователь решил скопировать сейчас.
     *
     * @property built предпросмотр вместе с номером запусков ([SyncRuns]): если к «Применить»
     *   закончился другой запуск, предпросмотр устарел и строится заново.
     * @property rebuiltAfterRun предпросмотр построен заново вместо применения: пока он был
     *   открыт, прошла другая синхронизация (баннер на экране).
     * @property autoApplied уже выполнено автоприменением (ADR 0004); в предпросмотре — вопросы.
     */
    data class Ready(
        val built: SyncRuns.Built,
        val selected: Set<String>,
        val rebuiltAfterRun: Boolean = false,
        val autoApplied: RunReport? = null,
    ) : SyncPhase {
        val preview: Preview get() = built.preview

        /**
         * Действия с запоминаемым отказом, с которых снята галочка: при «Применить» запомнится
         * отказ — «не копировать» у создания копии, «нет» у вопроса.
         */
        val refusals: List<PreviewAction>
            get() = preview.actions.filter { it.remembersRefusal && it.key !in selected }

        /** Строки «Не копируется», отмеченные галочкой: копия создастся сейчас, отказ снимется. */
        val copyNow: List<PreviewAction>
            get() = preview.refused.filter { it.key in selected }

        /** Сколько действий предпросмотра отмечено (без строк «Не копируется»). */
        val selectedActions: Int
            get() = preview.actions.count { it.key in selected }
    }

    data class Applying(val preview: Preview, val selected: Set<String>) : SyncPhase

    data class Done(val report: RunReport) : SyncPhase

    /** Не удалось построить предпросмотр ([applying] = false) или применить его. */
    data class Failed(val message: String, val applying: Boolean) : SyncPhase
}

/** @property autoApply «Синхронизировать» применяет сразу и спрашивает только вопросы ([SyncSettings.autoApply]). */
data class SyncState(
    val routes: Set<Route> = ALL_ROUTES.toSet(),
    val phase: SyncPhase = SyncPhase.Idle,
    val autoApply: Boolean = false,
)

/** Все шесть маршрутов в каноническом порядке систем. */
val ALL_ROUTES: List<Route> = System.ORDER.flatMap { from ->
    System.ORDER.filter { it != from }.map { Route(from, it) }
}

/**
 * Ручная синхронизация (`/v2`: «Проверить изменения» → «Применить»).
 *
 * Маршруты хранятся в настройках как `direction = "all"` и набор [SyncSettings.routes]
 * (`null`, если выбраны все). Отмеченные по умолчанию действия берутся из предпросмотра.
 *
 * Запуски идут через [runs]: пока идёт фоновый, ручные шаги молча пропускаются (экран остаётся
 * как был, [backgroundRunning] показывает, что идёт автосинхронизация). Открытый предпросмотр
 * фон не задерживает; если к «Применить» прошёл другой запуск, предпросмотр строится заново с
 * прежними галочками ([SyncPhase.Ready.rebuiltAfterRun]).
 *
 * Первое успешное применение включает фоновую синхронизацию ([SyncSettings.afterManualApply],
 * ADR 0003): платформа планирует её по сохранённым настройкам.
 */
class SyncModel(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val runs: SyncRuns,
) {
    private val mutable = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = mutable.asStateFlow()

    /** Идёт фоновая синхронизация: нажатия «Предпросмотр» и «Применить» сейчас пропускаются. */
    val backgroundRunning: StateFlow<Boolean> = runs.backgroundRunning

    /** Прочитать выбранные маршруты и режим подтверждения из настроек. */
    fun load(): Job = scope.launch {
        val s = settings.load()
        mutable.update { it.copy(routes = s.activeRoutes().toSet(), autoApply = s.autoApply) }
    }

    fun setRoute(route: Route, enabled: Boolean): Job {
        val routes = if (enabled) mutable.value.routes + route else mutable.value.routes - route
        mutable.update { it.copy(routes = routes) }
        return scope.launch {
            settings.update {
                it.copy(direction = "all", routes = routes.takeIf { r -> r.size < ALL_ROUTES.size })
            }
        }
    }

    /**
     * Построить предпросмотр, а при автоприменении ([SyncSettings.autoApply], ADR 0004) — сразу
     * выполнить всё, кроме вопросов, и показать предпросмотр только с вопросами (или отчёт, если
     * их нет). [full] — всегда полный предпросмотр без применения (кнопка «Предпросмотр» в режиме
     * автоприменения: в нём же строки «Не копируется»). Пока идёт шаг, повторный вызов ничего не делает.
     */
    fun preview(full: Boolean = false): Job? {
        val before = mutable.value.phase
        if (before is SyncPhase.Loading || before is SyncPhase.Applying) return null
        mutable.update { it.copy(phase = SyncPhase.Loading) }
        return step(before, applying = false) {
            val auto = settings.load().autoApply
            mutable.update { it.copy(autoApply = auto) }
            if (auto && !full) {
                runs.autoRun()?.let { run ->
                    run.questions?.let { SyncPhase.Ready(it, it.preview.defaultKeys, autoApplied = run.report) }
                        ?: SyncPhase.Done(run.report)
                }
            } else {
                runs.preview()?.let { SyncPhase.Ready(it, it.preview.defaultKeys) }
            }
        }
    }

    fun toggle(key: String) = select { selected ->
        if (key in selected) selected - key else selected + key
    }

    /** Отметить или снять все действия маршрута. */
    fun setAll(route: Route, checked: Boolean) = select { selected ->
        val ready = mutable.value.phase as SyncPhase.Ready
        val keys = ready.preview.actions.filter { it.route == route }.map { it.key }
        if (checked) selected + keys else selected - keys.toSet()
    }

    private fun select(change: (Set<String>) -> Set<String>) {
        val ready = mutable.value.phase as? SyncPhase.Ready ?: return
        mutable.update { it.copy(phase = ready.copy(selected = change(ready.selected))) }
    }

    /**
     * Снять отказ от копирования [key] (строка «Не копируется») и построить предпросмотр заново:
     * копия снова предлагается с галочкой. Остальные галочки, поставленные или снятые
     * пользователем, переносятся в новый предпросмотр.
     */
    fun undoRefusal(key: String): Job? {
        val ready = mutable.value.phase as? SyncPhase.Ready ?: return null
        mutable.update { it.copy(phase = SyncPhase.Loading) }
        return step(ready, applying = false) {
            runs.undoRefusal(ready.preview, key)?.let { SyncPhase.Ready(it, carrySelection(ready, it.preview, key)) }
        }
    }

    /**
     * Применить отмеченные действия. У снятых вопросов с запоминаемым отказом «нет» запоминается.
     * Устаревший план не применяется: предпросмотр строится заново ([SyncRuns.Applied.Stale]).
     */
    fun apply(): Job? {
        val ready = mutable.value.phase as? SyncPhase.Ready ?: return null
        mutable.update { it.copy(phase = SyncPhase.Applying(ready.preview, ready.selected)) }
        return step(ready, applying = true) {
            when (val outcome = runs.apply(ready.built, ready.selected)) {
                null -> null
                is SyncRuns.Applied.Stale -> SyncPhase.Ready(
                    outcome.rebuilt, carrySelection(ready, outcome.rebuilt.preview),
                    rebuiltAfterRun = true, autoApplied = ready.autoApplied,
                )
                is SyncRuns.Applied.Done -> {
                    enableBackgroundAfterFirstApply()
                    SyncPhase.Done(ready.autoApplied?.plus(outcome.report) ?: outcome.report)
                }
            }
        }
    }

    /**
     * Выполнить шаг запуска; `null` от [block] — шаг пропущен (идёт фоновый запуск), экран
     * возвращается к [before].
     */
    private fun step(before: SyncPhase, applying: Boolean, block: suspend () -> SyncPhase?): Job = scope.launch {
        val next = try {
            block() ?: before
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncPhase.Failed(e.message ?: e.toString(), applying)
        }
        mutable.update { it.copy(phase = next) }
    }

    /** Вернуться к выбору маршрутов (после отчёта или ошибки). */
    fun reset() {
        val phase = mutable.value.phase
        if (phase is SyncPhase.Loading || phase is SyncPhase.Applying) return
        mutable.update { it.copy(phase = SyncPhase.Idle) }
    }

    /** Платформа планирует фон по сохранённым настройкам ([BackgroundScheduler]). */
    private suspend fun enableBackgroundAfterFirstApply() {
        settings.update { it.afterManualApply() }
    }
}

/**
 * Галочки для предпросмотра, построенного заново ([SyncModel.undoRefusal] или устаревший план в
 * [SyncModel.apply]): у строк, которые уже были в [old], — выбор пользователя, у новых (и у
 * возвращённой [undone]) — галочка по умолчанию.
 */
internal fun carrySelection(old: SyncPhase.Ready, preview: Preview, undone: String? = null): Set<String> {
    val known = (old.preview.actions + old.preview.refused).map { it.key }.filter { it != undone }.toSet()
    val present = (preview.actions + preview.refused).map { it.key }.toSet()
    return preview.defaultKeys.filter { it !in known }.toSet() +
        old.selected.filter { it in present && it != undone }
}
