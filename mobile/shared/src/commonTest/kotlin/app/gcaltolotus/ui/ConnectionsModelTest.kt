package app.gcaltolotus.ui

import app.gcaltolotus.app.InMemorySecretStore
import app.gcaltolotus.app.InMemorySettingsStore
import app.gcaltolotus.app.SyncSettings
import app.gcaltolotus.sync.System
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

/** Вход-подделка: [nextSignIn] — что вернёт вход, [failWith] — чем он упадёт. */
class FakeAccounts : AccountActions {
    var google = AccountStatus.SignedOut
    var teams = AccountStatus.SignedOut
    var nextSignIn = AccountStatus(true, "me@example.com")
    var failWith: Exception? = null

    override suspend fun googleStatus() = google

    override suspend fun signInGoogle(): AccountStatus {
        failWith?.let { throw it }
        return nextSignIn.also { google = it }
    }

    override suspend fun signOutGoogle() {
        google = AccountStatus.SignedOut
    }

    override suspend fun teamsStatus() = teams

    override suspend fun signInTeams(): AccountStatus {
        failWith?.let { throw it }
        return nextSignIn.also { teams = it }
    }

    override suspend fun signOutTeams() {
        teams = AccountStatus.SignedOut
    }
}

class ConnectionsModelTest {
    private val settings = InMemorySettingsStore(SyncSettings(dominoUrl = "https://d", dominoUser = "Иван Петров/ORG", dominoDisabled = true))
    private val secrets = InMemorySecretStore()
    private val accounts = FakeAccounts()
    private val checked = mutableListOf<System>()
    private var checkResult: CheckResult = CheckResult.Ok("Календарь")

    private fun CoroutineScope.model() = ConnectionsModel(this, settings, secrets, accounts, ConnectionChecks {
        checked += it
        checkResult
    })

    @Test
    fun loadShowsAccountsAndDominoState() = runTest {
        secrets.setDominoPassword("old")
        accounts.google = AccountStatus(true, "me@example.com")
        val m = model()
        m.load().join()

        val s = m.state.value
        assertTrue(s.loaded)
        assertEquals("me@example.com", s.google.account)
        assertFalse(s.teams.signedIn)
        assertEquals("https://d", s.dominoUrl)
        assertTrue(s.dominoPasswordSaved)
        assertTrue(s.dominoDisabled)
    }

    @Test
    fun newDominoPasswordReenablesLotus() = runTest {
        val m = model()
        m.load().join()
        m.editDominoPassword("new")
        m.saveDomino().join()

        val s = m.state.value
        assertFalse(s.dominoDisabled)
        assertEquals("", s.dominoPassword)
        assertTrue(s.dominoPasswordSaved)
        assertEquals(ConnectionNotice.Saved, s.notices[System.DOMINO])
        assertEquals("new", secrets.dominoPassword())
        assertFalse(settings.load().dominoDisabled)
    }

    @Test
    fun savingWithoutPasswordKeepsLotusDisabled() = runTest {
        val m = model()
        m.load().join()
        m.editDominoUrl(" https://other ")
        m.saveDomino().join()
        assertTrue(m.state.value.dominoDisabled)
        assertEquals("https://other", settings.load().dominoUrl)
    }

    @Test
    fun incompleteDominoIsNotSaved() = runTest {
        val m = model()
        m.load().join()
        m.editDominoUser("  ")
        m.saveDomino().join()
        assertEquals(ConnectionNotice.DominoIncomplete, m.state.value.notices[System.DOMINO])
        assertEquals("Иван Петров/ORG", settings.load().dominoUser)
    }

    @Test
    fun checkingDominoSavesUnsavedFieldsFirst() = runTest {
        checkResult = CheckResult.AuthFailed("401")
        val m = model()
        m.load().join()
        m.editDominoPassword("p")
        m.check(System.DOMINO).join()

        assertEquals("p", secrets.dominoPassword())
        assertEquals(listOf(System.DOMINO), checked)
        assertEquals(ConnectionNotice.Checked(CheckResult.AuthFailed("401")), m.state.value.notices[System.DOMINO])
        assertTrue(m.state.value.busy.isEmpty())
    }

    @Test
    fun signInAndOut() = runTest {
        val m = model()
        m.signInTeams().join()
        assertTrue(m.state.value.teams.signedIn)
        assertEquals(null, m.state.value.notices[System.TEAMS])
        m.signOutTeams().join()
        assertFalse(m.state.value.teams.signedIn)
    }

    @Test
    fun cancelledAndFailedSignIn() = runTest {
        val m = model()
        accounts.nextSignIn = AccountStatus.SignedOut
        m.signInGoogle().join()
        assertEquals(ConnectionNotice.SignInCancelled, m.state.value.notices[System.GOOGLE])

        accounts.failWith = IllegalStateException("Нет сети")
        m.signInGoogle().join()
        assertEquals(ConnectionNotice.Failed("Нет сети"), m.state.value.notices[System.GOOGLE])
        assertFalse(m.state.value.google.signedIn)
    }
}
