package app.gcaltolotus.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.lifecycle.withResumed
import app.gcaltolotus.BuildConfig
import app.gcaltolotus.adapters.teams.AuthorizationBrowser
import app.gcaltolotus.adapters.teams.AuthorizationReply
import app.gcaltolotus.adapters.teams.TeamsAuth
import app.gcaltolotus.adapters.teams.TeamsOAuthConfig
import app.gcaltolotus.adapters.teams.loopbackRedirectUri
import app.gcaltolotus.app.SecretStore
import app.gcaltolotus.app.teamsTokenStore
import io.ktor.client.HttpClient
import java.net.URI

/**
 * Регистрация Teams из сборки: публичный клиент (платформа «Mobile and desktop» в Azure,
 * redirect `http://localhost`, PKCE), только client id из Gradle-свойства `teams.clientId`.
 * Секрета в приложении нет. `null` — client id пуст, Teams «не настроен».
 */
fun teamsConfigFromBuild(clientId: String = BuildConfig.TEAMS_CLIENT_ID): TeamsOAuthConfig? =
    if (clientId.isBlank()) null else TeamsOAuthConfig(clientId = clientId)

/**
 * [AuthorizationBrowser] для телефона: на время входа поднимает [LoopbackServer] на 127.0.0.1
 * (для loopback-redirect без порта — на свободном порту, redirect `http://localhost:<порт>`;
 * если порт в redirect указан — на нём), открывает страницу входа Microsoft в Custom Tab и
 * возвращает query redirect.
 *
 * Жизненный цикл [activity] учитывается сам: если пользователь вернулся в приложение, не войдя,
 * ожидание отменяется ([ReturnWatch], [LoopbackFailure.CANCELLED]); обмен кода на токен ждёт,
 * пока экран снова виден.
 */
class CustomTabAuthorizationBrowser(private val activity: ComponentActivity) : AuthorizationBrowser {
    override suspend fun authorize(redirectUri: String, buildUrl: (String) -> String): AuthorizationReply {
        val base = URI(redirectUri)
        LoopbackServer.open(port = base.port.coerceAtLeast(0), path = base.path.ifEmpty { "/" }).use { s ->
            val actual = loopbackRedirectUri(redirectUri, s.port)
            val url = buildUrl(actual)
            val params = ReturnWatch(activity.lifecycle).cancelIfReturnedWithout(s::cancel) {
                s.awaitCallback { openCustomTab(activity, url) }
            }
            // Redirect приходит, пока на экране вкладка браузера, а приложение в фоне. Android 15+
            // в фоне отключает приложению сеть (blocked=APP_BACKGROUND), и обмен кода на токен
            // падает с «Unable to resolve host». Поэтому ждём, пока экран приложения снова виден;
            // код Microsoft живёт около 10 минут.
            activity.withResumed {}
            return AuthorizationReply(actual, params)
        }
    }
}

/** Открыть [url] в Custom Tab (или в браузере по умолчанию, если Custom Tabs не поддерживаются). */
fun openCustomTab(context: Context, url: String) {
    val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
    if (context !is Activity) intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.launchUrl(context, url.toUri())
}

/**
 * Вход и выход Teams для экрана аккаунтов (через [AndroidAccountActions]).
 *
 * @param config регистрация из сборки; `null` — Teams не настроен ([isConfigured] = false).
 */
class TeamsAccount(
    private val http: HttpClient,
    val config: TeamsOAuthConfig?,
    private val secrets: SecretStore,
) {
    val isConfigured: Boolean get() = config != null

    suspend fun isSignedIn(): Boolean = config != null && secrets.teamsTokens() != null

    /**
     * Вход через Custom Tab с экрана [activity]: на время входа открывается loopback-сервер на
     * свободном порту 127.0.0.1, redirect — `http://localhost:<порт>`.
     *
     * @throws IllegalStateException Teams не настроен в сборке.
     * @throws LoopbackSignInException время вышло или пользователь вернулся, не войдя.
     * @throws app.gcaltolotus.adapters.teams.TeamsAuthException ошибка OAuth.
     */
    suspend fun signIn(activity: ComponentActivity) {
        val c = checkNotNull(config) { "Teams не настроен: в сборке нет teams.clientId" }
        TeamsAuth(http, c, secrets.teamsTokenStore()).signIn(browser = CustomTabAuthorizationBrowser(activity))
    }

    /** Выход: забыть токены (сессия Microsoft в браузере остаётся, как и на настольной версии). */
    suspend fun signOut() {
        secrets.setTeamsTokens(null)
    }
}
