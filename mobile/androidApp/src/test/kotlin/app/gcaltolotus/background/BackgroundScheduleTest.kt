package app.gcaltolotus.background

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import app.gcaltolotus.app.BACKGROUND_INTERVALS
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundScheduleTest {
    private fun periodMinutes(interval: Int): Long =
        TimeUnit.MILLISECONDS.toMinutes(backgroundWorkRequest(interval)!!.workSpec.intervalDuration)

    @Test
    fun offMeansNoWork() {
        assertNull(backgroundWorkRequest(0))
        assertNull(backgroundWorkRequest(-5))
    }

    @Test
    fun settingsIntervalsMapToPeriods() {
        assertEquals(15L, periodMinutes(15))
        assertEquals(60L, periodMinutes(60))
        assertEquals(240L, periodMinutes(240))
    }

    @Test
    fun firstRunWaitsOnePeriod() {
        for (minutes in listOf(15, 60, 240)) {
            val spec = backgroundWorkRequest(minutes)!!.workSpec
            assertEquals(spec.intervalDuration, spec.initialDelay)
        }
        // Поднятый до минимума период — и задержка тоже.
        assertEquals(TimeUnit.MINUTES.toMillis(15), backgroundWorkRequest(5)!!.workSpec.initialDelay)
    }

    @Test
    fun tooShortIntervalIsRaisedToWorkManagerMinimum() {
        assertEquals(15L, periodMinutes(5))
    }

    @Test
    fun requiresConnectedNetworkAndRunsTheSyncWorker() {
        val spec = backgroundWorkRequest(60)!!.workSpec
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(SyncWorker::class.java.name, spec.workerClassName)
        assertTrue(spec.isPeriodic)
    }

    @Test
    fun busyLockRetryIsShortAndLinear() {
        val spec = backgroundWorkRequest(60)!!.workSpec
        assertEquals(BackoffPolicy.LINEAR, spec.backoffPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(1), spec.backoffDelayDuration)
    }

    @Test
    fun everySettingsChoiceIsHandled() {
        for (minutes in BACKGROUND_INTERVALS) {
            val request = backgroundWorkRequest(minutes)
            if (minutes == 0) assertNull(request) else assertEquals(minutes.toLong(), periodMinutes(minutes))
        }
    }
}
