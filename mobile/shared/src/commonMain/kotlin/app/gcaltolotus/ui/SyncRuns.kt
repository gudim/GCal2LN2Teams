package app.gcaltolotus.ui

import app.gcaltolotus.app.BackgroundReport
import app.gcaltolotus.app.Preview
import app.gcaltolotus.app.RunReport
import app.gcaltolotus.app.SyncService
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Запуски синхронизации (GLOSSARY.md): ручной (построить предпросмотр, применить) и фоновый.
 * Один процесс — один экземпляр, общий для экранов и фонового обработчика.
 *
 * Правила, которые здесь держатся:
 * - Одновременно идёт только один запуск. У фона приоритет: пока он идёт, ручной шаг
 *   пропускается (возвращает `null`, [backgroundRunning] = true); фон, пришедший во время
 *   ручного шага, ждёт его окончания и сразу выполняется.
 * - Открытый на экране предпросмотр блокировку не держит. Каждый [Built] помнит номер
 *   законченных запусков; если к «Применить» закончился ещё один (фоновый, удачный или нет),
 *   план не применяется, а строится заново ([Applied.Stale]). Номер живёт в памяти и от записи
 *   истории не зависит.
 * - Перед каждым запуском вызывается [beforeNetwork] (на Android — сброс простаивающих
 *   соединений, см. `HttpConnections`).
 * - Итог каждого запуска, в том числе неудачного, записывается в [history] и передаётся в
 *   [afterRun] (уведомления, см. [noticeChanges]); сбой любого из них запуск не роняет.
 */
class SyncRuns(
    private val sync: SyncService,
    private val history: RunHistory,
    private val clock: Clock = Clock.System,
    private val beforeNetwork: suspend () -> Unit = {},
    private val afterRun: suspend (RunOutcome) -> Unit = {},
) {
    private val lock = Mutex()
    private val running = MutableStateFlow(false)

    /** Идёт фоновый запуск: ручные шаги сейчас пропускаются. */
    val backgroundRunning: StateFlow<Boolean> = running.asStateFlow()

    /** Сколько запусков закончилось; меняется только под [lock]. */
    private var finished = 0L

    /** Предпросмотр и номер запусков, на котором он построен. */
    class Built(val preview: Preview, internal val generation: Long)

    /** Итог «Применить». */
    sealed interface Applied {
        data class Done(val report: RunReport) : Applied

        /** Пока предпросмотр был открыт, закончился другой запуск: вот новый, ничего не применено. */
        class Stale(val rebuilt: Built) : Applied
    }

    /**
     * Итог ручного запуска с автоприменением (ADR 0004): [report] — выполненное без вопросов;
     * [questions] — предпросмотр, построенный после него, если остались вопросы.
     */
    class AutoRun(val report: RunReport, val questions: Built?)

    /**
     * Фоновый запуск: ждёт окончания ручного шага, если тот идёт. Ошибка записывается в историю
     * и пробрасывается (уведомления по ней решает вызывающий).
     */
    suspend fun background(): BackgroundReport = lock.withLock {
        running.value = true
        try {
            beforeNetwork()
            val report = sync.runBackground()
            record(LastRun.background(report, clock.now()), RunOutcome.Finished(report.run, report.openQuestions, background = true))
            report
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            record(LastRun.failed(e, clock.now(), background = true), RunOutcome.Failed(e, background = true))
            throw e
        } finally {
            finished++
            running.value = false
        }
    }

    /**
     * Ручной запуск с автоприменением: выполнить всё, кроме вопросов (как фон), и, если вопросы
     * остались, построить предпросмотр заново — в нём только они и то, что от них зависит.
     * `null` — идёт фоновый запуск.
     */
    suspend fun autoRun(): AutoRun? = manual {
        val report = try {
            sync.runBackground().also {
                record(LastRun.manual(it.run, clock.now(), it.openQuestions), RunOutcome.Finished(it.run, it.openQuestions, background = false))
            }
        } finally {
            finished++
        }
        AutoRun(report.run, if (report.openQuestions > 0) Built(sync.preview(), finished) else null)
    }

    /** Построить предпросмотр; `null` — идёт фоновый запуск. */
    suspend fun preview(): Built? = manual { Built(sync.preview(), finished) }

    /** Снять отказ [key] и построить предпросмотр заново; `null` — идёт фоновый запуск. */
    suspend fun undoRefusal(old: Preview, key: String): Built? = manual {
        sync.undoRefusal(old, key)
        Built(sync.preview(), finished)
    }

    /** Применить отмеченные [selected] из [built]; `null` — идёт фоновый запуск. */
    suspend fun apply(built: Built, selected: Set<String>): Applied? = manual {
        if (built.generation != finished) return@manual Applied.Stale(Built(sync.preview(), finished))
        try {
            val report = sync.applySelected(built.preview, selected)
            val open = built.preview.openQuestionsAfter(selected)
            record(LastRun.manual(report, clock.now(), open), RunOutcome.Finished(report, open, background = false))
            Applied.Done(report)
        } finally {
            finished++
        }
    }

    private suspend fun <T> manual(block: suspend () -> T): T? {
        if (!lock.tryLock()) return null
        try {
            beforeNetwork()
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            record(LastRun.failed(e, clock.now(), background = false), RunOutcome.Failed(e, background = false))
            throw e
        } finally {
            lock.unlock()
        }
    }

    private suspend fun record(run: LastRun, outcome: RunOutcome) {
        // История — только для экрана состояния, уведомления — для пользователя; устаревание
        // предпросмотра ни от того, ни от другого не зависит.
        ignoringFailure { history.record(run) }
        ignoringFailure { afterRun(outcome) }
    }

    private suspend fun ignoringFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // см. record
        }
    }
}
