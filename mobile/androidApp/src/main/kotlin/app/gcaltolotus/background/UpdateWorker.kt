package app.gcaltolotus.background

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.gcaltolotus.AppGraph
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** Имя периодической задачи проверки обновлений в WorkManager. */
const val UPDATE_WORK_NAME: String = "update-check"

/**
 * Проверка новой версии ([app.gcaltolotus.app.UpdateCheck]). Задача запускается раз в день, а
 * в GitHub проверка ходит раз в неделю: так неудавшаяся проверка (нет сети) повторяется назавтра,
 * а не через неделю. О новой версии — одно уведомление.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = AppGraph.get(applicationContext)
        try {
            graph.updates.runIfDue()?.let(graph.notifications::showUpdate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Проверка обновлений не удалась", e)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "UpdateWorker"
    }
}

/** Запланировать ежедневный запуск [UpdateWorker] (повторный вызов ничего не меняет). */
fun scheduleUpdateCheck(context: Context) {
    val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(UPDATE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
}
