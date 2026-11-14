package app.gcaltolotus.auth

import androidx.activity.ComponentActivity
import app.gcaltolotus.adapters.teams.TeamsAuthException
import app.gcaltolotus.ui.AccountActions
import app.gcaltolotus.ui.AccountStatus
import com.google.android.gms.common.api.ApiException
import kotlin.coroutines.cancellation.CancellationException

/** Ошибка входа с текстом для пользователя (по-русски); показывается экраном подключений как есть. */
class SignInException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Понятный пользователю текст ошибки входа; `null` — это отмена входа (не ошибка).
 * Исключения без своего текста получают общий.
 */
fun signInErrorMessage(system: String, error: Throwable): String? = when (error) {
    is LoopbackSignInException ->
        if (error.failure == LoopbackFailure.CANCELLED) null else error.message ?: "Вход в $system не выполнен"
    is TeamsAuthException -> buildString {
        append("Вход в Teams не удался: ")
        append(error.error)
        error.description?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
    }
    is GoogleSignInRequiredException -> error.message ?: "Вход в Google не выполнен"
    is ApiException -> "Вход в Google не удался: код ${error.statusCode}"
    else -> "Вход в $system не удался: ${error.message ?: error::class.simpleName}"
}

/**
 * [AccountActions] на Android: Google — [GoogleSignInLauncher] (экран согласия Play services),
 * Teams — [TeamsAccount] (Custom Tab и loopback-redirect; если пользователь вернулся, не войдя,
 * вход возвращает [AccountStatus.SignedOut]). Создавать в onCreate Activity.
 */
class AndroidAccountActions(
    private val activity: ComponentActivity,
    private val google: GoogleAuthorizer,
    private val googleSignIn: GoogleSignInLauncher,
    private val teams: TeamsAccount,
) : AccountActions {
    override suspend fun googleStatus(): AccountStatus =
        if (google.isSignedIn()) AccountStatus(signedIn = true, account = google.accountName()) else AccountStatus.SignedOut

    override suspend fun signInGoogle(): AccountStatus {
        val signedIn = readable("Google") { googleSignIn.signIn() } ?: false
        return if (signedIn) googleStatus() else AccountStatus.SignedOut
    }

    override suspend fun signOutGoogle() = google.signOut()

    override suspend fun teamsStatus(): AccountStatus =
        if (teams.isSignedIn()) AccountStatus(signedIn = true) else AccountStatus.SignedOut

    override suspend fun signInTeams(): AccountStatus {
        readable("Teams") { teams.signIn(activity) } ?: return AccountStatus.SignedOut
        return teamsStatus()
    }

    override suspend fun signOutTeams() = teams.signOut()

    /** Выполнить вход; отмена — `null`, ошибка — [SignInException] с понятным текстом. */
    private suspend fun <T> readable(system: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val message = signInErrorMessage(system, e) ?: return null
        throw SignInException(message, e)
    }
}
