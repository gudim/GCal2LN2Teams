package app.gcaltolotus.adapters.teams

import io.ktor.http.Url
import io.ktor.http.parseQueryString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

/**
 * OAuth-слой Teams на MockEngine: только фальшивые id, секрет и токены. Большинство тестов —
 * конфиденциальный клиент с фиксированным redirect (как настольная версия); публичный клиент
 * телефона — тесты `public*`.
 */
class TeamsAuthTest {
    private val now = Instant.parse("2026-11-14T10:00:00Z")
    private val clock = FakeClock(now)
    private val config = TeamsOAuthConfig(
        clientId = "fake-client-id",
        clientSecret = "fake-secret",
        redirectUri = TEAMS_DESKTOP_REDIRECT_URI,
    )
    private val publicConfig = TeamsOAuthConfig(clientId = "fake-client-id")
    private val tokenUrl = "https://login.microsoftonline.com/organizations/oauth2/v2.0/token"

    private fun auth(
        http: MockHttp,
        store: TeamsTokenStore = InMemoryTeamsTokenStore(),
        config: TeamsOAuthConfig = this.config,
    ) = TeamsAuth(http.client, config, store, clock)

    /** Браузер с фиксированным redirect: отвечает [params] плюс `state` из URL. */
    private fun fixedBrowser(params: Map<String, String> = mapOf("code" to "the-code"), opened: (String) -> Unit = {}) =
        AuthorizationBrowser { redirectUri, buildUrl ->
            val url = buildUrl(redirectUri)
            opened(url)
            AuthorizationReply(redirectUri, params + ("state" to Url(url).parameters["state"]!!))
        }

    private fun tokenReply(access: String, refresh: String? = null, expiresIn: Int = 3600) = ok(
        buildString {
            append("""{"token_type":"Bearer","scope":"Calendars.ReadWrite User.Read","expires_in":$expiresIn,""")
            append(""""ext_expires_in":$expiresIn,"access_token":"$access"""")
            if (refresh != null) append(""","refresh_token":"$refresh"""")
            append("}")
        },
    )

    private fun form(request: Recorded) = parseQueryString(request.body)

    @Test
    fun defaultsArePublicLoopbackClient() {
        assertNull(publicConfig.clientSecret)
        assertEquals("http://localhost", publicConfig.redirectUri)
        assertEquals("https://login.microsoftonline.com/organizations", publicConfig.authority)
        assertEquals(listOf("Calendars.ReadWrite", "User.Read", "offline_access"), publicConfig.scopes)
    }

    @Test
    fun desktopRedirectAndSecretAreHidden() {
        assertEquals("http://localhost:8000/api/teams/callback", config.redirectUri)
        assertFalse("fake-secret" in config.toString())
        assertTrue("clientSecret=null" in publicConfig.toString())
    }

    @Test
    fun authorizeUrlCarriesClientRedirectScopesStateAndChallenge() {
        val url = Url(auth(MockHttp()).authorizeUrl(state = "st-123", codeChallenge = "the-challenge"))

        assertEquals("login.microsoftonline.com", url.host)
        assertEquals("/organizations/oauth2/v2.0/authorize", url.encodedPath)
        val p = url.parameters
        assertEquals("fake-client-id", p["client_id"])
        assertEquals("code", p["response_type"])
        assertEquals("query", p["response_mode"])
        assertEquals("http://localhost:8000/api/teams/callback", p["redirect_uri"])
        assertEquals("Calendars.ReadWrite User.Read offline_access", p["scope"])
        assertEquals("st-123", p["state"])
        assertEquals("the-challenge", p["code_challenge"])
        assertEquals("S256", p["code_challenge_method"])
        assertNull(p["client_secret"])
    }

    @Test
    fun authorizeUrlUsesTheGivenRedirect() {
        val url = Url(
            auth(MockHttp(), config = publicConfig)
                .authorizeUrl("st", "ch", redirectUri = "http://localhost:53124"),
        )
        assertEquals("http://localhost:53124", url.parameters["redirect_uri"])
    }

    @Test
    fun exchangeCodePostsFormAndStoresTokens() = runTest {
        val http = MockHttp(tokenReply("AT1", "RT1", expiresIn = 3599))
        val store = InMemoryTeamsTokenStore()

        val tokens = auth(http, store).exchangeCode("the-code", codeVerifier = "the-verifier")

        val request = http.requests.single()
        assertEquals("POST", request.method.value)
        assertEquals(tokenUrl, request.url.toString())
        assertTrue(request.contentType.orEmpty().startsWith("application/x-www-form-urlencoded"))
        val form = form(request)
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the-code", form["code"])
        assertEquals("http://localhost:8000/api/teams/callback", form["redirect_uri"])
        assertEquals("fake-client-id", form["client_id"])
        assertEquals("fake-secret", form["client_secret"])
        assertEquals("the-verifier", form["code_verifier"])
        assertEquals("Calendars.ReadWrite User.Read offline_access", form["scope"])
        assertEquals(TeamsTokens("AT1", "RT1", now + 3599.seconds), tokens)
        assertEquals(tokens, store.load())
    }

    @Test
    fun publicExchangeSendsVerifierAndNoSecret() = runTest {
        val http = MockHttp(tokenReply("AT1", "RT1"))

        auth(http, config = publicConfig).exchangeCode("the-code", "the-verifier", "http://localhost:53124")

        val form = form(http.requests.single())
        assertEquals("fake-client-id", form["client_id"])
        assertEquals("the-verifier", form["code_verifier"])
        assertEquals("http://localhost:53124", form["redirect_uri"])
        assertFalse("client_secret" in form.names())
    }

    @Test
    fun publicRefreshSendsNoSecret() = runTest {
        val http = MockHttp(tokenReply("AT2", "RT2"))
        val store = InMemoryTeamsTokenStore(TeamsTokens("AT1", "RT1", now - 1.seconds))

        assertEquals("AT2", auth(http, store, publicConfig).accessToken())

        val form = form(http.requests.single())
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("fake-client-id", form["client_id"])
        assertFalse("client_secret" in form.names())
        assertNull(form["code_verifier"])
    }

    @Test
    fun validStoredTokenIsUsedWithoutHttp() = runTest {
        val http = MockHttp()
        val store = InMemoryTeamsTokenStore(TeamsTokens("AT", "RT", now + 30.minutes))

        assertEquals("AT", auth(http, store).accessToken())
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun expiredTokenIsRefreshedAndRotatedRefreshTokenStored() = runTest {
        val http = MockHttp(tokenReply("AT2", "RT2", expiresIn = 3600))
        val store = InMemoryTeamsTokenStore(TeamsTokens("AT1", "RT1", now - 1.seconds))

        assertEquals("AT2", auth(http, store).accessToken())

        val form = form(http.requests.single())
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("RT1", form["refresh_token"])
        assertEquals("fake-client-id", form["client_id"])
        assertEquals("fake-secret", form["client_secret"])
        assertEquals("Calendars.ReadWrite User.Read offline_access", form["scope"])
        assertNull(form["code"])
        assertEquals(TeamsTokens("AT2", "RT2", now + 3600.seconds), store.load())
    }

    @Test
    fun tokenAboutToExpireIsRefreshedEarly() = runTest {
        val http = MockHttp(tokenReply("AT2", "RT2"))
        val store = InMemoryTeamsTokenStore(TeamsTokens("AT1", "RT1", now + 30.seconds))

        assertEquals("AT2", auth(http, store).accessToken())
    }

    @Test
    fun refreshWithoutNewRefreshTokenKeepsTheOldOne() = runTest {
        val http = MockHttp(tokenReply("AT2", refresh = null))
        val store = InMemoryTeamsTokenStore(TeamsTokens("AT1", "RT1", now + 30.minutes))

        assertEquals("AT2", auth(http, store).refresh())

        assertEquals(TeamsTokens("AT2", "RT1", now + 3600.seconds), store.load())
    }

    @Test
    fun forcedRefreshUsesTheLatestRotatedToken() = runTest {
        val http = MockHttp(tokenReply("AT2", "RT2"), tokenReply("AT3", "RT3"))
        val store = InMemoryTeamsTokenStore(TeamsTokens("AT1", "RT1", now + 30.minutes))
        val auth = auth(http, store)

        auth.refresh()
        auth.refresh()

        assertEquals(listOf("RT1", "RT2"), http.requests.map { form(it)["refresh_token"] })
        assertEquals("RT3", store.load()?.refreshToken)
    }

    @Test
    fun concurrentCallersShareOneRefresh() = runTest {
        val http = MockHttp(tokenReply("AT2", "RT2"))
        val store = InMemoryTeamsTokenStore(TeamsTokens("AT1", "RT1", now - 1.seconds))
        val auth = auth(http, store)

        val tokens = List(3) { async { auth.accessToken() } }.awaitAll()

        assertEquals(listOf("AT2", "AT2", "AT2"), tokens)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun invalidGrantMeansSignInAgainAndKeepsStore() = runTest {
        val http = MockHttp(
            Reply(
                400,
                """{"error":"invalid_grant","error_description":"AADSTS70008: The refresh token has expired.","error_codes":[70008]}""",
            ),
        )
        val stored = TeamsTokens("AT1", "RT1", now - 1.seconds)
        val store = InMemoryTeamsTokenStore(stored)

        val error = assertFailsWith<TeamsAuthException> { auth(http, store).accessToken() }

        assertEquals("invalid_grant", error.error)
        assertTrue(error.description!!.startsWith("AADSTS70008"))
        assertEquals(400, error.status)
        assertTrue(error.signInRequired)
        assertEquals(stored, store.load())
    }

    @Test
    fun serverErrorIsNotSignInRequired() = runTest {
        val http = MockHttp(Reply(503, "<html>Service Unavailable</html>"))
        val store = InMemoryTeamsTokenStore(TeamsTokens("AT1", "RT1", now - 1.seconds))

        val error = assertFailsWith<TeamsAuthException> { auth(http, store).accessToken() }

        assertEquals(503, error.status)
        assertFalse(error.signInRequired)
    }

    @Test
    fun responseWithoutAccessTokenIsAnError() = runTest {
        val http = MockHttp(ok("""{"token_type":"Bearer"}"""))

        val error = assertFailsWith<TeamsAuthException> { auth(http).exchangeCode("c", "v") }
        assertFalse(error.signInRequired)
    }

    @Test
    fun noTokensMeansSignInRequired() = runTest {
        val http = MockHttp()

        val error = assertFailsWith<TeamsAuthException> { auth(http).accessToken() }

        assertTrue(error.signInRequired)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun noRefreshTokenMeansSignInRequired() = runTest {
        val store = InMemoryTeamsTokenStore(TeamsTokens("AT1", null, now - 1.seconds))

        val error = assertFailsWith<TeamsAuthException> { auth(MockHttp(), store).accessToken() }
        assertTrue(error.signInRequired)
    }

    @Test
    fun signInOpensUrlChecksStateAndExchangesCode() = runTest {
        val http = MockHttp(tokenReply("AT1", "RT1"))
        val store = InMemoryTeamsTokenStore()
        var opened: String? = null

        auth(http, store).signIn(
            browser = fixedBrowser(mapOf("code" to "the-code", "session_state" to "s")) { opened = it },
        )

        val url = Url(opened!!)
        assertTrue(url.parameters["state"]!!.length >= 16)
        val form = form(http.requests.single())
        assertEquals("the-code", form["code"])
        assertEquals("http://localhost:8000/api/teams/callback", form["redirect_uri"])
        assertEquals("fake-secret", form["client_secret"])
        assertEquals(url.parameters["code_challenge"], pkceChallenge(form["code_verifier"]!!))
        assertEquals("AT1", store.load()?.accessToken)
    }

    @Test
    fun publicSignInUsesBrowsersLoopbackPortAndPkce() = runTest {
        val http = MockHttp(tokenReply("AT1", "RT1"))
        val store = InMemoryTeamsTokenStore()
        var base: String? = null
        var opened: String? = null

        auth(http, store, publicConfig).signIn { redirectUri, buildUrl ->
            base = redirectUri
            val actual = loopbackRedirectUri(redirectUri, 53124)
            opened = buildUrl(actual)
            AuthorizationReply(actual, mapOf("code" to "the-code", "state" to Url(opened!!).parameters["state"]!!))
        }

        assertEquals("http://localhost", base)
        val p = Url(opened!!).parameters
        assertEquals("http://localhost:53124", p["redirect_uri"])
        assertEquals("S256", p["code_challenge_method"])
        val form = form(http.requests.single())
        assertEquals("http://localhost:53124", form["redirect_uri"])
        assertEquals(p["code_challenge"], pkceChallenge(form["code_verifier"]!!))
        assertFalse("client_secret" in form.names())
        assertEquals("AT1", store.load()?.accessToken)
    }

    @Test
    fun signInRejectsRedirectThatWasNotSent() = runTest {
        val http = MockHttp()

        val error = assertFailsWith<TeamsAuthException> {
            auth(http, config = publicConfig).signIn { redirectUri, buildUrl ->
                val url = buildUrl("http://localhost:1111")
                AuthorizationReply("http://localhost:2222", mapOf("code" to "c", "state" to Url(url).parameters["state"]!!))
            }
        }

        assertEquals("redirect_mismatch", error.error)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun signInStatesAreRandom() {
        val auth = auth(MockHttp())
        assertNotEquals(newOAuthState(), newOAuthState())
        assertTrue(auth.authorizeUrl(newOAuthState(), "ch").contains("state="))
    }

    @Test
    fun signInRejectsWrongState() = runTest {
        val http = MockHttp()

        val error = assertFailsWith<TeamsAuthException> {
            auth(http).signIn { redirectUri, buildUrl ->
                buildUrl(redirectUri)
                AuthorizationReply(redirectUri, mapOf("code" to "c", "state" to "forged"))
            }
        }

        assertEquals("state_mismatch", error.error)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun signInReportsErrorFromRedirect() = runTest {
        val http = MockHttp()

        val error = assertFailsWith<TeamsAuthException> {
            auth(http).signIn(
                browser = fixedBrowser(
                    mapOf("error" to "access_denied", "error_description" to "The user has denied access"),
                ),
            )
        }

        assertEquals("access_denied", error.error)
        assertEquals("The user has denied access", error.description)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun signOutClearsStore() = runTest {
        val store = InMemoryTeamsTokenStore(TeamsTokens("AT", "RT", now))

        auth(MockHttp(), store).signOut()

        assertNull(store.load())
    }

    @Test
    fun calendarRefreshesThroughAuthOn401() = runTest {
        val http = MockHttp(Reply(401), tokenReply("AT2", "RT2"), Reply(204))
        val store = InMemoryTeamsTokenStore(TeamsTokens("AT1", "RT1", now + 30.minutes))

        TeamsCalendar(http.client, auth(http, store)).delete("x")

        val (first, refresh, retry) = http.requests
        assertEquals("Bearer AT1", first.headers["Authorization"])
        assertEquals(tokenUrl, refresh.url.toString())
        assertNull(refresh.headers["Authorization"])
        assertEquals("Bearer AT2", retry.headers["Authorization"])
        assertEquals("RT2", store.load()?.refreshToken)
    }

    @Test
    fun tokensToStringHidesSecrets() {
        val text = TeamsTokens("secret-access", "secret-refresh", now).toString()
        assertFalse("secret-access" in text || "secret-refresh" in text)
    }
}
