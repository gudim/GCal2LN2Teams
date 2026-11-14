package app.gcaltolotus.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class UpdatesTest {
    private class TestClock(var now: Instant) : Clock {
        override fun now(): Instant = now
        fun advance(d: Duration) {
            now += d
        }
    }

    private val clock = TestClock(Instant.parse("2026-11-14T10:00:00Z"))
    private var latest: AppRelease? = AppRelease("0.2.0", "https://github.com/x/y/releases/tag/v0.2.0")
    private var calls = 0
    private val source = ReleaseSource {
        calls++
        latest
    }
    private val store = InMemoryUpdateStore()
    private val check = UpdateCheck(source, store, "0.1.0", clock)

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(isNewerVersion("0.10.0", "0.9.1"))
        assertTrue(isNewerVersion("v1.0", "0.9.9"))
        assertTrue(isNewerVersion("0.1.1", "0.1"))
        assertFalse(isNewerVersion("0.1.0", "0.1"))
        assertFalse(isNewerVersion("0.1.0-beta", "0.1.0"))
        assertFalse(isNewerVersion("0.9.0", "0.10.0"))
    }

    @Test
    fun notifiesOncePerVersionAndChecksWeekly() = runTest {
        assertEquals(latest, check.runIfDue()) // первый раз — сразу
        assertEquals(latest, check.available())

        clock.advance(6.days)
        assertNull(check.runIfDue())
        assertEquals(1, calls) // неделя не прошла — в сеть не ходили

        clock.advance(1.days)
        assertNull(check.runIfDue()) // та же версия: второго уведомления нет
        assertEquals(2, calls)

        latest = AppRelease("0.3.0", "page")
        clock.advance(7.days)
        assertEquals(latest, check.runIfDue())
    }

    @Test
    fun sameOrOlderReleaseIsNotAnUpdate() = runTest {
        latest = AppRelease("0.1.0", "page")
        assertNull(check.runIfDue())
        assertNull(check.available())
        latest = null
        clock.advance(7.days)
        assertNull(check.runIfDue())
    }

    @Test
    fun failedCheckIsRetriedNextTime() = runTest {
        val failing = UpdateCheck({ calls++; error("нет сети") }, store, "0.1.0", clock)
        assertFailsWith<IllegalStateException> { failing.runIfDue() }
        assertNull(store.load().lastCheck)
        assertEquals(latest, check.runIfDue()) // сразу, не через неделю
    }

    @Test
    fun readsLatestReleaseFromGitHub() = runTest {
        var url = ""
        val http = HttpClient(MockEngine { request ->
            url = request.url.toString()
            respond(
                """{"tag_name": "v0.2.0", "html_url": "https://github.com/o/r/releases/tag/v0.2.0", "draft": false}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        assertEquals(
            AppRelease("0.2.0", "https://github.com/o/r/releases/tag/v0.2.0"),
            GitHubReleases(http, "o/r").latest(),
        )
        assertEquals("https://api.github.com/repos/o/r/releases/latest", url)
    }

    @Test
    fun noReleasesYetIsNull() = runTest {
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.NotFound) })
        assertNull(GitHubReleases(http, "o/r").latest())
    }
}
