package app.gcaltolotus.ui

import app.gcaltolotus.app.RunReport
import app.gcaltolotus.app.SyncSettings
import app.gcaltolotus.app.shouldNotifyDominoUnreachable
import kotlin.time.Instant

/** Чем закончился запуск ([SyncRuns]): для уведомлений после него. */
sealed interface RunOutcome {
    val background: Boolean

    /** Запуск выполнен; [openQuestions] — вопросы, оставшиеся без ответа. */
    data class Finished(val report: RunReport, val openQuestions: Int, override val background: Boolean) : RunOutcome

    data class Failed(val error: Throwable, override val background: Boolean) : RunOutcome
}

/**
 * Что сделать с уведомлениями после запуска: `true` — показать, `false` — убрать, `null` — не
 * трогать. [questions]: число открытых вопросов (0 — убрать), `null` — не трогать.
 */
data class NoticeChanges(
    val questions: Int? = null,
    val lotusDisabled: Boolean? = null,
    val lotusUnreachable: Boolean? = null,
    val googleSignIn: Boolean? = null,
)

/**
 * Уведомления после любого запуска, ручного или фонового.
 *
 * - Запуск выполнен: число открытых вопросов обновляется; Lotus выключен после 401 — показать;
 *   вход в Google больше не нужен — убрать; Domino не отвечает дольше суток — показать, ответил —
 *   убрать (не ответил, но меньше суток — не трогать).
 * - Запуск не выполнен: нужен вход в Google ([googleSignInRequired]) — показать; прочее не
 *   трогать (ошибка видна на экране или в итоге фона).
 *
 * @param settings настройки после запуска (он пишет `lastDominoSuccess`).
 */
fun noticeChanges(
    outcome: RunOutcome,
    settings: SyncSettings,
    now: Instant,
    googleSignInRequired: (Throwable) -> Boolean,
): NoticeChanges = when (outcome) {
    is RunOutcome.Finished -> {
        val report = outcome.report
        NoticeChanges(
            questions = outcome.openQuestions,
            lotusDisabled = if (report.dominoDisabledNow) true else null,
            lotusUnreachable = when {
                shouldNotifyDominoUnreachable(report, settings, now) -> true
                report.dominoUnavailable == null -> false
                else -> null
            },
            googleSignIn = false,
        )
    }
    is RunOutcome.Failed -> NoticeChanges(googleSignIn = if (googleSignInRequired(outcome.error)) true else null)
}
