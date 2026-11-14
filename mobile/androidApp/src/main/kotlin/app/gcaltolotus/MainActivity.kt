package app.gcaltolotus

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.remember
import app.gcaltolotus.auth.AndroidAccountActions
import app.gcaltolotus.auth.GoogleSignInLauncher
import app.gcaltolotus.background.SyncNotifications
import app.gcaltolotus.background.openRequestFor
import app.gcaltolotus.platform.AndroidSystemControls
import app.gcaltolotus.ui.App
import app.gcaltolotus.ui.OpenRequest
import app.gcaltolotus.ui.Screen
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {
    // Поля ниже регистрируют ActivityResultLauncher: это нужно сделать до STARTED.
    private val notificationPermission = NotificationPermission(this)

    /** Вход в Google с экраном согласия Play services. */
    val googleSignIn: GoogleSignInLauncher by lazy { GoogleSignInLauncher(this, AppGraph.get(this).google) }

    /** Раздел «Фон и уведомления» в настройках; перечитывается при возврате на экран. */
    private val systemControls = AndroidSystemControls(this, notificationPermission)

    /** Переходы по касанию уведомления, когда приложение уже открыто ([onNewIntent]). */
    private val openRequests = MutableSharedFlow<OpenRequest>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тема следует системе, значки панелей тоже (auto: тёмные в светлом режиме, светлые в
        // тёмном). Смена режима пересоздаёт Activity, и стиль выбирается заново. Под панелями
        // рисует Compose; на трёхкнопочной навигации система добавляет подложку для контраста.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        val accounts = AndroidAccountActions(this, graph.google, googleSignIn, graph.teams)
        val deps = graph.appDependencies(accounts, systemControls)
        // После пересоздания Activity (поворот) Intent тот же: переход уже выполнен.
        val first = if (savedInstanceState == null) takeOpenRequest(intent) else null
        notificationPermission.requestIfNeeded()
        setContent {
            // Material You (цвета из обоев) на Android 12+; ниже — палитра по умолчанию из shared.
            val dark = isSystemInDarkTheme()
            val dynamicColors = remember(dark) {
                when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> null
                    dark -> dynamicDarkColorScheme(this)
                    else -> dynamicLightColorScheme(this)
                }
            }
            App(
                deps,
                start = first?.screen ?: Screen.STATUS,
                previewOnStart = first?.preview == true,
                openRequests = openRequests,
                dynamicColorScheme = dynamicColors,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeOpenRequest(intent)?.let { openRequests.tryEmit(it) }
    }

    /** Куда перейти по уведомлению; extra убирается, чтобы не сработать повторно. */
    private fun takeOpenRequest(intent: Intent?): OpenRequest? {
        val destination = intent?.getStringExtra(SyncNotifications.EXTRA_DESTINATION) ?: return null
        intent.removeExtra(SyncNotifications.EXTRA_DESTINATION)
        return openRequestFor(destination)
    }
}
