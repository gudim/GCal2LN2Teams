package app.gcaltolotus.ui

import app.gcaltolotus.app.SettingsStore
import app.gcaltolotus.sync.System
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Экран состояния: последний запуск (ручной или фоновый), открытые вопросы, Lotus выключен или
 * недоступен.
 *
 * @property backgroundMinutes период фоновой синхронизации; 0 — выключена.
 * @property backgroundPending фон ещё не настроен: включится после первого ручного применения.
 * @property nextBackgroundRun когда ожидается следующий фоновый запуск; `null` — неизвестно.
 * @property nextBackgroundSoon время [nextBackgroundRun] уже наступило: WorkManager вот-вот
 *   запустит синхронизацию (ждёт сеть или повторяет после занятой блокировки) — «скоро».
 */
data class StatusState(
    val loaded: Boolean = false,
    val lastRun: LastRun? = null,
    val dominoDisabled: Boolean = false,
    val backgroundMinutes: Int = 0,
    val backgroundPending: Boolean = false,
    val nextBackgroundRun: Instant? = null,
    val nextBackgroundSoon: Boolean = false,
) {
    val openQuestions: Int get() = lastRun?.openQuestions ?: 0

    /** Lotus не ответил в последнем запуске (а не выключен после 401). */
    val dominoUnavailable: Boolean get() = !dominoDisabled && lastRun?.unavailable?.contains(System.DOMINO) == true
}

class StatusModel(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val history: RunHistory,
    private val scheduler: BackgroundScheduler = BackgroundScheduler.None,
    private val clock: Clock = Clock.System,
    private val resumes: Flow<Unit> = emptyFlow(),
) {
    private val mutable = MutableStateFlow(StatusState())
    val state: StateFlow<StatusState> = mutable.asStateFlow()

    fun load(): Job = scope.launch { refresh() }

    /**
     * Перечитывать состояние, пока экран показан: сразу, каждые [period] и при возврате в
     * приложение ([resumes]). Иначе экран показывал бы прошедшее время как «следующий запуск» и
     * старый итог после фонового запуска. Работает, пока не отменён (LaunchedEffect экрана).
     */
    suspend fun keepFresh(period: Duration = 1.minutes): Unit = coroutineScope {
        launch { resumes.collect { refresh() } }
        while (true) {
            refresh()
            delay(period)
        }
    }

    private suspend fun refresh() {
        val s = settings.load()
        val next = if (s.backgroundIntervalMinutes > 0) scheduler.nextRun() else null
        mutable.value = StatusState(
            loaded = true,
            lastRun = history.last(),
            dominoDisabled = s.dominoDisabled,
            backgroundMinutes = s.backgroundIntervalMinutes,
            backgroundPending = !s.backgroundConfigured,
            nextBackgroundRun = next,
            nextBackgroundSoon = next != null && next <= clock.now(),
        )
    }
}
