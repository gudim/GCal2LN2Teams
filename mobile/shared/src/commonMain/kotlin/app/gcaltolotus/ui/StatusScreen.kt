package app.gcaltolotus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gcaltolotus.app.RouteCounts
import app.gcaltolotus.shared.resources.Res
import app.gcaltolotus.shared.resources.action_open_connections
import app.gcaltolotus.shared.resources.count_created
import app.gcaltolotus.shared.resources.count_deleted
import app.gcaltolotus.shared.resources.count_linked
import app.gcaltolotus.shared.resources.count_updated
import app.gcaltolotus.shared.resources.domino_unavailable_run
import app.gcaltolotus.shared.resources.nothing_changed
import app.gcaltolotus.shared.resources.status_background_every
import app.gcaltolotus.shared.resources.status_background_next
import app.gcaltolotus.shared.resources.status_background_off
import app.gcaltolotus.shared.resources.status_background_pending
import app.gcaltolotus.shared.resources.status_background_soon
import app.gcaltolotus.shared.resources.status_domino_disabled
import app.gcaltolotus.shared.resources.status_domino_unavailable
import app.gcaltolotus.shared.resources.status_errors
import app.gcaltolotus.shared.resources.status_failed
import app.gcaltolotus.shared.resources.status_last_run
import app.gcaltolotus.shared.resources.status_last_run_background
import app.gcaltolotus.shared.resources.status_last_run_manual
import app.gcaltolotus.shared.resources.status_never
import app.gcaltolotus.shared.resources.status_open_preview
import app.gcaltolotus.shared.resources.status_open_questions
import app.gcaltolotus.shared.resources.status_sync_now
import app.gcaltolotus.shared.resources.status_title
import app.gcaltolotus.shared.resources.status_unavailable_other
import app.gcaltolotus.sync.System
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Состояние: последний запуск, открытые вопросы и предупреждения о Lotus.
 *
 * @param onOpenPreview открыть синхронизацию и сразу построить предпросмотр.
 */
@Composable
internal fun StatusScreen(
    model: StatusModel,
    onOpenPreview: () -> Unit,
    onOpenConnections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(model) { model.keepFresh() }
    val state by model.state.collectAsState()

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.status_title), style = MaterialTheme.typography.headlineSmall)

        if (state.dominoDisabled) {
            Banner(stringResource(Res.string.status_domino_disabled), Tone.ERROR) {
                TextButton(onClick = onOpenConnections) { Text(stringResource(Res.string.action_open_connections)) }
            }
        } else if (state.dominoUnavailable) {
            val reason = state.lastRun?.dominoUnavailable
            Banner(
                if (reason != null) {
                    stringResource(Res.string.domino_unavailable_run, reason)
                } else {
                    stringResource(Res.string.status_domino_unavailable)
                },
                Tone.WARNING,
            )
        }
        val otherUnavailable = state.lastRun?.unavailable.orEmpty() - System.DOMINO
        for (system in otherUnavailable) {
            Banner(stringResource(Res.string.status_unavailable_other, systemName(system)), Tone.WARNING)
        }

        if (state.openQuestions > 0) {
            SectionCard {
                Text(
                    pluralStringResource(Res.plurals.status_open_questions, state.openQuestions, state.openQuestions),
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(onClick = onOpenPreview, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.status_open_preview))
                }
            }
        }

        SectionCard {
            SectionTitle(stringResource(Res.string.status_last_run))
            val last = state.lastRun
            if (last == null) {
                Hint(stringResource(Res.string.status_never))
            } else {
                Text(
                    stringResource(
                        if (last.background) Res.string.status_last_run_background else Res.string.status_last_run_manual,
                        formatDateTime(last.finishedAt),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (last.failure != null) {
                    Text(
                        stringResource(Res.string.status_failed, last.failure),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(countsText(last.totals), style = MaterialTheme.typography.bodyMedium)
                }
                if (last.errors > 0) {
                    Text(
                        stringResource(Res.string.status_errors, last.errors),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            val next = state.nextBackgroundRun
            Hint(
                when {
                    state.backgroundMinutes > 0 -> stringResource(Res.string.status_background_every, intervalLabel(state.backgroundMinutes).replaceFirstChar { it.lowercase() })
                    state.backgroundPending -> stringResource(Res.string.status_background_pending)
                    else -> stringResource(Res.string.status_background_off)
                },
            )
            if (state.backgroundMinutes > 0 && next != null) {
                Hint(
                    if (state.nextBackgroundSoon) {
                        stringResource(Res.string.status_background_soon)
                    } else {
                        stringResource(Res.string.status_background_next, formatDateTime(next))
                    },
                )
            }
        }

        FilledTonalButton(onClick = onOpenPreview, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.status_sync_now))
        }
    }
}

/** «2 создано, 1 обновлено» или «Ничего не изменено». */
@Composable
internal fun countsText(counts: RouteCounts): String {
    val parts = buildList {
        if (counts.created > 0) add(pluralStringResource(Res.plurals.count_created, counts.created, counts.created))
        if (counts.updated > 0) add(pluralStringResource(Res.plurals.count_updated, counts.updated, counts.updated))
        if (counts.deleted > 0) add(pluralStringResource(Res.plurals.count_deleted, counts.deleted, counts.deleted))
        if (counts.linked > 0) add(pluralStringResource(Res.plurals.count_linked, counts.linked, counts.linked))
    }
    return if (parts.isEmpty()) stringResource(Res.string.nothing_changed) else parts.joinToString(", ")
}
