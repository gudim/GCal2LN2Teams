package app.gcaltolotus.background

import app.gcaltolotus.ui.OpenRequest
import app.gcaltolotus.ui.Screen

/**
 * Куда открыть приложение по [SyncNotifications.EXTRA_DESTINATION] из уведомления: вопросы —
 * синхронизация с предпросмотром, Lotus и Google — подключения, итог — состояние. Неизвестное
 * значение — `null`.
 */
fun openRequestFor(destination: String?): OpenRequest? = when (destination) {
    SyncNotifications.DESTINATION_QUESTIONS -> OpenRequest(Screen.SYNC, preview = true)
    SyncNotifications.DESTINATION_DOMINO, SyncNotifications.DESTINATION_GOOGLE -> OpenRequest(Screen.CONNECTIONS)
    SyncNotifications.DESTINATION_STATUS -> OpenRequest(Screen.STATUS)
    else -> null
}
