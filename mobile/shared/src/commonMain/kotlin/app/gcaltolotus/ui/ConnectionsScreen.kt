package app.gcaltolotus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.gcaltolotus.app.PRIMARY_CALENDAR_ID
import app.gcaltolotus.shared.resources.Res
import app.gcaltolotus.shared.resources.account_signed_in
import app.gcaltolotus.shared.resources.account_signed_in_as
import app.gcaltolotus.shared.resources.account_signed_out
import app.gcaltolotus.shared.resources.check_auth_failed
import app.gcaltolotus.shared.resources.check_not_configured
import app.gcaltolotus.shared.resources.check_ok
import app.gcaltolotus.shared.resources.check_ok_detail
import app.gcaltolotus.shared.resources.check_unreachable
import app.gcaltolotus.shared.resources.connection_check
import app.gcaltolotus.shared.resources.connection_check_a11y
import app.gcaltolotus.shared.resources.connection_failed
import app.gcaltolotus.shared.resources.connection_saved
import app.gcaltolotus.shared.resources.connection_sign_in
import app.gcaltolotus.shared.resources.connection_sign_in_a11y
import app.gcaltolotus.shared.resources.connection_sign_in_cancelled
import app.gcaltolotus.shared.resources.connection_sign_out
import app.gcaltolotus.shared.resources.connection_sign_out_a11y
import app.gcaltolotus.shared.resources.connections_markers_hint
import app.gcaltolotus.shared.resources.connections_title
import app.gcaltolotus.shared.resources.domino_disabled
import app.gcaltolotus.shared.resources.domino_incomplete
import app.gcaltolotus.shared.resources.domino_not_configured
import app.gcaltolotus.shared.resources.domino_password
import app.gcaltolotus.shared.resources.domino_password_hint
import app.gcaltolotus.shared.resources.domino_password_saved
import app.gcaltolotus.shared.resources.domino_save
import app.gcaltolotus.shared.resources.domino_server
import app.gcaltolotus.shared.resources.domino_server_placeholder
import app.gcaltolotus.shared.resources.domino_user
import app.gcaltolotus.shared.resources.domino_user_hint
import app.gcaltolotus.shared.resources.google_calendar_choose
import app.gcaltolotus.shared.resources.google_calendar_current
import app.gcaltolotus.shared.resources.google_calendar_primary
import app.gcaltolotus.shared.resources.progress_busy
import app.gcaltolotus.shared.resources.title_google
import app.gcaltolotus.shared.resources.title_lotus
import app.gcaltolotus.shared.resources.title_teams
import app.gcaltolotus.sync.System
import org.jetbrains.compose.resources.stringResource

/** Подключения: Google, Lotus, Teams. */
@Composable
internal fun ConnectionsScreen(model: ConnectionsModel, onChooseCalendar: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(model) { model.load() }
    val state by model.state.collectAsState()

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.connections_title), style = MaterialTheme.typography.headlineSmall)

        AccountCard(
            system = System.GOOGLE,
            title = stringResource(Res.string.title_google),
            status = state.google,
            state = state,
            onSignIn = model::signInGoogle,
            onSignOut = model::signOutGoogle,
            onCheck = { model.check(System.GOOGLE) },
        ) {
            val calendar = if (state.googleCalendarId == PRIMARY_CALENDAR_ID) {
                stringResource(Res.string.google_calendar_primary)
            } else {
                state.googleCalendarId
            }
            Text(stringResource(Res.string.google_calendar_current, calendar), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onChooseCalendar, enabled = state.google.signedIn) {
                Text(stringResource(Res.string.google_calendar_choose))
            }
        }

        DominoCard(state, model)

        AccountCard(
            system = System.TEAMS,
            title = stringResource(Res.string.title_teams),
            status = state.teams,
            state = state,
            onSignIn = model::signInTeams,
            onSignOut = model::signOutTeams,
            onCheck = { model.check(System.TEAMS) },
        )

        Hint(stringResource(Res.string.connections_markers_hint))
    }
}

@Composable
private fun AccountCard(
    system: System,
    title: String,
    status: AccountStatus,
    state: ConnectionsState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onCheck: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val busy = system in state.busy
    val name = systemName(system)
    SectionCard {
        SectionTitle(title)
        Text(
            when {
                !status.signedIn -> stringResource(Res.string.account_signed_out)
                status.account != null -> stringResource(Res.string.account_signed_in_as, status.account)
                else -> stringResource(Res.string.account_signed_in)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (status.signedIn) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        if (status.signedIn) extra()
        BusyBar(busy)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status.signedIn) {
                val a11y = stringResource(Res.string.connection_sign_out_a11y, name)
                OutlinedButton(onClick = onSignOut, enabled = !busy, modifier = Modifier.semantics { contentDescription = a11y }) {
                    Text(stringResource(Res.string.connection_sign_out))
                }
                CheckButton(name, busy, onCheck)
            } else {
                val a11y = stringResource(Res.string.connection_sign_in_a11y, name)
                Button(onClick = onSignIn, enabled = !busy, modifier = Modifier.semantics { contentDescription = a11y }) {
                    Text(stringResource(Res.string.connection_sign_in))
                }
            }
        }
        state.notices[system]?.let { NoticeBanner(it) }
    }
}

@Composable
private fun DominoCard(state: ConnectionsState, model: ConnectionsModel) {
    val busy = System.DOMINO in state.busy
    SectionCard {
        SectionTitle(stringResource(Res.string.title_lotus))
        if (state.dominoDisabled) {
            Banner(stringResource(Res.string.domino_disabled), Tone.ERROR)
        } else if (state.loaded && !state.dominoConfigured) {
            Text(
                stringResource(Res.string.domino_not_configured),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedTextField(
            value = state.dominoUrl,
            onValueChange = model::editDominoUrl,
            label = { Text(stringResource(Res.string.domino_server)) },
            placeholder = { Text(stringResource(Res.string.domino_server_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.dominoUser,
            onValueChange = model::editDominoUser,
            label = { Text(stringResource(Res.string.domino_user)) },
            supportingText = { Text(stringResource(Res.string.domino_user_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.dominoPassword,
            onValueChange = model::editDominoPassword,
            label = { Text(stringResource(Res.string.domino_password)) },
            supportingText = {
                Text(
                    stringResource(
                        if (state.dominoPasswordSaved && !state.dominoDisabled) Res.string.domino_password_saved
                        else Res.string.domino_password_hint,
                    ),
                )
            },
            isError = state.dominoDisabled,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        BusyBar(busy)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.saveDomino() }, enabled = !busy) { Text(stringResource(Res.string.domino_save)) }
            CheckButton(systemName(System.DOMINO), busy || state.dominoDisabled && state.dominoPassword.isEmpty()) {
                model.check(System.DOMINO)
            }
        }
        state.notices[System.DOMINO]?.let { NoticeBanner(it) }
    }
}

@Composable
private fun CheckButton(name: String, disabled: Boolean, onCheck: () -> Unit) {
    val a11y = stringResource(Res.string.connection_check_a11y, name)
    OutlinedButton(onClick = onCheck, enabled = !disabled, modifier = Modifier.semantics { contentDescription = a11y }) {
        Text(stringResource(Res.string.connection_check))
    }
}

@Composable
private fun BusyBar(busy: Boolean) {
    if (!busy) return
    val text = stringResource(Res.string.progress_busy)
    LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = text })
}

@Composable
private fun NoticeBanner(notice: ConnectionNotice) {
    when (notice) {
        is ConnectionNotice.Checked -> when (val r = notice.result) {
            is CheckResult.Ok -> Banner(
                if (r.detail == null) stringResource(Res.string.check_ok) else stringResource(Res.string.check_ok_detail, r.detail),
                Tone.SUCCESS,
            )
            is CheckResult.AuthFailed -> Banner(stringResource(Res.string.check_auth_failed, r.message.orEmpty()), Tone.ERROR)
            is CheckResult.Unreachable -> Banner(stringResource(Res.string.check_unreachable, r.message.orEmpty()), Tone.WARNING)
            is CheckResult.NotConfigured -> Banner(stringResource(Res.string.check_not_configured, r.message.orEmpty()), Tone.INFO)
        }
        ConnectionNotice.Saved -> Banner(stringResource(Res.string.connection_saved), Tone.SUCCESS)
        ConnectionNotice.DominoIncomplete -> Banner(stringResource(Res.string.domino_incomplete), Tone.ERROR)
        ConnectionNotice.SignInCancelled -> Banner(stringResource(Res.string.connection_sign_in_cancelled), Tone.INFO)
        is ConnectionNotice.Failed -> Banner(stringResource(Res.string.connection_failed, notice.message), Tone.ERROR)
    }
}
