package app.gcaltolotus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.gcaltolotus.shared.resources.Res
import app.gcaltolotus.shared.resources.action_back
import app.gcaltolotus.shared.resources.calendar_create
import app.gcaltolotus.shared.resources.calendar_create_button
import app.gcaltolotus.shared.resources.calendar_created
import app.gcaltolotus.shared.resources.calendar_failed
import app.gcaltolotus.shared.resources.calendar_hint
import app.gcaltolotus.shared.resources.calendar_list_failed
import app.gcaltolotus.shared.resources.calendar_loading
import app.gcaltolotus.shared.resources.calendar_name
import app.gcaltolotus.shared.resources.calendar_name_default
import app.gcaltolotus.shared.resources.calendar_name_required
import app.gcaltolotus.shared.resources.calendar_not_found
import app.gcaltolotus.shared.resources.calendar_primary_mark
import app.gcaltolotus.shared.resources.calendar_read_only
import app.gcaltolotus.shared.resources.calendar_reload
import app.gcaltolotus.shared.resources.calendar_save
import app.gcaltolotus.shared.resources.calendar_switch_cancel
import app.gcaltolotus.shared.resources.calendar_switch_delete
import app.gcaltolotus.shared.resources.calendar_switch_keep
import app.gcaltolotus.shared.resources.calendar_switch_question
import app.gcaltolotus.shared.resources.calendar_switch_title
import app.gcaltolotus.shared.resources.calendar_switched
import app.gcaltolotus.shared.resources.calendar_switched_deleted
import app.gcaltolotus.shared.resources.calendar_switched_errors
import app.gcaltolotus.shared.resources.calendar_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Выбор календаря Google для копий. */
@Composable
internal fun GoogleCalendarScreen(model: GoogleCalendarModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(model) { model.load() }
    val state by model.state.collectAsState()
    val defaultName = stringResource(Res.string.calendar_name_default)
    var newName by remember { mutableStateOf(defaultName) }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.Back, contentDescription = stringResource(Res.string.action_back))
            }
            Text(stringResource(Res.string.calendar_title), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Hint(stringResource(Res.string.calendar_hint))
            if (state.loading || state.busy) {
                val text = stringResource(Res.string.calendar_loading)
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = text })
            }
            state.notice?.let { CalendarNoticeBanner(it) }
            state.loadError?.let {
                Banner(stringResource(Res.string.calendar_list_failed, it), Tone.ERROR) {
                    TextButton(onClick = { model.load() }) { Text(stringResource(Res.string.calendar_reload)) }
                }
            }

            if (state.calendars.isNotEmpty()) {
                SectionCard {
                    if (state.calendars.none { state.isCurrent(it) } && !state.loading) {
                        Hint(stringResource(Res.string.calendar_not_found, state.current), color = MaterialTheme.colorScheme.error)
                    }
                    for (calendar in state.calendars) CalendarRow(calendar, state, model)
                }
                Button(
                    onClick = { model.save() },
                    enabled = !state.busy && !state.loading && state.calendars.any { state.isSelected(it) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.calendar_save))
                }
            }

            SectionCard {
                SectionTitle(stringResource(Res.string.calendar_create))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(Res.string.calendar_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { model.create(newName) }, enabled = !state.busy) {
                    Text(stringResource(Res.string.calendar_create_button))
                }
            }
        }
    }

    state.pending?.let { pending ->
        AlertDialog(
            onDismissRequest = model::cancelSwitch,
            title = { Text(stringResource(Res.string.calendar_switch_title, pending.target.name)) },
            text = { Text(pluralStringResource(Res.plurals.calendar_switch_question, pending.oldCopies, pending.oldCopies)) },
            confirmButton = {
                Button(onClick = { model.confirmSwitch(deleteOld = true) }) {
                    Text(stringResource(Res.string.calendar_switch_delete))
                }
            },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    OutlinedButton(onClick = { model.confirmSwitch(deleteOld = false) }) {
                        Text(stringResource(Res.string.calendar_switch_keep))
                    }
                    TextButton(onClick = model::cancelSwitch) { Text(stringResource(Res.string.calendar_switch_cancel)) }
                }
            },
        )
    }
}

@Composable
private fun CalendarRow(calendar: GoogleCalendarInfo, state: GoogleCalendarState, model: GoogleCalendarModel) {
    val selected = state.isSelected(calendar)
    Row(
        Modifier.fillMaxWidth().selectable(
            selected = selected,
            enabled = calendar.writable && !state.busy,
            role = Role.RadioButton,
            onClick = { model.select(calendar) },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = calendar.writable, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f)) {
            Text(calendar.name, style = MaterialTheme.typography.bodyLarge)
            val marks = buildList {
                if (calendar.primary) add(stringResource(Res.string.calendar_primary_mark))
                if (!calendar.writable) add(stringResource(Res.string.calendar_read_only))
            }
            if (marks.isNotEmpty()) Hint(marks.joinToString(" · "))
        }
    }
}

@Composable
private fun CalendarNoticeBanner(notice: CalendarNotice) {
    when (notice) {
        is CalendarNotice.Created -> Banner(stringResource(Res.string.calendar_created, notice.name), Tone.SUCCESS)
        is CalendarNotice.Switched -> {
            val lines = buildList {
                add(stringResource(Res.string.calendar_switched, notice.name))
                if (notice.deleted > 0) add(pluralStringResource(Res.plurals.calendar_switched_deleted, notice.deleted, notice.deleted))
                if (notice.errors.isNotEmpty()) {
                    add(stringResource(Res.string.calendar_switched_errors, notice.errors.size))
                    addAll(notice.errors)
                }
            }
            Banner(lines.joinToString("\n"), if (notice.errors.isEmpty()) Tone.SUCCESS else Tone.WARNING)
        }
        CalendarNotice.NameRequired -> Banner(stringResource(Res.string.calendar_name_required), Tone.ERROR)
        is CalendarNotice.Failed -> Banner(stringResource(Res.string.calendar_failed, notice.message), Tone.ERROR)
    }
}
