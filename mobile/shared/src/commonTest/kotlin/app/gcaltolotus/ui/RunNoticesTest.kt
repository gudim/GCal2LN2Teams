package app.gcaltolotus.ui

import app.gcaltolotus.app.NOW
import app.gcaltolotus.app.RunReport
import app.gcaltolotus.app.SyncSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

class RunNoticesTest {
    private fun report(dominoDisabledNow: Boolean = false, dominoUnavailable: String? = null) =
        RunReport(emptyMap(), emptyList(), dominoDisabledNow, emptyList(), dominoUnavailable)

    private val signInRequired = IllegalStateException("нужен вход")

    private fun changes(outcome: RunOutcome, lastDominoSuccessHoursAgo: Int? = 1) = noticeChanges(
        outcome,
        SyncSettings(lastDominoSuccess = lastDominoSuccessHoursAgo?.let { NOW - it.hours }),
        NOW,
    ) { it === signInRequired }

    @Test
    fun finishedRunSetsQuestionsAndClearsGoogleAndUnreachable() {
        for (background in listOf(true, false)) {
            assertEquals(
                NoticeChanges(questions = 2, lotusDisabled = null, lotusUnreachable = false, googleSignIn = false),
                changes(RunOutcome.Finished(report(), 2, background)),
            )
        }
        assertEquals(0, changes(RunOutcome.Finished(report(), 0, background = false)).questions) // ответил — убрать
    }

    @Test
    fun lotusDisabledDuringManualRunIsShown() {
        assertEquals(true, changes(RunOutcome.Finished(report(dominoDisabledNow = true), 0, background = false)).lotusDisabled)
    }

    @Test
    fun unreachableIsShownAfterADayAndKeptBefore() {
        val down = RunOutcome.Finished(report(dominoUnavailable = "таймаут"), 0, background = true)
        assertEquals(true, changes(down, lastDominoSuccessHoursAgo = 25).lotusUnreachable)
        assertEquals(null, changes(down, lastDominoSuccessHoursAgo = 3).lotusUnreachable)
    }

    @Test
    fun failureOnlyTouchesGoogleSignIn() {
        assertEquals(NoticeChanges(googleSignIn = true), changes(RunOutcome.Failed(signInRequired, background = true)))
        assertEquals(NoticeChanges(), changes(RunOutcome.Failed(RuntimeException("503"), background = false)))
    }
}
