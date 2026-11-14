package app.gcaltolotus.auth

import android.app.PendingIntent
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.gcaltolotus.adapters.google.GoogleAccessToken
import app.gcaltolotus.app.GoogleTokens
import app.gcaltolotus.app.SecretStore
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/** Права Google Calendar, те же, что у настольной версии. */
val GOOGLE_SCOPES: List<String> = listOf(
    "https://www.googleapis.com/auth/calendar",
    "https://www.googleapis.com/auth/calendar.events",
)

/**
 * Нужен вход в Google с участием пользователя (согласие, выбор аккаунта): в фоне это
 * невозможно, пользователь должен открыть приложение и войти.
 */
class GoogleSignInRequiredException(message: String = "Требуется вход в Google: откройте приложение") :
    Exception(message)

/** Ошибка вызвана (в том числе через цепочку причин) [GoogleSignInRequiredException]. */
fun Throwable.isGoogleSignInRequired(): Boolean =
    generateSequence(this) { it.cause.takeIf { c -> c !== it } }.any { it is GoogleSignInRequiredException }

/**
 * Вход в Google через Authorization API Google Play services (клиент типа Android, ADR 0002).
 *
 * Client id в коде не нужен: Play services находит OAuth-клиент Android по имени пакета и
 * SHA-1 подписи. Он понадобился бы только для `requestOfflineAccess` (код для сервера), а
 * серверной части у приложения нет. Refresh-токена на телефоне нет: свежий access-токен
 * Play services выдаёт по повторному [authorize] без UI, пока согласие в силе.
 *
 * В [SecretStore] хранится только access-токен со сроком (наличие = «вход в Google выполнен»);
 * адрес аккаунта (не секрет, нужен для отзыва доступа при выходе) — в [prefs].
 */
class GoogleAuthorizer(
    context: Context,
    private val secrets: SecretStore,
    private val prefs: DataStore<Preferences>,
    private val clock: Clock = Clock.System,
) {
    private val client = Identity.getAuthorizationClient(context.applicationContext)

    private val request: AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(GOOGLE_SCOPES.map(::Scope)).build()

    /** Для [app.gcaltolotus.app.CalendarDeps.googleAccessToken]. */
    val accessTokenProvider: GoogleAccessToken = { forceRefresh -> accessToken(forceRefresh) }

    suspend fun isSignedIn(): Boolean = secrets.googleTokens() != null

    /** Адрес аккаунта Google, если известен. */
    suspend fun accountName(): String? = prefs.data.first()[ACCOUNT]

    /**
     * Действующий access-токен без UI (годится для фона). [forceRefresh] — сервер ответил 401:
     * сброшенный токен убирается из кеша Play services, берётся новый.
     *
     * @throws GoogleSignInRequiredException входа не было или нужно согласие пользователя.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String {
        val stored = secrets.googleTokens() ?: throw GoogleSignInRequiredException()
        if (!forceRefresh) {
            val expiresAt = stored.expiresAt
            if (expiresAt != null && expiresAt - REFRESH_MARGIN > clock.now()) return stored.accessToken
        } else {
            runCatching { client.clearToken(ClearTokenRequest.builder().setToken(stored.accessToken).build()).await() }
        }
        val result = try {
            client.authorize(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw GoogleSignInRequiredException("Вход в Google не удался: ${e.statusCode}")
        }
        if (result.hasResolution()) throw GoogleSignInRequiredException()
        return save(result)
    }

    /**
     * Начать вход: если согласие уже есть, токен сохраняется и возвращается `null`; иначе —
     * [PendingIntent] экрана согласия, который нужно запустить из Activity и передать результат
     * в [finishSignIn]. Удобная обёртка — [GoogleSignInLauncher].
     */
    suspend fun beginSignIn(): PendingIntent? {
        val result = client.authorize(request).await()
        if (result.hasResolution()) return checkNotNull(result.pendingIntent)
        save(result)
        return null
    }

    /**
     * Завершить вход по результату экрана согласия.
     *
     * @throws GoogleSignInRequiredException пользователь отказался или ответ без токена.
     */
    suspend fun finishSignIn(data: android.content.Intent?) {
        val result = try {
            client.getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            throw GoogleSignInRequiredException("Вход в Google отменён или не удался: ${e.statusCode}")
        }
        save(result)
    }

    /** Выйти: отозвать доступ (если аккаунт известен), сбросить токен и забыть его. */
    suspend fun signOut() {
        val stored = secrets.googleTokens()
        val account = accountName()
        if (stored != null) {
            runCatching { client.clearToken(ClearTokenRequest.builder().setToken(stored.accessToken).build()).await() }
        }
        if (account != null) {
            runCatching {
                client.revokeAccess(
                    RevokeAccessRequest.builder()
                        .setAccount(android.accounts.Account(account, GOOGLE_ACCOUNT_TYPE))
                        .setScopes(GOOGLE_SCOPES.map(::Scope))
                        .build(),
                ).await()
            }
        }
        secrets.setGoogleTokens(null)
        prefs.edit { it.remove(ACCOUNT) }
    }

    private suspend fun save(result: AuthorizationResult): String {
        val token = result.accessToken ?: throw GoogleSignInRequiredException("Google не выдал access-токен")
        // Срок Play services не сообщает; токены Google живут час, берём с запасом.
        secrets.setGoogleTokens(GoogleTokens(token, refreshToken = null, expiresAt = clock.now() + TOKEN_LIFETIME))
        @Suppress("DEPRECATION")
        val account = result.toGoogleSignInAccount()?.account?.name
        if (account != null) prefs.edit { it[ACCOUNT] = account }
        return token
    }

    private companion object {
        val ACCOUNT = stringPreferencesKey("google_account")
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        val TOKEN_LIFETIME = 50.minutes
        val REFRESH_MARGIN = 5.minutes
    }
}

/**
 * Вход в Google из Activity: показывает экран согласия Play services, если он нужен.
 * Создавать до того, как Activity стала STARTED (например, полем Activity), потому что
 * регистрирует ActivityResultLauncher.
 */
class GoogleSignInLauncher(activity: ComponentActivity, private val authorizer: GoogleAuthorizer) {
    private var pending: CompletableDeferred<androidx.activity.result.ActivityResult>? = null

    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            pending?.complete(result)
            pending = null
        }

    /**
     * Войти. Возвращает `true`, если вход выполнен, `false`, если пользователь закрыл экран согласия.
     *
     * @throws GoogleSignInRequiredException Google отказал во входе.
     */
    suspend fun signIn(): Boolean {
        val intent = authorizer.beginSignIn() ?: return true
        val deferred = CompletableDeferred<androidx.activity.result.ActivityResult>()
        pending = deferred
        launcher.launch(IntentSenderRequest.Builder(intent).build())
        val result = deferred.await()
        if (result.resultCode != android.app.Activity.RESULT_OK) return false
        authorizer.finishSignIn(result.data)
        return true
    }
}
