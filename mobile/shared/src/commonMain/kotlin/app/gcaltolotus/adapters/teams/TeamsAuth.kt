package app.gcaltolotus.adapters.teams

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

// Вход в Teams по OAuth 2.0 (authorization code + PKCE S256) без MSAL
// (docs/adr/0002-mobile-kmp-on-device.md). На телефоне это публичный клиент той же регистрации
// в Azure: секрета нет, redirect — loopback `http://localhost:<порт>` (платформа «Mobile and
// desktop»; порт Microsoft для loopback не сверяет). Конфиденциальный режим (с секретом и
// фиксированным redirect, как msal.ConfidentialClientApplication в Python) тоже работает.

/** Loopback-redirect публичного клиента; порт подставляет [AuthorizationBrowser]. */
const val TEAMS_LOOPBACK_REDIRECT_URI: String = "http://localhost"

/** Redirect настольной версии (платформа «Web», конфиденциальный клиент с секретом). */
const val TEAMS_DESKTOP_REDIRECT_URI: String = "http://localhost:8000/api/teams/callback"

/** Single-tenant приложение: только рабочие учётные записи. */
const val TEAMS_AUTHORITY: String = "https://login.microsoftonline.com/organizations"

/** Права Python-версии плюс `offline_access` (MSAL добавляет его сам), чтобы получить refresh-токен. */
val TEAMS_SCOPES: List<String> = listOf("Calendars.ReadWrite", "User.Read", "offline_access")

/**
 * Настройки OAuth-клиента Teams. [toString] не показывает секрет.
 *
 * @property clientSecret `null` — публичный клиент (телефон): `client_secret` не отправляется
 *   ни при обмене кода, ни при обновлении; защиту кода даёт PKCE. С секретом — конфиденциальный
 *   клиент, как настольная версия.
 * @property redirectUri базовый redirect: [AuthorizationBrowser] получает его и возвращает
 *   фактически использованный (для loopback — с портом, см. [loopbackRedirectUri]; для
 *   фиксированного, например [TEAMS_DESKTOP_REDIRECT_URI], — его же).
 */
data class TeamsOAuthConfig(
    val clientId: String,
    val clientSecret: String? = null,
    val redirectUri: String = TEAMS_LOOPBACK_REDIRECT_URI,
    val authority: String = TEAMS_AUTHORITY,
    val scopes: List<String> = TEAMS_SCOPES,
) {
    val authorizeEndpoint: String get() = "${authority.trimEnd('/')}/oauth2/v2.0/authorize"
    val tokenEndpoint: String get() = "${authority.trimEnd('/')}/oauth2/v2.0/token"

    override fun toString(): String =
        "TeamsOAuthConfig(clientId=$clientId, clientSecret=${if (clientSecret == null) "null" else "***"}, " +
            "redirectUri=$redirectUri, " +
            "authority=$authority, scopes=$scopes)"
}

/**
 * Токены Teams. [refreshToken] ротируется: после каждого обновления хранится новый.
 * [toString] не показывает токены.
 */
data class TeamsTokens(val accessToken: String, val refreshToken: String?, val expiresAt: Instant) {
    override fun toString(): String =
        "TeamsTokens(accessToken=***, refreshToken=${if (refreshToken == null) "null" else "***"}, " +
            "expiresAt=$expiresAt)"
}

/** Хранилище токенов (на телефоне — зашифрованное; реализует платформа). */
interface TeamsTokenStore {
    suspend fun load(): TeamsTokens?

    suspend fun save(tokens: TeamsTokens)

    suspend fun clear()
}

/** Хранилище в памяти: для тестов. */
class InMemoryTeamsTokenStore(private var tokens: TeamsTokens? = null) : TeamsTokenStore {
    override suspend fun load(): TeamsTokens? = tokens

    override suspend fun save(tokens: TeamsTokens) {
        this.tokens = tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}

/**
 * Ответ браузера входа.
 *
 * @property redirectUri redirect, на который пришёл ответ (тот, что передан в `buildUrl`); он
 *   же уходит в обмен кода на токены.
 * @property params query redirect: `code` и `state` или `error` и `error_description`.
 */
data class AuthorizationReply(val redirectUri: String, val params: Map<String, String>)

/**
 * Шов к платформенному UI входа. Браузер сам выбирает фактический redirect: для loopback
 * поднимает HTTP-сервер на свободном порту и берёт `loopbackRedirectUri(redirectUri, порт)`,
 * для фиксированного redirect использует [redirectUri] как есть. Затем открывает
 * `buildUrl(фактический redirect)`, принимает переход и возвращает [AuthorizationReply].
 * Проверки `state` и redirect делает [TeamsAuth].
 *
 * @param redirectUri базовый redirect из [TeamsOAuthConfig.redirectUri].
 */
fun interface AuthorizationBrowser {
    suspend fun authorize(redirectUri: String, buildUrl: (redirectUri: String) -> String): AuthorizationReply
}

/**
 * Ошибка входа или обновления токена.
 *
 * @property error код OAuth (`invalid_grant`, `access_denied`, ...) или свой: `no_tokens`,
 *   `no_refresh_token`, `state_mismatch`, `redirect_mismatch`, `no_code`, `bad_response`, `http_<статус>`.
 * @property status HTTP-статус ответа token endpoint, если ошибка от него.
 */
class TeamsAuthException(
    val error: String,
    val description: String? = null,
    val status: Int? = null,
) : Exception("Teams OAuth: $error${if (description != null) " - $description" else ""}") {
    /** Нужен повторный вход через браузер: токенов нет, refresh-токен истёк или отозван. */
    val signInRequired: Boolean get() = error in SIGN_IN_REQUIRED

    private companion object {
        val SIGN_IN_REQUIRED = setOf(
            "no_tokens", "no_refresh_token", "invalid_grant", "interaction_required",
            "login_required", "consent_required",
        )
    }
}

/** Случайный `state` для защиты от CSRF (криптостойкий генератор платформы). */
@OptIn(ExperimentalUuidApi::class)
fun newOAuthState(): String = Uuid.random().toHexString()

/**
 * OAuth 2.0 для MS Graph: URL входа, обмен кода на токены, обновление по refresh-токену.
 * Токены лежат в [store]; [accessToken] и [refresh] выполняются по одному (общий [Mutex]),
 * чтобы ротируемый refresh-токен не использовался дважды.
 *
 * @param refreshMargin токен обновляется заранее, если до истечения осталось меньше этого.
 */
class TeamsAuth(
    private val http: HttpClient,
    val config: TeamsOAuthConfig,
    private val store: TeamsTokenStore,
    private val clock: Clock = Clock.System,
    private val refreshMargin: Duration = 5.minutes,
) {
    private val mutex = Mutex()

    /**
     * URL страницы входа Microsoft (authorization code flow с PKCE S256, ответ в query
     * redirect URI).
     *
     * @param codeChallenge [pkceChallenge] от `code_verifier` этого входа.
     */
    fun authorizeUrl(state: String, codeChallenge: String, redirectUri: String = config.redirectUri): String =
        URLBuilder(config.authorizeEndpoint).apply {
            parameters.append("client_id", config.clientId)
            parameters.append("response_type", "code")
            parameters.append("redirect_uri", redirectUri)
            parameters.append("response_mode", "query")
            parameters.append("scope", config.scopes.joinToString(" "))
            parameters.append("state", state)
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
        }.buildString()

    /**
     * Полный вход: открыть страницу через [browser], проверить `state` и redirect, обменять
     * код на токены (с тем же redirect и `code_verifier`) и сохранить их.
     */
    suspend fun signIn(
        state: String = newOAuthState(),
        codeVerifier: String = newPkceVerifier(),
        browser: AuthorizationBrowser,
    ): TeamsTokens {
        val challenge = pkceChallenge(codeVerifier)
        val sent = mutableSetOf<String>()
        val reply = browser.authorize(config.redirectUri) { redirectUri ->
            sent += redirectUri
            authorizeUrl(state, challenge, redirectUri)
        }
        val params = reply.params
        if (params["state"] != state) {
            throw TeamsAuthException("state_mismatch", "state в ответе не совпадает с отправленным")
        }
        if (reply.redirectUri !in sent) {
            throw TeamsAuthException("redirect_mismatch", "ответ пришёл не на тот redirect, что был в URL входа")
        }
        params["error"]?.let { throw TeamsAuthException(it, params["error_description"]) }
        val code = params["code"] ?: throw TeamsAuthException("no_code", "в ответе нет code")
        return exchangeCode(code, codeVerifier, reply.redirectUri)
    }

    /**
     * Обменять код авторизации на токены и сохранить их.
     *
     * @param redirectUri тот же, что был в URL входа.
     */
    suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
        redirectUri: String = config.redirectUri,
    ): TeamsTokens = mutex.withLock {
        val tokens = requestTokens(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirectUri,
                "code_verifier" to codeVerifier,
            ),
            previousRefreshToken = null,
        )
        store.save(tokens)
        tokens
    }

    /**
     * Действующий access-токен: сохранённый, если он не истекает в ближайшие [refreshMargin],
     * иначе обновлённый по refresh-токену.
     *
     * @throws TeamsAuthException с [TeamsAuthException.signInRequired], если нужен вход.
     */
    suspend fun accessToken(): String = mutex.withLock {
        val tokens = store.load() ?: throw TeamsAuthException("no_tokens", "вход в Teams не выполнен")
        if (tokens.expiresAt - refreshMargin > clock.now()) tokens.accessToken else refreshLocked(tokens)
    }

    /** Обновить токен сейчас (например, после 401) и вернуть новый access-токен. */
    suspend fun refresh(): String = mutex.withLock {
        val tokens = store.load() ?: throw TeamsAuthException("no_tokens", "вход в Teams не выполнен")
        refreshLocked(tokens)
    }

    /** Выйти: забыть токены. */
    suspend fun signOut() {
        mutex.withLock { store.clear() }
    }

    private suspend fun refreshLocked(tokens: TeamsTokens): String {
        val refreshToken = tokens.refreshToken
            ?: throw TeamsAuthException("no_refresh_token", "нет refresh-токена, нужен вход")
        val fresh = requestTokens(
            mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken),
            previousRefreshToken = refreshToken,
        )
        store.save(fresh)
        return fresh.accessToken
    }

    /**
     * POST на token endpoint; `client_secret` — только у конфиденциального клиента. Без нового
     * refresh-токена в ответе остаётся прежний.
     */
    private suspend fun requestTokens(fields: Map<String, String>, previousRefreshToken: String?): TeamsTokens {
        val now = clock.now()
        val response = http.submitForm(
            config.tokenEndpoint,
            parameters {
                append("client_id", config.clientId)
                config.clientSecret?.let { append("client_secret", it) }
                append("scope", config.scopes.joinToString(" "))
                for ((k, v) in fields) append(k, v)
            },
        ) { expectSuccess = false }
        val status = response.status.value
        val json = try {
            Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
        fun field(name: String) = (json?.get(name) as? JsonPrimitive)?.contentOrNull

        if (status !in 200..299) {
            throw TeamsAuthException(field("error") ?: "http_$status", field("error_description"), status)
        }
        val accessToken = field("access_token")
            ?: throw TeamsAuthException("bad_response", "в ответе нет access_token", status)
        val expiresIn = (json?.get("expires_in") as? JsonPrimitive)
            ?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
            ?: 3600 // как в Python: expires_in по умолчанию 3600
        return TeamsTokens(accessToken, field("refresh_token") ?: previousRefreshToken, now + expiresIn.seconds)
    }
}
