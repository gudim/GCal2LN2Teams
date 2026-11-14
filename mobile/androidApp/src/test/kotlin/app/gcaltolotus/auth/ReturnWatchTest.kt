package app.gcaltolotus.auth

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReturnWatchTest {
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun test(body: suspend TestScope.(LifecycleRegistry) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            owner.registry.currentState = Lifecycle.State.RESUMED
            body(owner.registry)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun returningWithoutRedirectCancelsAfterGrace() = test { registry ->
        val redirect = CompletableDeferred<String>()
        var cancelled = false
        val result = async {
            ReturnWatch(registry, 3.seconds).cancelIfReturnedWithout({ cancelled = true; redirect.complete("отменён") }) {
                redirect.await()
            }
        }
        testScheduler.advanceTimeBy(10_000)
        assertFalse(cancelled) // экран открыт, в браузер ещё не уходили

        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) // открылась вкладка браузера
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) // вкладку закрыли без входа
        testScheduler.advanceTimeBy(2_000)
        assertFalse(cancelled) // браузер ещё мог дойти до redirect
        testScheduler.advanceTimeBy(2_000)
        assertTrue(cancelled)
        assertEquals("отменён", result.await())
        assertEquals(0, registry.observerCount)
    }

    @Test
    fun redirectBeforeReturnIsNotCancelled() = test { registry ->
        val redirect = CompletableDeferred<String>()
        var cancelled = false
        val result = async {
            ReturnWatch(registry, 3.seconds).cancelIfReturnedWithout({ cancelled = true }) { redirect.await() }
        }
        testScheduler.runCurrent()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        redirect.complete("code") // redirect пришёл, пока на экране браузер
        assertEquals("code", result.await())

        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        testScheduler.advanceTimeBy(10_000)
        assertFalse(cancelled)
        assertEquals(0, registry.observerCount)
    }

    @Test
    fun leavingAgainBeforeGraceKeepsWaiting() = test { registry ->
        val redirect = CompletableDeferred<String>()
        var cancelled = false
        val result = async {
            ReturnWatch(registry, 3.seconds).cancelIfReturnedWithout({ cancelled = true }) { redirect.await() }
        }
        testScheduler.runCurrent()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        testScheduler.advanceTimeBy(1_000)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) // снова в браузер (например, двухфакторный вход)
        testScheduler.advanceTimeBy(10_000)
        assertFalse(cancelled)
        redirect.complete("code")
        assertEquals("code", result.await())
    }
}
