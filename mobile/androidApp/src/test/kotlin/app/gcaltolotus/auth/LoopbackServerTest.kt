package app.gcaltolotus.auth

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Настоящий сокет на свободном порту 127.0.0.1; «браузер» — простой клиент в потоке. */
class LoopbackServerTest {
    private fun open(timeout: kotlin.time.Duration = 10.seconds) =
        LoopbackServer.open(timeout = timeout, uiContext = Dispatchers.Default)

    /** Отправить запрос и вернуть ответ сервера целиком. */
    private fun get(port: Int, target: String): String =
        Socket(InetAddress.getByName("127.0.0.1"), port).use { s ->
            s.getOutputStream().write("GET $target HTTP/1.1\r\nHost: localhost:$port\r\n\r\n".encodeToByteArray())
            s.getInputStream().readBytes().decodeToString()
        }

    @Test
    fun opensOnEphemeralPort() {
        open().use { s -> assertTrue(s.port > 0) }
    }

    @Test
    fun returnsCallbackQueryAndAnswers404ToOtherPaths() = runBlocking {
        val server = open()
        val replies = java.util.Collections.synchronizedList(mutableListOf<String>())
        val query = withTimeout(10.seconds) {
            server.awaitCallback {
                Thread {
                    replies += get(server.port, "/favicon.ico")
                    replies += get(server.port, "/?code=abc&state=xyz")
                }.start()
            }
        }
        assertEquals(mapOf("code" to "abc", "state" to "xyz"), query)
        Thread.sleep(300)
        assertTrue(replies[0].startsWith("HTTP/1.1 404"))
        assertTrue(replies[1].startsWith("HTTP/1.1 200"))
        assertTrue("href=\"${LoopbackHttp.RETURN_TO_APP}\"" in replies[1])
    }

    @Test
    fun closesAfterCallback() = runBlocking {
        val server = open()
        val port = server.port
        server.awaitCallback { Thread { get(port, "/?code=1&state=2") }.start() }
        // Порт освобождён: его снова можно занять.
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()
    }

    @Test
    fun timesOut() = runBlocking {
        val server = open(timeout = 300.milliseconds)
        try {
            server.awaitCallback {}
            fail("ожидался таймаут")
        } catch (e: LoopbackSignInException) {
            assertEquals(LoopbackFailure.TIMEOUT, e.failure)
        }
    }

    @Test
    fun cancelStopsWaiting() = runBlocking {
        val server = open()
        try {
            server.awaitCallback { Thread { Thread.sleep(200); server.cancel() }.start() }
            fail("ожидалась отмена")
        } catch (e: LoopbackSignInException) {
            assertEquals(LoopbackFailure.CANCELLED, e.failure)
        }
    }

    @Test
    fun busyPortIsReported() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { taken ->
            try {
                LoopbackServer.open(port = taken.localPort, uiContext = Dispatchers.Default).close()
                fail("ожидалась ошибка занятого порта")
            } catch (e: LoopbackSignInException) {
                assertEquals(LoopbackFailure.PORT_BUSY, e.failure)
            }
        }
    }
}
