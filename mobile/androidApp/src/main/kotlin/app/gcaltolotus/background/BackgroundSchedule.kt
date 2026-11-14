package app.gcaltolotus.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.time.Instant
import kotlinx.coroutines.flow.first

/** Имя единственной периодической задачи синхронизации в WorkManager. */
const val BACKGROUND_WORK_NAME: String = "background-sync"

/**
 * Пауза перед повтором, если фоновый запуск совпал с ручным (блокировка занята,
 * [SyncWorker] вернул retry); линейно растёт с числом попыток.
 */
const val BACKGROUND_RETRY_MINUTES: Long = 1

/**
 * Периодическая задача для периода [intervalMinutes] из настроек; `null` — фон выключен
 * (0 или отрицательное). WorkManager не запускает задачи чаще раза в 15 минут, поэтому
 * меньшие значения поднимаются до 15. Запуск — только при подключённой сети.
 *
 * Периодическая задача WorkManager по умолчанию запускается сразу после постановки в очередь;
 * начальная задержка, равная периоду, сдвигает первый запуск на один период (ADR 0003).
 * Повтор при `Result.retry` — через [BACKGROUND_RETRY_MINUTES] (линейно).
 */
fun backgroundWorkRequest(intervalMinutes: Int): PeriodicWorkRequest? {
    if (intervalMinutes <= 0) return null
    val minutes = maxOf(intervalMinutes.toLong(), PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS / 60_000)
    return PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
        .setInitialDelay(minutes, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.LINEAR, BACKGROUND_RETRY_MINUTES, TimeUnit.MINUTES)
        .addTag(BACKGROUND_WORK_NAME)
        .build()
}

/**
 * Запланировать фоновую синхронизацию по периоду из настроек или снять её. Повторный вызов
 * безопасен: UPDATE обновляет существующую задачу, сохраняя время постановки и число прошедших
 * периодов, поэтому перепланирование не вызывает немедленного запуска. Новая задача (после
 * выключения или на новой установке) впервые запускается через период.
 */
fun scheduleBackgroundSync(context: Context, intervalMinutes: Int) {
    val workManager = WorkManager.getInstance(context)
    val request = backgroundWorkRequest(intervalMinutes)
    if (request == null) {
        workManager.cancelUniqueWork(BACKGROUND_WORK_NAME)
    } else {
        workManager.enqueueUniquePeriodicWork(BACKGROUND_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

/** Когда WorkManager запустит фоновую синхронизацию; `null` — задачи нет или время неизвестно. */
suspend fun nextBackgroundRun(context: Context): Instant? {
    val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(BACKGROUND_WORK_NAME).first()
    val next = infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.nextScheduleTimeMillis ?: return null
    return if (next == Long.MAX_VALUE) null else Instant.fromEpochMilliseconds(next)
}
