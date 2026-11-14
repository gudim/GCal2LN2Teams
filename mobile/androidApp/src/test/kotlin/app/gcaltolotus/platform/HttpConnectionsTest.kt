package app.gcaltolotus.platform

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Соединения, оставшиеся в пуле с прошлого запуска, могут быть уже мертвы: Android, убирая
 * приложение в фон, уничтожает его сокеты («Software caused connection abort»). OkHttp для GET
 * не проверяет простаивающее HTTP/1.1-соединение и повторов у нас нет, поэтому первый же запрос к
 * Domino падал. [HttpConnections.dropIdle] перед запуском закрывает такие соединения.
 *
 * Здесь «мёртвое» соединение — то, которое сервер на loopback сбросил (RST) после первого ответа.
 */
class HttpConnectionsTest {
    private val server = ServerSocket(0)
    private val accepted = AtomicInteger()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                val number = accepted.incrementAndGet()
                thread(isDaemon = true) { serve(socket, resetAfterFirst = number == 1) }
            }
        }
    }

    /** Отвечает 200 «ok» с keep-alive; первое соединение после ответа сбрасывает. */
    private fun serve(socket: Socket, resetAfterFirst: Boolean) {
        socket.use {
            val input = it.getInputStream().bufferedReader()
            while (true) {
                val line = input.readLine() ?: return
                if (line.isNotEmpty()) continue
                it.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok".toByteArray(),
                )
                it.getOutputStream().flush()
                if (resetAfterFirst) {
                    Thread.sleep(100)
                    it.setSoLinger(true, 0) // close() отправит RST
                    return
                }
            }
        }
    }

    private val url = "http://127.0.0.1:${server.localPort}/api/calendar/events"

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun staleConnectionFailsWithoutDropAndWorksAfterIt() = runBlocking {
        val connections = HttpConnections()
        val client = connections.createClient()
        assertEquals("ok", client.get(url).bodyAsText())
        Thread.sleep(300) // сервер сбросил соединение, а в пуле оно осталось
        assertEquals(1, connections.idleCount())

        val stale = runCatching { client.get(url).bodyAsText() }
        assertTrue("повторно использованное мёртвое соединение: $stale", stale.isFailure)

        assertEquals("ok", client.get(url).bodyAsText()) // уже новое соединение
        Thread.sleep(300)
        connections.dropIdle()
        assertEquals(0, connections.idleCount())
        assertEquals("ok", client.get(url).bodyAsText())
        assertEquals(3, accepted.get())
    }

    @Test
    fun dropBeforeRunAvoidsTheStaleConnection() = runBlocking {
        val connections = HttpConnections()
        val client = connections.createClient()
        assertEquals("ok", client.get(url).bodyAsText())
        Thread.sleep(300)

        connections.dropIdle() // как в начале запуска

        assertEquals("ok", client.get(url).bodyAsText())
        assertEquals(2, accepted.get())
    }
}
