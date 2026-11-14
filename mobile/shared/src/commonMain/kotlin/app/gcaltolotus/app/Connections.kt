package app.gcaltolotus.app

import app.gcaltolotus.adapters.domino.DominoAuthException
import app.gcaltolotus.adapters.domino.DominoCalendar
import app.gcaltolotus.adapters.google.GoogleApiException
import app.gcaltolotus.adapters.google.GoogleCalendarAdapter
import app.gcaltolotus.adapters.teams.GraphException
import app.gcaltolotus.adapters.teams.TeamsAuth
import app.gcaltolotus.adapters.teams.TeamsAuthException
import app.gcaltolotus.adapters.teams.TeamsCalendar
import kotlin.coroutines.cancellation.CancellationException

/** Итог проверки подключения (экран настроек). Тексты — для пользователя, по-русски. */
sealed interface ConnectionCheck {
    /** Подключение работает; [details] — кто вошёл или какой календарь. */
    data class Ok(val details: String) : ConnectionCheck

    /** Сервер отверг вход: неверный пароль, истёкший или отозванный токен. */
    data class AuthFailed(val message: String) : ConnectionCheck

    /** Сервер не ответил или ответил ошибкой (сеть, таймаут, 5xx, неверный адрес). */
    data class Unreachable(val message: String) : ConnectionCheck

    /** Проверять нечего: нет входа или не заполнены настройки. В сеть не ходили. */
    data class NotConfigured(val message: String) : ConnectionCheck
}

/**
 * Проверки подключения для экрана настроек (`/api/test/<система>` веб-версии): по одному дешёвому
 * запросу с авторизацией к каждой системе. Ничего не синхронизирует.
 */
class ConnectionChecker(
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val deps: CalendarDeps,
) {
    /**
     * Domino с данными из формы: `GET /api/calendar` ([DominoCalendar.connect]). 401 —
     * [ConnectionCheck.AuthFailed] без повтора (повторы блокируют учётную запись).
     *
     * Пустой [password] — проверить сохранённый пароль, как веб-версия. Если Lotus уже выключен
     * после 401, сохранённый пароль не проверяется (к серверу не обращаемся); если сохранённый
     * пароль получил 401 сейчас — Lotus выключается, как при синхронизации. Введённый пароль ничего
     * не сохраняет и не выключает: сохраняет [saveDominoCredentials].
     */
    suspend fun checkDomino(url: String, user: String, password: String?): ConnectionCheck {
        if (url.isBlank() || user.isBlank()) return ConnectionCheck.NotConfigured("Укажите адрес сервера и пользователя Domino")
        val typed = password?.takeIf { it.isNotEmpty() }
        if (typed == null && settings.load().dominoDisabled) {
            return ConnectionCheck.AuthFailed("Сохранённый пароль Domino не подошёл (401). Введите пароль заново")
        }
        val secret = typed ?: secrets.dominoPassword()?.takeIf { it.isNotEmpty() }
            ?: return ConnectionCheck.NotConfigured("Введите пароль Domino")
        val calendar = DominoCalendar(deps.dominoHttp, url.trim(), user.trim(), secret)
        return try {
            val owner = calendar.connect().owner
            val who = listOfNotNull(owner?.displayName ?: owner?.distinguishedName, owner?.email?.let { "<$it>" })
            ConnectionCheck.Ok(if (who.isEmpty()) "Подключено к ${calendar.serverUrl}" else "Календарь: ${who.joinToString(" ")}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: DominoAuthException) {
            if (typed == null) settings.update { it.copy(dominoDisabled = true) }
            ConnectionCheck.AuthFailed("Domino: неверный логин или пароль (401)")
        } catch (e: Exception) {
            ConnectionCheck.Unreachable(e.text())
        }
    }

    /** Google: `GET calendars/primary` (после 401 токен один раз обновляется, как в адаптере). */
    suspend fun checkGoogle(): ConnectionCheck {
        val token = deps.googleAccessToken ?: return ConnectionCheck.NotConfigured("Google недоступен в этой сборке")
        secrets.googleTokens() ?: return ConnectionCheck.NotConfigured("Вход в Google не выполнен")
        return try {
            val primary = GoogleCalendarAdapter(deps.http, token, log = deps.log).primaryCalendar()
            ConnectionCheck.Ok("Google: ${primary.summary}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: GoogleApiException) {
            if (e.status == 401 || e.status == 403) {
                ConnectionCheck.AuthFailed("Google отклонил вход (HTTP ${e.status}): войдите заново")
            } else {
                ConnectionCheck.Unreachable(e.text())
            }
        } catch (e: Exception) {
            ConnectionCheck.Unreachable(e.text())
        }
    }

    /** Teams: `GET /me/calendar` (после 401 токен один раз обновляется, как в календаре). */
    suspend fun checkTeams(): ConnectionCheck {
        val config = deps.teamsConfig ?: return ConnectionCheck.NotConfigured("Teams недоступен в этой сборке")
        secrets.teamsTokens() ?: return ConnectionCheck.NotConfigured("Вход в Teams не выполнен")
        return try {
            val auth = TeamsAuth(deps.http, config, secrets.teamsTokenStore())
            val info = TeamsCalendar(deps.http, auth, deps.log).defaultCalendar()
            val who = listOfNotNull(info.ownerName, info.ownerAddress?.let { "<$it>" })
            ConnectionCheck.Ok(if (who.isEmpty()) "Teams: ${info.name ?: "календарь"}" else "Teams: ${who.joinToString(" ")}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: TeamsAuthException) {
            if (e.signInRequired || e.status in 400..499) {
                ConnectionCheck.AuthFailed("Teams: нужен повторный вход (${e.error})")
            } else {
                ConnectionCheck.Unreachable(e.text())
            }
        } catch (e: GraphException) {
            if (e.status == 401 || e.status == 403) {
                ConnectionCheck.AuthFailed("Teams отклонил вход (HTTP ${e.status}): войдите заново")
            } else {
                ConnectionCheck.Unreachable(e.text())
            }
        } catch (e: Exception) {
            ConnectionCheck.Unreachable(e.text())
        }
    }

    private fun Exception.text(): String = message ?: toString()
}
