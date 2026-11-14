package app.gcaltolotus.background

import android.app.NotificationManager
import app.gcaltolotus.app.BackgroundReport
import app.gcaltolotus.app.Route
import app.gcaltolotus.app.RouteCounts
import app.gcaltolotus.app.RouteResult
import app.gcaltolotus.app.RunReport
import app.gcaltolotus.sync.System
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationChannelsTest {
    @Test
    fun eachNotificationGoesToItsChannel() {
        assertEquals(SyncChannel.QUESTIONS, SyncNotification.QUESTIONS.channel)
        assertEquals(SyncChannel.CONNECTION, SyncNotification.LOTUS_DISABLED.channel)
        assertEquals(SyncChannel.CONNECTION, SyncNotification.LOTUS_UNREACHABLE.channel)
        assertEquals(SyncChannel.CONNECTION, SyncNotification.GOOGLE_SIGN_IN.channel)
        assertEquals(SyncChannel.SUMMARY, SyncNotification.SUMMARY.channel)
    }

    @Test
    fun channelImportance() {
        assertEquals(NotificationManager.IMPORTANCE_LOW, SyncChannel.QUESTIONS.importance) // вопросы без звука
        assertEquals(NotificationManager.IMPORTANCE_HIGH, SyncChannel.CONNECTION.importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, SyncChannel.SUMMARY.importance)
    }

    @Test
    fun idsAreUniqueAndOldChannelIsNotReused() {
        assertEquals(SyncNotification.entries.size, SyncNotification.entries.map { it.id }.toSet().size)
        assertEquals(SyncChannel.entries.size, SyncChannel.entries.map { it.id }.toSet().size)
        SyncChannel.entries.forEach { ch -> LEGACY_CHANNEL_IDS.forEach { assertNotEquals(it, ch.id) } }
        // Прежние id уведомлений сохранены: обновление заменяет уже показанные.
        assertEquals(listOf(1, 2, 3, 4), listOf(SyncNotification.QUESTIONS, SyncNotification.LOTUS_DISABLED, SyncNotification.LOTUS_UNREACHABLE, SyncNotification.GOOGLE_SIGN_IN).map { it.id })
    }

    private val words = SummaryWords(
        noChanges = "Изменений нет",
        lotusUnavailable = "Lotus не ответил",
        failed = { "Синхронизация не выполнена: $it" },
        part = { part, n ->
            when (part) {
                SummaryPart.CREATED -> "создано $n"
                SummaryPart.UPDATED -> "обновлено $n"
                SummaryPart.DELETED -> "удалено $n"
                SummaryPart.LINKED -> "связано $n"
                SummaryPart.QUESTIONS -> "вопросов $n"
                SummaryPart.ERRORS -> "ошибок $n"
            }
        },
    )

    private fun report(
        counts: RouteCounts = RouteCounts(),
        errors: List<String> = emptyList(),
        questions: Int = 0,
        dominoUnavailable: String? = null,
    ) = BackgroundReport(
        RunReport(
            routes = mapOf(Route(System.GOOGLE, System.DOMINO) to RouteResult(counts, errors)),
            skipped = emptyList(),
            dominoDisabledNow = false,
            warnings = emptyList(),
            dominoUnavailable = dominoUnavailable,
        ),
        openQuestions = questions,
    )

    @Test
    fun summaryListsNonZeroCounts() {
        assertEquals(
            "Создано 3, обновлено 1, вопросов 2",
            backgroundSummary(report(RouteCounts(created = 3, updated = 1), questions = 2), words),
        )
        assertEquals(
            "Удалено 1, связано 2, ошибок 1, Lotus не ответил",
            backgroundSummary(report(RouteCounts(deleted = 1, linked = 2), errors = listOf("x"), dominoUnavailable = "timeout"), words),
        )
    }

    @Test
    fun summaryWithoutChanges() {
        assertEquals("Изменений нет", backgroundSummary(report(), words))
        assertEquals("Lotus не ответил", backgroundSummary(report(dominoUnavailable = "timeout"), words))
    }

    @Test
    fun failedSummaryHasTheError() {
        assertEquals("Синхронизация не выполнена: нет сети", failedSummary(RuntimeException("нет сети"), words))
    }
}
