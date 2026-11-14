package app.gcaltolotus.platform

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.gcaltolotus.NotificationPermission
import app.gcaltolotus.background.SyncChannel
import app.gcaltolotus.background.SyncNotifications
import app.gcaltolotus.ui.SystemControls
import app.gcaltolotus.ui.SystemStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * [SystemControls] на Android: оптимизация батареи, ограничение фона, уведомления и их каналы.
 * [resumes] срабатывает при каждом возврате на экран [activity] (наблюдение за её жизненным
 * циклом): экран настроек перечитывает состояние после возврата из системных настроек.
 */
class AndroidSystemControls(
    private val activity: ComponentActivity,
    private val permission: NotificationPermission,
) : SystemControls {
    override val isSamsung: Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    private val resumed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val resumes: Flow<Unit> get() = resumed

    init {
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumed.tryEmit(Unit) },
        )
    }

    private val packageName: String get() = activity.packageName

    private fun ignoringBatteryOptimizations(): Boolean =
        activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun backgroundRestricted(): Boolean =
        activity.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    override suspend fun status(): SystemStatus = SystemStatus(
        backgroundUnrestricted = ignoringBatteryOptimizations(),
        backgroundRestricted = backgroundRestricted(),
        notificationsEnabled = NotificationManagerCompat.from(activity).areNotificationsEnabled() && permission.granted(),
        canRequestNotifications = permission.canRequest(),
        summaryChannelEnabled = !SyncNotifications(activity).summaryChannelBlocked(),
    )

    /**
     * Фон запрещён в настройках батареи — экран приложения (на Samsung там «Батарея → Без
     * ограничений»). Иначе, пока оптимизация включена, — системный запрос её снять, затем
     * список оптимизации, затем экран приложения. Оптимизация уже снята — экран приложения.
     */
    @SuppressLint("BatteryLife") // приложение распространяется внутри компании, не через Play
    override fun openBackgroundSettings() {
        val details = appDetails()
        if (backgroundRestricted() || ignoringBatteryOptimizations()) {
            start(details)
        } else {
            start(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                details,
            )
        }
    }

    override fun openNotificationSettings() {
        start(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            appDetails(),
        )
    }

    override fun openSummaryChannelSettings() {
        // Канал должен существовать, иначе система откроет пустой экран.
        SyncNotifications(activity).ensureChannels()
        start(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, SyncChannel.SUMMARY.id),
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            appDetails(),
        )
    }

    override suspend fun requestNotificationPermission() = permission.request()

    private fun appDetails(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    /** Открыть первый экран, который есть на этом телефоне. */
    private fun start(vararg intents: Intent) {
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Log.i(TAG, "Нет экрана ${intent.action}, пробуем следующий")
            } catch (e: SecurityException) {
                Log.i(TAG, "Экран ${intent.action} недоступен: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "SystemControls"
    }
}
