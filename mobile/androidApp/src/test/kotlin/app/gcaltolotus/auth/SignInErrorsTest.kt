package app.gcaltolotus.auth

import app.gcaltolotus.adapters.teams.TeamsAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignInErrorsTest {
    @Test
    fun cancelledBrowserSignInIsNotAnError() {
        assertNull(signInErrorMessage("Teams", LoopbackSignInException(LoopbackFailure.CANCELLED, "Вход отменён")))
    }

    @Test
    fun timeoutKeepsItsRussianText() {
        val e = LoopbackSignInException(LoopbackFailure.TIMEOUT, "Время входа истекло: повторите вход")
        assertEquals("Время входа истекло: повторите вход", signInErrorMessage("Teams", e))
    }

    @Test
    fun teamsOAuthErrorShowsCodeAndFirstLineOfDescription() {
        val e = TeamsAuthException("invalid_grant", "AADSTS70008: expired\\nTrace ID: 1".replace("\\n", "\n"), 400)
        assertEquals("Вход в Teams не удался: invalid_grant — AADSTS70008: expired", signInErrorMessage("Teams", e))
    }

    @Test
    fun googleAndOtherErrors() {
        assertEquals("Требуется вход в Google: откройте приложение", signInErrorMessage("Google", GoogleSignInRequiredException()))
        assertEquals(
            "Вход в Teams не удался: Teams не настроен",
            signInErrorMessage("Teams", IllegalStateException("Teams не настроен")),
        )
    }
}
