package app.gcaltolotus.app

import app.gcaltolotus.adapters.teams.TeamsOAuthConfig
import app.gcaltolotus.adapters.teams.TeamsTokens
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

/** Проверки подключения для экрана настроек: один запрос, 401 — без повтора. */
class ConnectionCheckerTest {
    /** Ответ на запрос: статус и тело, либо ошибка сети (`status = null`). */
    private class Answer(val status: Int?, val body: String = "{}")

    private val requests = mutableListOf<String>()
    private val answers = mutableMapOf<String, Answer>()

    private val http = HttpClient(MockEngine { request ->
        val url = request.url.toString().substringBefore('?')
        requests += "${request.method.value} $url ${request.headers[HttpHeaders.Authorization]}"
        val answer = answers[url] ?: Answer(404)
        if (answer.status == null) throw IOException("Unable to resolve host")
        respond(answer.body, HttpStatusCode.fromValue(answer.status), headersOf(HttpHeaders.ContentType, "application/json"))
    })

    private val settings = InMemorySettingsStore(SyncSettings(dominoUrl = "https://mail.example", dominoUser = "user"))
    private val secrets = InMemorySecretStore()
    private val googleTokenCalls = mutableListOf<Boolean>()

    private fun checker(teams: Boolean = true, google: Boolean = true) = ConnectionChecker(
        settings,
        secrets,
        CalendarDeps(
            http,
            teamsConfig = if (teams) TeamsOAuthConfig("id", "secret") else null,
            googleAccessToken = if (google) { force -> googleTokenCalls += force; "g-access" } else null,
        ),
    )

    // --- Domino ---

    private val calendarUrl = "https://domino.example/api/calendar"
    private val calendarJson = """
        {"calendars": [{"owner": {"displayName": "Ivan Petrov", "email": "IPetrov@example.com"},
          "links": [{"rel": "events", "href": "/mail/user.nsf/api/calendar/events"}]}]}
    """.trimIndent()

    @Test
    fun dominoOkShowsOwner() = runTest {
        answers[calendarUrl] = Answer(200, calendarJson)
        val result = checker().checkDomino("https://domino.example/", "user", "pw")
        val ok = assertIs<ConnectionCheck.Ok>(result)
        assertTrue("Ivan Petrov" in ok.details, ok.details)
        assertEquals(1, requests.size)
        assertTrue(requests.single().startsWith("GET $calendarUrl Basic "), requests.single())
    }

    @Test
    fun dominoUnauthorizedIsAuthFailedWithoutRetry() = runTest {
        answers[calendarUrl] = Answer(401)
        assertIs<ConnectionCheck.AuthFailed>(checker().checkDomino("https://domino.example", "user", "typed"))
        assertEquals(1, requests.size)
        // введённый в форму пароль не сохранён — сохранённый не трогаем
        assertFalse(settings.load().dominoDisabled)
    }

    @Test
    fun dominoUnreachableAndServerErrors() = runTest {
        answers[calendarUrl] = Answer(null)
        val net = assertIs<ConnectionCheck.Unreachable>(checker().checkDomino("https://domino.example", "user", "pw"))
        assertTrue("resolve" in net.message, net.message)
        answers[calendarUrl] = Answer(503, "down")
        assertIs<ConnectionCheck.Unreachable>(checker().checkDomino("https://domino.example", "user", "pw"))
    }

    @Test
    fun dominoBlankPasswordUsesSavedOneAnd401DisablesLotus() = runTest {
        secrets.setDominoPassword("saved")
        answers[calendarUrl] = Answer(401)
        assertIs<ConnectionCheck.AuthFailed>(checker().checkDomino("https://domino.example", "user", ""))
        // сохранённый пароль неверен: Lotus выключается, как после 401 при синхронизации
        assertTrue(settings.load().dominoDisabled)
    }

    @Test
    fun dominoDisabledWithSavedPasswordIsNotContacted() = runTest {
        secrets.setDominoPassword("saved")
        settings.save(settings.load().copy(dominoDisabled = true))
        assertIs<ConnectionCheck.AuthFailed>(checker().checkDomino("https://domino.example", "user", null))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun dominoWithoutSettingsIsNotConfigured() = runTest {
        assertIs<ConnectionCheck.NotConfigured>(checker().checkDomino(" ", "user", "pw"))
        assertIs<ConnectionCheck.NotConfigured>(checker().checkDomino("https://domino.example", "user", null))
        assertTrue(requests.isEmpty())
    }

    // --- Google ---

    private val googlePrimary = "https://www.googleapis.com/calendar/v3/calendars/primary"

    @Test
    fun googleOkShowsPrimaryCalendar() = runTest {
        secrets.setGoogleTokens(GoogleTokens("g-access", "g-refresh", null))
        answers[googlePrimary] = Answer(200, """{"id": "me@example.com", "summary": "me@example.com", "timeZone": "Europe/Moscow"}""")
        val ok = assertIs<ConnectionCheck.Ok>(checker().checkGoogle())
        assertTrue("me@example.com" in ok.details, ok.details)
        assertEquals(listOf("GET $googlePrimary Bearer g-access"), requests)
    }

    @Test
    fun googleUnauthorizedIsAuthFailed() = runTest {
        secrets.setGoogleTokens(GoogleTokens("g-access", "g-refresh", null))
        answers[googlePrimary] = Answer(401)
        assertIs<ConnectionCheck.AuthFailed>(checker().checkGoogle())
        assertEquals(listOf(false, true), googleTokenCalls) // одно обновление токена, как в адаптере
    }

    @Test
    fun googleNetworkErrorIsUnreachable() = runTest {
        secrets.setGoogleTokens(GoogleTokens("g-access", "g-refresh", null))
        answers[googlePrimary] = Answer(null)
        assertIs<ConnectionCheck.Unreachable>(checker().checkGoogle())
    }

    @Test
    fun googleWithoutSignInIsNotConfigured() = runTest {
        assertIs<ConnectionCheck.NotConfigured>(checker().checkGoogle())
        secrets.setGoogleTokens(GoogleTokens("g-access", "g-refresh", null))
        assertIs<ConnectionCheck.NotConfigured>(checker(google = false).checkGoogle())
        assertTrue(requests.isEmpty())
    }

    // --- Teams ---

    private val teamsCalendar = "https://graph.microsoft.com/v1.0/me/calendar"
    private val teamsToken = "https://login.microsoftonline.com/organizations/oauth2/v2.0/token"

    private suspend fun signedInTeams() =
        secrets.setTeamsTokens(TeamsTokens("t-access", "t-refresh", Instant.parse("2030-01-01T00:00:00Z")))

    @Test
    fun teamsOkShowsOwner() = runTest {
        signedInTeams()
        answers[teamsCalendar] = Answer(200, """{"id": "A", "name": "Календарь", "owner": {"name": "Иван", "address": "ivan@example.com"}}""")
        val ok = assertIs<ConnectionCheck.Ok>(checker().checkTeams())
        assertTrue("ivan@example.com" in ok.details, ok.details)
        assertEquals(listOf("GET $teamsCalendar Bearer t-access"), requests)
    }

    @Test
    fun teamsRejectedRefreshIsAuthFailed() = runTest {
        signedInTeams()
        answers[teamsCalendar] = Answer(401)
        answers[teamsToken] = Answer(400, """{"error": "invalid_grant", "error_description": "expired"}""")
        assertIs<ConnectionCheck.AuthFailed>(checker().checkTeams())
    }

    @Test
    fun teamsServerErrorIsUnreachable() = runTest {
        signedInTeams()
        answers[teamsCalendar] = Answer(503)
        assertIs<ConnectionCheck.Unreachable>(checker().checkTeams())
    }

    @Test
    fun teamsWithoutSignInIsNotConfigured() = runTest {
        assertIs<ConnectionCheck.NotConfigured>(checker().checkTeams())
        signedInTeams()
        assertIs<ConnectionCheck.NotConfigured>(checker(teams = false).checkTeams())
        assertTrue(requests.isEmpty())
    }
}
