package app.gcaltolotus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gcaltolotus.app.BACKGROUND_INTERVALS
import app.gcaltolotus.shared.resources.Res
import app.gcaltolotus.shared.resources.about_title
import app.gcaltolotus.shared.resources.about_update_available
import app.gcaltolotus.shared.resources.about_update_download
import app.gcaltolotus.shared.resources.about_update_hint
import app.gcaltolotus.shared.resources.about_version
import app.gcaltolotus.shared.resources.interval_15m
import app.gcaltolotus.shared.resources.interval_1h
import app.gcaltolotus.shared.resources.interval_4h
import app.gcaltolotus.shared.resources.interval_hint
import app.gcaltolotus.shared.resources.interval_off
import app.gcaltolotus.shared.resources.interval_title
import app.gcaltolotus.shared.resources.language_hint
import app.gcaltolotus.shared.resources.language_title
import app.gcaltolotus.shared.resources.settings_title
import app.gcaltolotus.shared.resources.system_title
import app.gcaltolotus.shared.resources.system_background_ok
import app.gcaltolotus.shared.resources.system_background_limited
import app.gcaltolotus.shared.resources.system_background_restricted
import app.gcaltolotus.shared.resources.system_background_allow
import app.gcaltolotus.shared.resources.system_background_settings
import app.gcaltolotus.shared.resources.system_samsung_hint
import app.gcaltolotus.shared.resources.system_notifications_on
import app.gcaltolotus.shared.resources.system_notifications_off
import app.gcaltolotus.shared.resources.system_notifications_enable
import app.gcaltolotus.shared.resources.system_notifications_settings
import app.gcaltolotus.shared.resources.system_notifications_hint
import app.gcaltolotus.shared.resources.system_summary_switch
import app.gcaltolotus.shared.resources.confirm_forget
import app.gcaltolotus.shared.resources.confirm_hint_off
import app.gcaltolotus.shared.resources.confirm_hint_on
import app.gcaltolotus.shared.resources.confirm_switch
import app.gcaltolotus.shared.resources.confirm_title
import app.gcaltolotus.shared.resources.system_summary_hint
import app.gcaltolotus.shared.resources.system_summary_blocked_app
import app.gcaltolotus.shared.resources.system_summary_blocked_channel
import app.gcaltolotus.shared.resources.window_back
import app.gcaltolotus.shared.resources.window_forward
import app.gcaltolotus.shared.resources.window_hint
import app.gcaltolotus.shared.resources.window_invalid
import app.gcaltolotus.shared.resources.window_title
import org.jetbrains.compose.resources.stringResource

/** Настройки: окно синхронизации, фоновая синхронизация, фон и уведомления, язык, версия. */
@Composable
internal fun SettingsScreen(model: SettingsModel, modifier: Modifier = Modifier) {
    LaunchedEffect(model) { model.load() }
    // Возврат из системных настроек (onResume): разрешения перечитываются без переоткрытия экрана.
    LaunchedEffect(model) { model.followSystemChanges() }
    val state by model.state.collectAsState()

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        SectionCard {
            SectionTitle(stringResource(Res.string.window_title))
            Hint(stringResource(Res.string.window_hint))
            DaysField(stringResource(Res.string.window_back), state.daysBack, state.daysBackValid, model::editDaysBack)
            DaysField(stringResource(Res.string.window_forward), state.daysForward, state.daysForwardValid, model::editDaysForward)
        }

        SectionCard {
            SectionTitle(stringResource(Res.string.interval_title))
            Column(Modifier.selectableGroup()) {
                for (minutes in BACKGROUND_INTERVALS) {
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = state.intervalMinutes == minutes,
                            role = Role.RadioButton,
                            onClick = { model.setInterval(minutes) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.intervalMinutes == minutes, onClick = null, modifier = Modifier.padding(12.dp))
                        Text(intervalLabel(minutes), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Hint(stringResource(Res.string.interval_hint))
        }

        ConfirmSection(state, model)

        SystemSection(state, model)

        SectionCard {
            SectionTitle(stringResource(Res.string.language_title))
            Hint(stringResource(Res.string.language_hint))
        }

        AboutSection(state)
    }
}

/** «О приложении»: версия и, если вышла новая, ссылка на её страницу с APK. */
@Composable
private fun AboutSection(state: SettingsState) {
    val version = state.appVersion ?: return
    val uriHandler = LocalUriHandler.current
    SectionCard {
        SectionTitle(stringResource(Res.string.about_title))
        Text(stringResource(Res.string.about_version, version), style = MaterialTheme.typography.bodyLarge)
        val update = state.update
        if (update != null) {
            Banner(stringResource(Res.string.about_update_available, update.version), Tone.INFO)
            Button(onClick = { uriHandler.openUri(update.pageUrl) }) { Text(stringResource(Res.string.about_update_download)) }
        }
        Hint(stringResource(Res.string.about_update_hint))
    }
}

/** «Подтверждение»: подтверждать ли каждое действие ручной синхронизации (ADR 0004). */
@Composable
private fun ConfirmSection(state: SettingsState, model: SettingsModel) {
    SectionCard {
        SectionTitle(stringResource(Res.string.confirm_title))
        Row(
            Modifier.fillMaxWidth().toggleable(
                value = state.confirmEach,
                role = Role.Switch,
                onValueChange = { model.setConfirmEach(it) },
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.confirm_switch), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = state.confirmEach, onCheckedChange = null)
        }
        Hint(stringResource(if (state.confirmEach) Res.string.confirm_hint_on else Res.string.confirm_hint_off))
        if (state.attendeeAnswers > 0) {
            OutlinedButton(onClick = { model.forgetAttendeeAnswers() }) {
                Text(stringResource(Res.string.confirm_forget, state.attendeeAnswers))
            }
        }
    }
}

/** «Фон и уведомления»: состояние разрешений системы, кнопки к её настройкам, итог фона. */
@Composable
private fun SystemSection(state: SettingsState, model: SettingsModel) {
    val system = state.system
    SectionCard {
        SectionTitle(stringResource(Res.string.system_title))

        if (system != null) {
            if (system.backgroundOk) {
                Banner(stringResource(Res.string.system_background_ok), Tone.SUCCESS)
            } else {
                Banner(stringResource(Res.string.system_background_limited), Tone.WARNING)
            }
            if (system.backgroundRestricted) {
                Banner(stringResource(Res.string.system_background_restricted), Tone.ERROR)
            }
            if (system.backgroundOk) {
                OutlinedButton(onClick = model::allowBackground) { Text(stringResource(Res.string.system_background_settings)) }
            } else {
                Button(onClick = model::allowBackground) { Text(stringResource(Res.string.system_background_allow)) }
            }
        }
        if (state.isSamsung) Hint(stringResource(Res.string.system_samsung_hint))

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        if (system != null) {
            if (system.notificationsEnabled) {
                Banner(stringResource(Res.string.system_notifications_on), Tone.SUCCESS)
            } else {
                Banner(stringResource(Res.string.system_notifications_off), Tone.WARNING)
                Button(onClick = { model.enableNotifications() }) { Text(stringResource(Res.string.system_notifications_enable)) }
            }
        }
        // Итог фона — рядом с настройками уведомлений: включается и выключается в одном месте.
        Row(
            Modifier.fillMaxWidth().toggleable(
                value = state.summaryShown,
                role = Role.Switch,
                onValueChange = { model.setNotifySummary(it) },
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.system_summary_switch), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = state.summaryShown, onCheckedChange = null)
        }
        when {
            system == null || !state.summaryBlocked -> Hint(stringResource(Res.string.system_summary_hint))
            !system.notificationsEnabled -> Hint(stringResource(Res.string.system_summary_blocked_app), color = MaterialTheme.colorScheme.error)
            else -> Hint(stringResource(Res.string.system_summary_blocked_channel), color = MaterialTheme.colorScheme.error)
        }
        if (system != null) {
            OutlinedButton(onClick = model::openNotificationSettings) { Text(stringResource(Res.string.system_notifications_settings)) }
            Hint(stringResource(Res.string.system_notifications_hint))
        }
    }
}

@Composable
private fun DaysField(label: String, value: String, valid: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(3)) },
        label = { Text(label) },
        isError = !valid,
        supportingText = if (valid) null else ({ Text(stringResource(Res.string.window_invalid, MAX_WINDOW_DAYS)) }),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** «Каждые 15 минут», «Каждый час», «Каждые 4 часа», «Выключена». */
@Composable
internal fun intervalLabel(minutes: Int): String = stringResource(
    when (minutes) {
        15 -> Res.string.interval_15m
        60 -> Res.string.interval_1h
        240 -> Res.string.interval_4h
        else -> Res.string.interval_off
    },
)
