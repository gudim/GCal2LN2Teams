package app.gcaltolotus.background

import android.app.NotificationManager
import app.gcaltolotus.app.BackgroundReport

/**
 * Каналы уведомлений: каждый пользователь настраивает в системе отдельно. Важность канала
 * после создания меняет только пользователь, поэтому у каждого канала своя важность с самого
 * начала.
 */
enum class SyncChannel(val id: String, val importance: Int) {
    /**
     * Открытые вопросы после фоновой синхронизации: в шторке, но без звука. Новый id, потому что
     * важность существующего канала приложение изменить не может (прежний "questions" был со звуком).
     */
    QUESTIONS("questions_silent", NotificationManager.IMPORTANCE_LOW),

    /** Lotus выключен или недоступен, нужен вход в Google. */
    CONNECTION("connection", NotificationManager.IMPORTANCE_HIGH),

    /** Итог каждой фоновой синхронизации (по переключателю в настройках), без звука. */
    SUMMARY("summary", NotificationManager.IMPORTANCE_LOW),

    /** Вышла новая версия приложения (проверка раз в неделю). */
    UPDATES("updates", NotificationManager.IMPORTANCE_DEFAULT),
}

/** Канал до разделения на [SyncChannel]; удаляется при старте. */
const val LEGACY_CHANNEL_ID: String = "sync"

/** Прежние каналы, которые удаляются при старте: общий и «Вопросы» со звуком. */
val LEGACY_CHANNEL_IDS: List<String> = listOf(LEGACY_CHANNEL_ID, "questions")

/** Уведомления приложения: id (новое уведомление того же вида заменяет прежнее) и канал. */
enum class SyncNotification(val id: Int, val channel: SyncChannel) {
    QUESTIONS(1, SyncChannel.QUESTIONS),
    LOTUS_DISABLED(2, SyncChannel.CONNECTION),
    LOTUS_UNREACHABLE(3, SyncChannel.CONNECTION),
    GOOGLE_SIGN_IN(4, SyncChannel.CONNECTION),
    SUMMARY(5, SyncChannel.SUMMARY),
    UPDATE(6, SyncChannel.UPDATES),
}

/** Части итога фоновой синхронизации. */
enum class SummaryPart { CREATED, UPDATED, DELETED, LINKED, QUESTIONS, ERRORS }

/**
 * Слова итога (из ресурсов Android; в тестах — свои).
 *
 * @property part «создано 3» и т. п. для каждой части с ненулевым числом.
 */
class SummaryWords(
    val noChanges: String,
    val lotusUnavailable: String,
    val failed: (String) -> String,
    val part: (SummaryPart, Int) -> String,
)

/**
 * Текст итога фоновой синхронизации: «Создано 3, обновлено 1, вопросов 2»; ничего не
 * произошло — «Изменений нет». Domino не ответил — в конце «Lotus не ответил».
 */
fun backgroundSummary(report: BackgroundReport, words: SummaryWords): String {
    val totals = report.run.totals
    val parts = listOf(
        SummaryPart.CREATED to totals.created,
        SummaryPart.UPDATED to totals.updated,
        SummaryPart.DELETED to totals.deleted,
        SummaryPart.LINKED to totals.linked,
        SummaryPart.QUESTIONS to report.openQuestions,
        SummaryPart.ERRORS to report.run.errors.size,
    ).filter { it.second > 0 }.map { (part, n) -> words.part(part, n) }
        .let { if (report.run.dominoUnavailable != null) it + words.lotusUnavailable else it }
    if (parts.isEmpty()) return words.noChanges
    return parts.joinToString(", ").replaceFirstChar { it.uppercaseChar() }
}

/** Итог неудавшегося фонового запуска: «Синхронизация не выполнена: <ошибка>». */
fun failedSummary(error: Throwable, words: SummaryWords): String =
    words.failed(error.message ?: error.toString())
