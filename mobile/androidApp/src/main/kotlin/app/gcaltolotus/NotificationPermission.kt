package app.gcaltolotus

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.gcaltolotus.background.SyncNotifications
import kotlinx.coroutines.CompletableDeferred

/**
 * Запрос POST_NOTIFICATIONS на Android 13+. Создавать полем Activity (регистрирует
 * ActivityResultLauncher), вызывать [requestIfNeeded] из onCreate. Отказ не мешает работе:
 * фоновая синхронизация идёт, только без уведомлений.
 *
 * После двух отказов система диалог больше не показывает; тогда [canRequest] — `false`, и
 * уведомления включаются только в системных настройках. Был ли запрос, помнится в
 * SharedPreferences: до первого запроса `shouldShowRequestPermissionRationale` тоже `false`.
 */
class NotificationPermission(private val activity: ComponentActivity) {
    private var pending: CompletableDeferred<Unit>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.complete(Unit)
        pending = null
    }
    private val prefs by lazy { activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Разрешение есть (до Android 13 его не нужно). */
    fun granted(): Boolean = SyncNotifications(activity).canPost()

    /** Системный диалог ещё можно показать. */
    fun canRequest(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted()) return false
        return !prefs.getBoolean(KEY_ASKED, false) ||
            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun requestIfNeeded() {
        if (!canRequest()) return
        launch()
    }

    /** Показать диалог и дождаться ответа; если диалог уже показан — дождаться его ответа. */
    suspend fun request() {
        val waiting = pending ?: run {
            if (!canRequest()) return
            launch()
            pending
        }
        waiting?.await()
    }

    private fun launch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        if (pending == null) pending = CompletableDeferred()
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val PREFS = "permissions"
        const val KEY_ASKED = "notifications_asked"
    }
}
