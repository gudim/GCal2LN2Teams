package app.gcaltolotus.app

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Репозиторий GitHub, где выкладываются выпуски приложения (APK в Releases). */
const val RELEASES_REPO: String = "gudim/GCal2LN2Teams"

/** Как часто проверять, вышла ли новая версия. */
val UPDATE_CHECK_PERIOD: Duration = 7.days

/** Выпуск приложения: версия (`0.2.0`, без `v`) и страница, где скачать APK. */
data class AppRelease(val version: String, val pageUrl: String)

/** Последний опубликованный выпуск; `null` — выпусков нет. Ошибка сети — исключение. */
fun interface ReleaseSource {
    suspend fun latest(): AppRelease?
}

/** Последний выпуск из GitHub Releases (`/releases/latest`: без черновиков и pre-release). */
class GitHubReleases(private val http: HttpClient, private val repo: String = RELEASES_REPO) : ReleaseSource {
    override suspend fun latest(): AppRelease? {
        val response = http.get("https://api.github.com/repos/$repo/releases/latest") {
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        if (response.status == HttpStatusCode.NotFound) return null
        check(response.status.isSuccess()) { "GitHub ответил ${response.status}" }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tag = body.getValue("tag_name").jsonPrimitive.content
        return AppRelease(tag.removePrefix("v"), body.getValue("html_url").jsonPrimitive.content)
    }
}

/**
 * [candidate] новее [current]: числа через точку сравниваются по порядку (`0.10.0` > `0.9.1`),
 * недостающие считаются нулями. Нечисловые хвосты (`-beta`) отбрасываются.
 */
fun isNewerVersion(candidate: String, current: String): Boolean {
    fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(candidate)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/**
 * Что известно о выпусках.
 *
 * @property lastCheck когда GitHub последний раз ответил; `null` — ещё не проверяли.
 * @property latest последний выпуск по той проверке (может быть не новее установленной версии).
 * @property notified о какой версии уже было уведомление (одно на версию).
 */
data class UpdateState(
    val lastCheck: Instant? = null,
    val latest: AppRelease? = null,
    val notified: String? = null,
)

/** Хранилище [UpdateState] (DataStore на Android). */
interface UpdateStore {
    suspend fun load(): UpdateState

    suspend fun save(state: UpdateState)
}

class InMemoryUpdateStore(private var state: UpdateState = UpdateState()) : UpdateStore {
    override suspend fun load(): UpdateState = state

    override suspend fun save(state: UpdateState) {
        this.state = state
    }
}

/**
 * Проверка новой версии раз в [period]. Планирует её платформа (на Android — раз в день,
 * сама проверка в сеть ходит, только если с прошлой прошло [period]).
 *
 * @param currentVersion установленная версия (`versionName`).
 */
class UpdateCheck(
    private val source: ReleaseSource,
    private val store: UpdateStore,
    val currentVersion: String,
    private val clock: Clock = Clock.System,
    private val period: Duration = UPDATE_CHECK_PERIOD,
) {
    /**
     * Проверить, если пора. Возвращает выпуск, о котором нужно уведомить (новее установленного
     * и уведомления о нём ещё не было), иначе `null`. Ошибка сети — исключение; время проверки
     * тогда не запоминается, и следующая попытка будет при следующем вызове.
     */
    suspend fun runIfDue(): AppRelease? {
        val state = store.load()
        val now = clock.now()
        val last = state.lastCheck
        if (last != null && now - last < period) return null
        val latest = source.latest()
        val fresh = latest?.takeIf { isNewerVersion(it.version, currentVersion) && it.version != state.notified }
        store.save(UpdateState(now, latest, fresh?.version ?: state.notified))
        return fresh
    }

    /** Выпуск новее установленного по последней проверке (для экрана настроек); иначе `null`. */
    suspend fun available(): AppRelease? =
        store.load().latest?.takeIf { isNewerVersion(it.version, currentVersion) }
}
