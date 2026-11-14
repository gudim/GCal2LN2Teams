package app.gcaltolotus.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.gcaltolotus.AppGraph
import app.gcaltolotus.app.BackgroundReport
import app.gcaltolotus.app.SyncSetupException
import app.gcaltolotus.auth.isGoogleSignInRequired
import kotlin.coroutines.cancellation.CancellationException

/**
 * Фоновая синхронизация (GLOSSARY.md): [app.gcaltolotus.app.SyncService.runBackground] —
 * всё, кроме вопросов. Если включено в настройках — тихий итог запуска
 * ([SyncNotifications.showSummary]).
 *
 * Запуск идёт через [AppGraph.syncRuns]: если ручной запуск как раз строит или применяет
 * предпросмотр, фон ждёт его окончания и сразу выполняется; итог и уведомления о вопросах, Lotus
 * и входе в Google — там же, общие с ручным запуском. Ошибки не повторяются немедленно:
 * следующая попытка — в следующий период.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return sync(AppGraph.get(applicationContext))
    }

    private suspend fun sync(graph: AppGraph): Result {
        val notifications = graph.notifications
        return try {
            val report = graph.syncRuns.background()
            log(report)
            summary(graph) { backgroundSummary(report, notifications.summaryWords()) }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isGoogleSignInRequired()) {
                Log.w(TAG, "Фоновая синхронизация: нужен вход в Google") // уведомление — в SyncRuns.afterRun
                Result.success()
            } else if (e is SyncSetupException) {
                Log.w(TAG, "Фоновая синхронизация невозможна: ${e.message}")
                summary(graph) { failedSummary(e, notifications.summaryWords()) }
                Result.success()
            } else {
                Log.e(TAG, "Фоновая синхронизация не удалась", e)
                summary(graph) { failedSummary(e, notifications.summaryWords()) }
                Result.failure()
            }
        }
    }

    /** Итог запуска, если пользователь включил его в настройках; ошибка не роняет запуск. */
    private suspend fun summary(graph: AppGraph, text: () -> String) {
        try {
            if (graph.settings.load().notifyBackgroundSummary) graph.notifications.showSummary(text())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось показать итог запуска", e)
        }
    }

    private fun log(report: BackgroundReport) {
        val run = report.run
        Log.i(TAG, "Фоновая синхронизация: ${run.totals}, вопросов ${report.openQuestions}, ошибок ${run.errors.size}")
        run.errors.forEach { Log.w(TAG, it) }
    }

    private companion object {
        const val TAG = "SyncWorker"
    }
}
