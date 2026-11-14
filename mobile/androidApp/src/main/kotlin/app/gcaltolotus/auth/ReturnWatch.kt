package app.gcaltolotus.auth

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Возврат из браузера без входа. Пока идёт вход, экран приложения уходит в фон (ON_PAUSE:
 * открылась вкладка браузера). Если он снова виден (ON_RESUME), а redirect так и не пришёл за
 * [grace], пользователь закрыл вкладку, не войдя: ожидание отменяется.
 *
 * @param lifecycle жизненный цикл экрана, с которого начат вход.
 * @param grace сколько ждать redirect после возврата: браузер мог ещё не дойти до него.
 */
internal class ReturnWatch(private val lifecycle: Lifecycle, private val grace: Duration = 3.seconds) {
    /**
     * Выполнить [wait] (ожидание redirect); при возврате без redirect вызвать [cancel], который
     * должен прервать [wait]. Наблюдение снимается, как только [wait] закончился.
     */
    suspend fun <T> cancelIfReturnedWithout(cancel: () -> Unit, wait: suspend () -> T): T = coroutineScope {
        var left = false
        var pending: Job? = null
        // События приходят в главном потоке; там же создаётся и снимается наблюдатель.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    left = true
                    pending?.cancel()
                }
                // ON_RESUME при подписке на уже открытый экран — не возврат: в браузер ещё не уходили.
                Lifecycle.Event.ON_RESUME -> if (left) {
                    pending?.cancel()
                    pending = launch(Dispatchers.Main.immediate) {
                        delay(grace)
                        cancel()
                    }
                }
                else -> Unit
            }
        }
        withContext(Dispatchers.Main.immediate) { lifecycle.addObserver(observer) }
        try {
            wait()
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                lifecycle.removeObserver(observer)
                pending?.cancel()
            }
        }
    }
}
