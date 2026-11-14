package app.gcaltolotus.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.style.TextOverflow
import app.gcaltolotus.shared.resources.Res
import app.gcaltolotus.shared.resources.tab_connections
import app.gcaltolotus.shared.resources.tab_settings
import app.gcaltolotus.shared.resources.tab_status
import app.gcaltolotus.shared.resources.tab_sync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.stringResource

/** Состояние приложения: навигация и модели экранов. Живёт, пока показан [App]. */
class AppState(scope: CoroutineScope, deps: AppDependencies, start: Screen = Screen.STATUS) {
    val navigator = Navigator(start)
    private val sync = deps.syncService()

    val status = StatusModel(scope, deps.settings, deps.history, deps.scheduler, deps.clock, deps.systemControls.resumes)
    val syncModel = SyncModel(scope, deps.settings, deps.syncRuns())
    val connections = ConnectionsModel(scope, deps.settings, deps.secrets, deps.accounts, deps.checks)
    val googleCalendar = GoogleCalendarModel(scope, deps.settings, deps.googleCalendars, sync)
    val settings = SettingsModel(scope, deps.settings, deps.systemControls, deps.updates)

    /** Открыть синхронизацию и сразу построить предпросмотр (кнопка на экране состояния, уведомление). */
    fun openPreview() {
        navigator.open(Screen.SYNC)
        syncModel.preview()
    }

    /** Перейти по запросу платформы (касание уведомления при открытом приложении). */
    fun open(request: OpenRequest) {
        if (request.preview) openPreview() else navigator.open(request.screen)
    }
}

/**
 * Куда перейти по запросу платформы, например по касанию уведомления.
 *
 * @property preview открыть синхронизацию и сразу построить предпросмотр (тогда [screen] — SYNC).
 */
data class OpenRequest(val screen: Screen, val preview: Boolean = false)

/**
 * Корневой экран приложения.
 *
 * @param deps платформенные зависимости (androidApp, позже iOS).
 * @param start экран при запуске.
 * @param previewOnStart сразу построить предпросмотр (например, из уведомления об открытых вопросах).
 * @param openRequests переходы, пока приложение уже показано (новое касание уведомления).
 * @param dynamicColorScheme динамическая схема платформы под текущий светлый/тёмный режим
 *   (Material You на Android 12+); `null` — палитра по умолчанию, см. [GcalTheme].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun App(
    deps: AppDependencies,
    start: Screen = Screen.STATUS,
    previewOnStart: Boolean = false,
    openRequests: Flow<OpenRequest> = emptyFlow(),
    dynamicColorScheme: ColorScheme? = null,
) {
    val scope = rememberCoroutineScope()
    val app = remember(deps) { AppState(scope, deps, start) }
    LaunchedEffect(app) { if (previewOnStart) app.openPreview() }
    LaunchedEffect(app, openRequests) { openRequests.collect(app::open) }

    GcalTheme(dynamicColorScheme) {
        val screen by app.navigator.screen.collectAsState()
        // На «Состоянии» «Назад» не перехватывается: приложение закрывается как обычно.
        BackHandler(enabled = screen != Screen.STATUS) { app.navigator.back() }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    for (tab in Screen.TABS) {
                        val label = tabLabel(tab)
                        NavigationBarItem(
                            selected = screen.tab == tab,
                            onClick = { app.navigator.open(tab) },
                            icon = { Icon(tabIcon(tab), contentDescription = null) },
                            // Подпись вкладки никогда не переносится: короткие строки tab_*, а на узком экране — «…».
                            label = { Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            },
        ) { padding ->
            val modifier = Modifier.fillMaxSize().padding(padding)
            when (screen) {
                Screen.STATUS -> StatusScreen(
                    app.status,
                    onOpenPreview = app::openPreview,
                    onOpenConnections = { app.navigator.open(Screen.CONNECTIONS) },
                    modifier = modifier,
                )
                Screen.SYNC -> SyncScreen(
                    app.syncModel,
                    onOpenConnections = { app.navigator.open(Screen.CONNECTIONS) },
                    modifier = modifier,
                )
                Screen.CONNECTIONS -> ConnectionsScreen(
                    app.connections,
                    onChooseCalendar = { app.navigator.open(Screen.GOOGLE_CALENDAR) },
                    modifier = modifier,
                )
                Screen.GOOGLE_CALENDAR -> GoogleCalendarScreen(
                    app.googleCalendar,
                    onBack = { app.navigator.back() },
                    modifier = modifier,
                )
                Screen.SETTINGS -> SettingsScreen(app.settings, modifier = modifier)
            }
        }
    }
}

@Composable
private fun tabLabel(tab: Screen): String = stringResource(
    when (tab) {
        Screen.SYNC -> Res.string.tab_sync
        Screen.CONNECTIONS, Screen.GOOGLE_CALENDAR -> Res.string.tab_connections
        Screen.SETTINGS -> Res.string.tab_settings
        Screen.STATUS -> Res.string.tab_status
    },
)

private fun tabIcon(tab: Screen) = when (tab) {
    Screen.SYNC -> AppIcons.Sync
    Screen.CONNECTIONS, Screen.GOOGLE_CALENDAR -> AppIcons.Account
    Screen.SETTINGS -> AppIcons.Tune
    Screen.STATUS -> AppIcons.Home
}
