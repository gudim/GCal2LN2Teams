package app.gcaltolotus.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.ConnectionPool

/**
 * Клиенты Ktor на OkHttp для адаптеров с общим пулом соединений.
 *
 * Повторов нет ни на каком уровне: плагин HttpRequestRetry не ставится, а OkHttp не повторяет
 * запрос после обрыва соединения. Повтор запроса к Domino после 401 может заблокировать учётную
 * запись (ADR 0002), а повтор POST создал бы дубль события. Authenticator у OkHttp не задан,
 * поэтому 401 тоже не повторяется.
 *
 * Поэтому перед каждым запуском синхронизации нужно вызвать [dropIdle]. Когда приложение уходит в
 * фон, Android закрывает ему сеть (`blocked=APP_BACKGROUND`) и может уничтожить его сокеты;
 * соединение, оставшееся в пуле, при следующем запросе падает с «Software caused connection
 * abort» (ECONNABORTED — так завершается уничтоженный сокет). OkHttp перед GET не проверяет
 * простаивающее HTTP/1.1-соединение (так ходит Domino), а повторить запрос на новом соединении ему
 * не дают, поэтому фоновый запуск видел Domino недоступным. HTTP/2 (Google, Teams), вероятно, это
 * замечает сам: у соединения есть постоянно читающий поток.
 */
class HttpConnections {
    private val pool = ConnectionPool()

    fun createClient(): HttpClient = HttpClient(OkHttp) {
        engine {
            config {
                retryOnConnectionFailure(false)
                connectionPool(pool)
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
            requestTimeoutMillis = 120_000
        }
    }

    /**
     * Закрыть простаивающие соединения (идущие запросы не трогаются). Ни один запрос не
     * повторяется: следующий просто откроет новое соединение.
     */
    fun dropIdle() = pool.evictAll()

    fun idleCount(): Int = pool.idleConnectionCount()
}
