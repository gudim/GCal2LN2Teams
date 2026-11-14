package app.gcaltolotus.ui

import app.gcaltolotus.app.PRIMARY_CALENDAR_ID
import app.gcaltolotus.app.SecretStore
import app.gcaltolotus.app.SettingsStore
import app.gcaltolotus.app.saveDominoCredentials
import app.gcaltolotus.sync.System
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Сообщение под карточкой системы. Тексты — в ресурсах; [Failed.message] показывается как есть. */
sealed interface ConnectionNotice {
    data class Checked(val result: CheckResult) : ConnectionNotice

    data object Saved : ConnectionNotice

    /** Не заполнены адрес или пользователь Lotus. */
    data object DominoIncomplete : ConnectionNotice

    data object SignInCancelled : ConnectionNotice

    data class Failed(val message: String) : ConnectionNotice
}

/**
 * Состояние экрана подключений.
 *
 * @property dominoUrl поля Lotus в том виде, в каком их редактирует пользователь.
 * @property dominoPassword введённый, ещё не сохранённый пароль (после сохранения очищается).
 * @property dominoPasswordSaved пароль Lotus уже сохранён: пустое поле его не меняет.
 * @property dominoDisabled Lotus выключен после 401 до повторного ввода пароля.
 * @property busy системы, по которым идёт вход, сохранение или проверка.
 */
data class ConnectionsState(
    val loaded: Boolean = false,
    val google: AccountStatus = AccountStatus.SignedOut,
    val teams: AccountStatus = AccountStatus.SignedOut,
    val googleCalendarId: String = PRIMARY_CALENDAR_ID,
    val dominoUrl: String = "",
    val dominoUser: String = "",
    val dominoPassword: String = "",
    val dominoPasswordSaved: Boolean = false,
    val dominoDisabled: Boolean = false,
    val busy: Set<System> = emptySet(),
    val notices: Map<System, ConnectionNotice> = emptyMap(),
) {
    /** Lotus настроен: есть адрес, пользователь и пароль. */
    val dominoConfigured: Boolean
        get() = dominoUrl.isNotBlank() && dominoUser.isNotBlank() && dominoPasswordSaved
}

/** Подключения: вход в Google и Teams, настройки Lotus, проверка подключений. */
class ConnectionsModel(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val accounts: AccountActions,
    private val checks: ConnectionChecks,
) {
    private val mutable = MutableStateFlow(ConnectionsState())
    val state: StateFlow<ConnectionsState> = mutable.asStateFlow()

    fun load(): Job = scope.launch {
        val s = settings.load()
        val saved = secrets.dominoPassword() != null
        val google = safeStatus { accounts.googleStatus() }
        val teams = safeStatus { accounts.teamsStatus() }
        mutable.update {
            it.copy(
                loaded = true,
                google = google,
                teams = teams,
                googleCalendarId = s.googleCalendarId,
                dominoUrl = s.dominoUrl,
                dominoUser = s.dominoUser,
                dominoPasswordSaved = saved,
                dominoDisabled = s.dominoDisabled,
            )
        }
    }

    fun editDominoUrl(value: String) = mutable.update { it.copy(dominoUrl = value) }

    fun editDominoUser(value: String) = mutable.update { it.copy(dominoUser = value) }

    fun editDominoPassword(value: String) = mutable.update { it.copy(dominoPassword = value) }

    fun signInGoogle(): Job = signIn(System.GOOGLE) { accounts.signInGoogle() }

    fun signOutGoogle(): Job = run(System.GOOGLE) {
        accounts.signOutGoogle()
        mutable.update { it.copy(google = AccountStatus.SignedOut) }
        null
    }

    fun signInTeams(): Job = signIn(System.TEAMS) { accounts.signInTeams() }

    fun signOutTeams(): Job = run(System.TEAMS) {
        accounts.signOutTeams()
        mutable.update { it.copy(teams = AccountStatus.SignedOut) }
        null
    }

    /** Сохранить настройки Lotus; новый пароль снимает выключение после 401. */
    fun saveDomino(): Job = run(System.DOMINO) { if (storeDomino()) ConnectionNotice.Saved else ConnectionNotice.DominoIncomplete }

    /** Проверить подключение; у Lotus несохранённые поля сначала сохраняются. */
    fun check(system: System): Job = run(system) {
        if (system == System.DOMINO && isDominoDirty() && !storeDomino()) {
            return@run ConnectionNotice.DominoIncomplete
        }
        ConnectionNotice.Checked(checks.check(system))
    }

    private suspend fun isDominoDirty(): Boolean {
        val s = settings.load()
        val st = mutable.value
        return st.dominoPassword.isNotEmpty() || st.dominoUrl.trim() != s.dominoUrl || st.dominoUser.trim() != s.dominoUser
    }

    /** Записать поля Lotus; `false` — не заполнены адрес или пользователь. */
    private suspend fun storeDomino(): Boolean {
        val st = mutable.value
        if (st.dominoUrl.isBlank() || st.dominoUser.isBlank()) return false
        saveDominoCredentials(settings, secrets, st.dominoUrl, st.dominoUser, st.dominoPassword)
        val s = settings.load()
        val saved = secrets.dominoPassword() != null
        mutable.update {
            it.copy(
                dominoUrl = s.dominoUrl,
                dominoUser = s.dominoUser,
                dominoPassword = "",
                dominoPasswordSaved = saved,
                dominoDisabled = s.dominoDisabled,
            )
        }
        return true
    }

    private fun signIn(system: System, action: suspend () -> AccountStatus): Job = run(system) {
        val status = action()
        mutable.update { if (system == System.GOOGLE) it.copy(google = status) else it.copy(teams = status) }
        if (status.signedIn) null else ConnectionNotice.SignInCancelled
    }

    /** Выполнить [action] для [system] с отметкой «занято» и показать её итог. */
    private fun run(system: System, action: suspend () -> ConnectionNotice?): Job {
        mutable.update { it.copy(busy = it.busy + system, notices = it.notices - system) }
        return scope.launch {
            val notice = try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ConnectionNotice.Failed(e.message ?: e.toString())
            }
            mutable.update {
                val notices = if (notice == null) it.notices - system else it.notices + (system to notice)
                it.copy(busy = it.busy - system, notices = notices)
            }
        }
    }

    private suspend fun safeStatus(get: suspend () -> AccountStatus): AccountStatus = try {
        get()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AccountStatus.SignedOut
    }
}
