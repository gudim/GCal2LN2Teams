package app.gcaltolotus.auth

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Почему вход через браузер не завершился. */
enum class LoopbackFailure {
    /** Не удалось открыть порт (при свободном порту [LoopbackServer.open] почти невозможно). */
    PORT_BUSY,

    /** Браузер не вернулся на redirect за отведённое время. */
    TIMEOUT,

    /** Вход отменён пользователем ([LoopbackServer.cancel]). */
    CANCELLED,
}

/** Вход через браузер не завершился. Текст — для пользователя (по-русски). */
class LoopbackSignInException(val failure: LoopbackFailure, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Запрос, пришедший на сервер: путь и разобранный query. */
data class LoopbackRequest(val method: String, val path: String, val query: Map<String, String>)

/** Разбор запроса браузера и ответ ему. Без сокетов: проверяется JVM-тестами. */
object LoopbackHttp {
    /**
     * Ссылка приложения, на которую браузер уходит после redirect: её ловит
     * [ReturnToAppActivity] и возвращает на экран приложения, закрывая вкладку входа.
     */
    const val RETURN_TO_APP = "app.gcaltolotus://signed-in"

    /**
     * Разобрать строку запроса HTTP (`GET /api/teams/callback?code=..&state=.. HTTP/1.1`).
     * Негодная строка — `null`. Повторяющийся параметр: берётся первое значение.
     */
    fun parseRequestLine(line: String): LoopbackRequest? {
        val parts = line.trim().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
        val target = parts[1]
        if (!target.startsWith("/")) return null
        val path = target.substringBefore('?')
        val rawQuery = if ('?' in target) target.substringAfter('?').substringBefore('#') else ""
        return LoopbackRequest(parts[0], path, parseQuery(rawQuery) ?: return null)
    }

    /** `a=1&b=%20x` → map; ошибка кодировки — `null`. */
    fun parseQuery(raw: String): Map<String, String>? {
        val result = LinkedHashMap<String, String>()
        if (raw.isEmpty()) return result
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val name = decode(pair.substringBefore('=')) ?: return null
            val value = decode(if ('=' in pair) pair.substringAfter('=') else "") ?: return null
            result.putIfAbsent(name, value)
        }
        return result
    }

    private fun decode(s: String): String? = try {
        URLDecoder.decode(s, "UTF-8") // перегрузка с Charset — только с API 33
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * Ответ на redirect: сразу отправить браузер в приложение ([RETURN_TO_APP]). Код уже получен,
     * поэтому, даже если браузер не откроет ссылку, вход завершится, когда пользователь сам
     * вернётся в приложение.
     */
    fun redirectToApp(): ByteArray = (
        "HTTP/1.1 302 Found\r\n" +
            "Location: $RETURN_TO_APP\r\n" +
            "Content-Length: 0\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        ).encodeToByteArray()

    /** Полный HTTP-ответ с закрытием соединения. */
    fun response(status: String, html: String): ByteArray {
        val body = html.encodeToByteArray()
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        return head.encodeToByteArray() + body
    }
}

/**
 * Одноразовый HTTP-сервер на loopback для redirect OAuth (RFC 8252, «loopback redirect»).
 * Создаётся [open] на свободном порту: redirect для входа — `http://localhost:<[port]>`
 * (Microsoft не сравнивает порт у loopback-redirect). Принимает
 * соединения, пока не придёт запрос на [path], отправляет браузер обратно в приложение
 * ([LoopbackHttp.redirectToApp]), возвращает query и закрывается. Прочие запросы (например,
 * `/favicon.ico`) получают 404.
 *
 * @param timeout сколько ждать redirect.
 * @param uiContext где вызывается `onListening` (открытие браузера).
 */
class LoopbackServer private constructor(
    private val server: ServerSocket,
    val path: String,
    private val timeout: Duration,
    private val uiContext: CoroutineContext,
) : AutoCloseable {
    /** Фактический порт сервера (для redirect `http://localhost:<порт>`). */
    val port: Int = server.localPort

    @Volatile
    private var cancelled = false

    /**
     * Вызвать [onListening] (открыть браузер) и ждать redirect. Сервер закрывается в любом случае.
     *
     * @throws LoopbackSignInException время вышло или вход отменён.
     */
    suspend fun awaitCallback(onListening: suspend () -> Unit): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            server.soTimeout = POLL_MS
            withContext(uiContext) { onListening() }
            val deadline = TimeSource.Monotonic.markNow() + timeout
            var result: Map<String, String>? = null
            while (result == null) {
                ensureActive()
                if (cancelled) throw LoopbackSignInException(LoopbackFailure.CANCELLED, "Вход отменён")
                if (deadline.hasPassedNow()) {
                    throw LoopbackSignInException(LoopbackFailure.TIMEOUT, "Время входа истекло: повторите вход")
                }
                val client = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: SocketException) {
                    if (cancelled) throw LoopbackSignInException(LoopbackFailure.CANCELLED, "Вход отменён", e)
                    throw e
                }
                result = handle(client)
            }
            result
        } finally {
            server.close()
        }
    }

    /** Отменить ожидание (кнопка «Отмена» или возврат в приложение без входа). */
    fun cancel() {
        cancelled = true
        server.close()
    }

    override fun close() = server.close()

    /** Обработать одно соединение; query, если это redirect. */
    private fun handle(client: Socket): Map<String, String>? = client.use {
        try {
            it.soTimeout = READ_TIMEOUT_MS
            val line = readLine(it) ?: return null
            val request = LoopbackHttp.parseRequestLine(line)
            val out = it.getOutputStream()
            if (request == null || request.method != "GET" || request.path != path) {
                out.write(LoopbackHttp.response("404 Not Found", "<!doctype html><title>404</title>"))
                out.flush()
                return null
            }
            out.write(LoopbackHttp.redirectToApp())
            out.flush()
            request.query
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            null // браузер оборвал соединение (например, предварительное подключение)
        }
    }

    /** Первая строка запроса (до CRLF), не длиннее 8 КБ. Остальные заголовки не нужны. */
    private fun readLine(client: Socket): String? {
        val input = client.getInputStream()
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < MAX_LINE) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8.name()).takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val POLL_MS = 500
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_LINE = 8 * 1024

        /**
         * Открыть сервер на [bindAddress] (127.0.0.1: снаружи телефона недоступен).
         *
         * @param port 0 — любой свободный (обычный случай).
         * @throws LoopbackSignInException порт занят.
         */
        fun open(
            port: Int = 0,
            path: String = "/",
            bindAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
            timeout: Duration = 5.minutes,
            uiContext: CoroutineContext = Dispatchers.Main,
        ): LoopbackServer {
            val server = ServerSocket()
            try {
                server.reuseAddress = true
                server.bind(InetSocketAddress(bindAddress, port))
            } catch (e: IOException) {
                server.close()
                throw LoopbackSignInException(
                    LoopbackFailure.PORT_BUSY,
                    "Не удалось открыть порт $port для входа: он занят, повторите вход",
                    e,
                )
            }
            return LoopbackServer(server, path, timeout, uiContext)
        }
    }
}
