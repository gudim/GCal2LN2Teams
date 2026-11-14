package app.gcaltolotus.app

import app.gcaltolotus.adapters.domino.DominoCalendar
import app.gcaltolotus.adapters.google.GoogleCalendarAdapter
import app.gcaltolotus.adapters.teams.TeamsCalendar
import app.gcaltolotus.adapters.teams.TeamsOAuthConfig
import app.gcaltolotus.adapters.teams.TeamsTokens
import app.gcaltolotus.sync.System
import app.gcaltolotus.sync.Window
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class AdapterCalendarSourceTest {
    private val requests = mutableListOf<String>()
    private val http = HttpClient(MockEngine { request ->
        requests += "${request.method.value} ${request.url} ${request.headers[HttpHeaders.Authorization]}"
        respond("""{"items": []}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    private val tokens = TeamsTokens("access", "refresh", Instant.parse("2030-01-01T00:00:00Z"))
    private val full = SyncSettings(dominoUrl = "https://mail.example", dominoUser = "user", googleCalendarId = "notes@group")
    private val all = System.ORDER.toSet()

    private suspend fun secrets(withGoogle: Boolean = true, withTeams: Boolean = true, withDomino: Boolean = true) =
        InMemorySecretStore().apply {
            if (withGoogle) setGoogleTokens(GoogleTokens("g-access", "g-refresh", null))
            if (withTeams) setTeamsTokens(tokens)
            if (withDomino) setDominoPassword("secret")
        }

    private fun source(secrets: SecretStore, teamsConfig: TeamsOAuthConfig? = TeamsOAuthConfig("id", "secret")) =
        AdapterCalendarSource(secrets, CalendarDeps(http, teamsConfig = teamsConfig, googleAccessToken = { "g-access" }))

    @Test
    fun buildsAllThreeInCanonicalOrder() = runTest {
        val opened = source(secrets()).open(full, all)
        assertEquals(System.ORDER, opened.calendars.keys.toList())
        assertIs<GoogleCalendarAdapter>(opened.calendars[System.GOOGLE])
        assertEquals("notes@group", (opened.calendars[System.GOOGLE] as GoogleCalendarAdapter).calendarId)
        assertIs<DominoCalendar>(opened.calendars[System.DOMINO])
        assertIs<TeamsCalendar>(opened.calendars[System.TEAMS])
        assertTrue(opened.skipped.isEmpty())
        assertTrue(requests.isEmpty()) // сборка календарей в сеть не ходит
    }

    @Test
    fun onlyRequestedSystemsAreBuilt() = runTest {
        val opened = source(secrets()).open(full, setOf(System.TEAMS))
        assertEquals(listOf(System.TEAMS), opened.calendars.keys.toList())
        assertTrue(opened.skipped.isEmpty())
    }

    @Test
    fun systemsWithoutCredentialsAreSkipped() = runTest {
        val opened = source(secrets(withGoogle = false, withTeams = false, withDomino = false)).open(full, all)
        assertTrue(opened.calendars.isEmpty())
        assertEquals(System.ORDER.map { SkippedSystem(it, SkipReason.NOT_CONFIGURED) }, opened.skipped)
    }

    @Test
    fun dominoNeedsUrlUserAndPassword() = runTest {
        val opened = source(secrets()).open(full.copy(dominoUser = ""), setOf(System.DOMINO))
        assertEquals(listOf(SkippedSystem(System.DOMINO, SkipReason.NOT_CONFIGURED)), opened.skipped)
    }

    @Test
    fun teamsNeedsAppRegistration() = runTest {
        val opened = source(secrets(), teamsConfig = null).open(full, setOf(System.TEAMS))
        assertEquals(listOf(SkippedSystem(System.TEAMS, SkipReason.NOT_CONFIGURED)), opened.skipped)
    }

    @Test
    fun disabledLotusIsSkippedAndReported() = runTest {
        val opened = source(secrets()).open(full.copy(dominoDisabled = true), all)
        assertEquals(listOf(System.GOOGLE, System.TEAMS), opened.calendars.keys.toList())
        assertEquals(listOf(SkippedSystem(System.DOMINO, SkipReason.DOMINO_DISABLED)), opened.skipped)
    }

    @Test
    fun googleUsesInjectedTokenProvider() = runTest {
        val google = source(secrets()).open(full, setOf(System.GOOGLE)).calendars.getValue(System.GOOGLE)
        google.list(Window(Instant.parse("2026-11-12T00:00:00Z"), Instant.parse("2026-11-13T00:00:00Z")))
        assertTrue(requests.single().endsWith("Bearer g-access"), requests.single())
    }

    @Test
    fun teamsTokensLiveInSecretStore() = runTest {
        val secrets = InMemorySecretStore()
        val store = secrets.teamsTokenStore()
        store.save(tokens)
        assertEquals(tokens, secrets.teamsTokens())
        store.clear()
        assertEquals(null, secrets.teamsTokens())
    }
}
