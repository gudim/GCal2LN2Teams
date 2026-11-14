package app.gcaltolotus.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.gcaltolotus.MainActivity
import app.gcaltolotus.R
import app.gcaltolotus.app.AppRelease
import app.gcaltolotus.ui.NoticeChanges

/**
 * Уведомления фоновой синхронизации. Каналы и id — в [SyncNotification]. Касание открывает
 * приложение; в Intent лежит [EXTRA_DESTINATION] — куда UI стоит перейти (вопросы, пароль
 * Lotus, вход в Google, состояние).
 */
class SyncNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    /**
     * Создать каналы [SyncChannel] и удалить прежний общий канал [LEGACY_CHANNEL_ID]. Повторный
     * вызов обновляет только названия: важность, выбранную пользователем, система не трогает.
     */
    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        LEGACY_CHANNEL_IDS.forEach(system::deleteNotificationChannel)
        system.createNotificationChannels(
            SyncChannel.entries.map { channel ->
                NotificationChannel(channel.id, context.getString(channelName(channel)), channel.importance).apply {
                    description = context.getString(channelDescription(channel))
                    if (channel == SyncChannel.SUMMARY) setShowBadge(false)
                }
            },
        )
    }

    /** Канал итога выключен пользователем в системе (важность NONE). */
    fun summaryChannelBlocked(): Boolean =
        manager.getNotificationChannel(SyncChannel.SUMMARY.id)?.importance == NotificationManager.IMPORTANCE_NONE

    /** Итог фоновой синхронизации: тихо, одно уведомление, новое заменяет прежнее. */
    fun showSummary(text: String) = post(
        SyncNotification.SUMMARY,
        context.getString(R.string.notification_summary_title),
        text,
        DESTINATION_STATUS,
    )

    /** Переключатель итога выключен: убрать показанный итог. */
    fun clearSummary() = manager.cancel(SyncNotification.SUMMARY.id)

    /** Слова итога из ресурсов (язык системы). */
    fun summaryWords(): SummaryWords = SummaryWords(
        noChanges = context.getString(R.string.notification_summary_no_changes),
        lotusUnavailable = context.getString(R.string.notification_summary_lotus_unavailable),
        failed = { context.getString(R.string.notification_summary_failed, it) },
        part = { part, n -> context.getString(summaryPartString(part), n) },
    )

    /** Привести уведомления в соответствие с итогом запуска ([noticeChanges]). */
    fun apply(changes: NoticeChanges) {
        changes.questions?.let(::showOpenQuestions)
        when (changes.lotusDisabled) {
            true -> showLotusDisabled()
            false -> clearLotusDisabled()
            null -> Unit
        }
        when (changes.lotusUnreachable) {
            true -> showLotusUnreachable()
            false -> clearLotusUnreachable()
            null -> Unit
        }
        when (changes.googleSignIn) {
            true -> showGoogleSignInRequired()
            false -> clearGoogleSignInRequired()
            null -> Unit
        }
    }

    /** Открытые вопросы после запуска; 0 — убрать уведомление. */
    fun showOpenQuestions(count: Int) {
        if (count <= 0) {
            manager.cancel(SyncNotification.QUESTIONS.id)
            return
        }
        post(
            SyncNotification.QUESTIONS,
            context.resources.getQuantityString(R.plurals.notification_questions_title, count, count),
            context.getString(R.string.notification_questions_text),
            DESTINATION_QUESTIONS,
        )
    }

    /** Lotus выключен после 401: нужно снова ввести пароль. */
    fun showLotusDisabled() = post(
        SyncNotification.LOTUS_DISABLED,
        context.getString(R.string.notification_lotus_disabled_title),
        context.getString(R.string.notification_lotus_disabled_text),
        DESTINATION_DOMINO,
    )

    /** Пароль Lotus введён снова: уведомление о выключении больше не нужно. */
    fun clearLotusDisabled() = manager.cancel(SyncNotification.LOTUS_DISABLED.id)

    /** Lotus недоступен дольше суток. */
    fun showLotusUnreachable() = post(
        SyncNotification.LOTUS_UNREACHABLE,
        context.getString(R.string.notification_lotus_unreachable_title),
        context.getString(R.string.notification_lotus_unreachable_text),
        DESTINATION_DOMINO,
    )

    fun clearLotusUnreachable() = manager.cancel(SyncNotification.LOTUS_UNREACHABLE.id)

    /** Google требует входа с участием пользователя (согласие отозвано, сменился пароль). */
    fun showGoogleSignInRequired() = post(
        SyncNotification.GOOGLE_SIGN_IN,
        context.getString(R.string.notification_google_sign_in_title),
        context.getString(R.string.notification_google_sign_in_text),
        DESTINATION_GOOGLE,
    )

    fun clearGoogleSignInRequired() = manager.cancel(SyncNotification.GOOGLE_SIGN_IN.id)

    /** Вышла новая версия: касание открывает страницу выпуска с APK в браузере. */
    fun showUpdate(release: AppRelease) = post(
        SyncNotification.UPDATE,
        context.getString(R.string.notification_update_title, release.version),
        context.getString(R.string.notification_update_text),
        Intent(Intent.ACTION_VIEW, release.pageUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun post(kind: SyncNotification, title: String, text: String, destination: String) = post(
        kind,
        title,
        text,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_DESTINATION, destination),
    )

    private fun post(kind: SyncNotification, title: String, text: String, intent: Intent) {
        if (!canPost()) return
        ensureChannels()
        val id = kind.id
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val silent = kind.channel == SyncChannel.SUMMARY
        val notification = NotificationCompat.Builder(context, kind.channel.id)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Разрешение отозвали между проверкой и показом: уведомление просто не показывается.
        }
    }

    /** На Android 13+ без разрешения POST_NOTIFICATIONS уведомления не показываются. */
    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        /** Куда перейти в приложении после касания уведомления. */
        const val EXTRA_DESTINATION = "app.gcaltolotus.extra.DESTINATION"
        const val DESTINATION_QUESTIONS = "questions"
        const val DESTINATION_DOMINO = "domino"
        const val DESTINATION_GOOGLE = "google"
        const val DESTINATION_STATUS = "status"

        private fun channelName(channel: SyncChannel): Int = when (channel) {
            SyncChannel.QUESTIONS -> R.string.notification_channel_questions
            SyncChannel.CONNECTION -> R.string.notification_channel_connection
            SyncChannel.SUMMARY -> R.string.notification_channel_summary
            SyncChannel.UPDATES -> R.string.notification_channel_updates
        }

        private fun channelDescription(channel: SyncChannel): Int = when (channel) {
            SyncChannel.QUESTIONS -> R.string.notification_channel_questions_description
            SyncChannel.CONNECTION -> R.string.notification_channel_connection_description
            SyncChannel.SUMMARY -> R.string.notification_channel_summary_description
            SyncChannel.UPDATES -> R.string.notification_channel_updates_description
        }

        private fun summaryPartString(part: SummaryPart): Int = when (part) {
            SummaryPart.CREATED -> R.string.notification_summary_created
            SummaryPart.UPDATED -> R.string.notification_summary_updated
            SummaryPart.DELETED -> R.string.notification_summary_deleted
            SummaryPart.LINKED -> R.string.notification_summary_linked
            SummaryPart.QUESTIONS -> R.string.notification_summary_questions
            SummaryPart.ERRORS -> R.string.notification_summary_errors
        }
    }
}
