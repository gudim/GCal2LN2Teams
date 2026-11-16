package app.gcaltolotus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gcaltolotus.app.PreviewAction
import app.gcaltolotus.app.Route
import app.gcaltolotus.app.RouteGroup
import app.gcaltolotus.app.RunReport
import app.gcaltolotus.app.SkipReason
import app.gcaltolotus.app.SkippedSystem
import app.gcaltolotus.shared.resources.Res
import app.gcaltolotus.shared.resources.action_create
import app.gcaltolotus.shared.resources.action_delete_copy
import app.gcaltolotus.shared.resources.action_delete_extra
import app.gcaltolotus.shared.resources.action_delete_original
import app.gcaltolotus.shared.resources.action_delete_twin
import app.gcaltolotus.shared.resources.action_open_connections
import app.gcaltolotus.shared.resources.action_twin
import app.gcaltolotus.shared.resources.action_update
import app.gcaltolotus.shared.resources.all_day
import app.gcaltolotus.shared.resources.answer_delete_original_off
import app.gcaltolotus.shared.resources.answer_delete_original_on
import app.gcaltolotus.shared.resources.answer_delete_twin_on
import app.gcaltolotus.shared.resources.answer_twin_off
import app.gcaltolotus.shared.resources.answer_twin_on
import app.gcaltolotus.shared.resources.answer_twin_time_off
import app.gcaltolotus.shared.resources.answer_twin_time_on
import app.gcaltolotus.shared.resources.answer_create_off
import app.gcaltolotus.shared.resources.answer_refused_off
import app.gcaltolotus.shared.resources.answer_refused_on
import app.gcaltolotus.shared.resources.detail_attendees
import app.gcaltolotus.shared.resources.detail_changed
import app.gcaltolotus.shared.resources.detail_copy_gone
import app.gcaltolotus.shared.resources.detail_extra
import app.gcaltolotus.shared.resources.detail_from
import app.gcaltolotus.shared.resources.detail_original_gone
import app.gcaltolotus.shared.resources.detail_same_time
import app.gcaltolotus.shared.resources.detail_same_title
import app.gcaltolotus.shared.resources.detail_twin_gone
import app.gcaltolotus.shared.resources.domino_unavailable_run
import app.gcaltolotus.shared.resources.field_description
import app.gcaltolotus.shared.resources.field_location
import app.gcaltolotus.shared.resources.field_time
import app.gcaltolotus.shared.resources.field_title
import app.gcaltolotus.shared.resources.question_badge
import app.gcaltolotus.shared.resources.report_almost_done
import app.gcaltolotus.shared.resources.report_done
import app.gcaltolotus.shared.resources.report_errors
import app.gcaltolotus.shared.resources.report_errors_hint
import app.gcaltolotus.shared.resources.report_finish
import app.gcaltolotus.shared.resources.routes_hint
import app.gcaltolotus.shared.resources.routes_none
import app.gcaltolotus.shared.resources.routes_title
import app.gcaltolotus.shared.resources.skipped_domino_disabled
import app.gcaltolotus.shared.resources.skipped_not_configured
import app.gcaltolotus.shared.resources.sync_apply
import app.gcaltolotus.shared.resources.sync_applying
import app.gcaltolotus.shared.resources.sync_apply_failed
import app.gcaltolotus.shared.resources.sync_back_to_routes
import app.gcaltolotus.shared.resources.sync_domino_disabled_now
import app.gcaltolotus.shared.resources.sync_background_running
import app.gcaltolotus.shared.resources.sync_preview_rebuilt
import app.gcaltolotus.shared.resources.sync_loading
import app.gcaltolotus.shared.resources.sync_nothing
import app.gcaltolotus.shared.resources.sync_nothing_hint
import app.gcaltolotus.shared.resources.sync_preview
import app.gcaltolotus.shared.resources.sync_preview_failed
import app.gcaltolotus.shared.resources.sync_preview_hint
import app.gcaltolotus.shared.resources.sync_preview_full_hint
import app.gcaltolotus.shared.resources.sync_auto_applied
import app.gcaltolotus.shared.resources.sync_run
import app.gcaltolotus.shared.resources.sync_run_hint
import app.gcaltolotus.shared.resources.sync_questions_note
import app.gcaltolotus.shared.resources.sync_copy_now
import app.gcaltolotus.shared.resources.sync_refusals_note
import app.gcaltolotus.shared.resources.sync_refused_collapse
import app.gcaltolotus.shared.resources.sync_refused_expand
import app.gcaltolotus.shared.resources.sync_refused_hint
import app.gcaltolotus.shared.resources.sync_refused_title
import app.gcaltolotus.shared.resources.sync_refused_undo
import app.gcaltolotus.shared.resources.sync_retry
import app.gcaltolotus.shared.resources.sync_route_default
import app.gcaltolotus.shared.resources.sync_select_all
import app.gcaltolotus.shared.resources.sync_selected
import app.gcaltolotus.shared.resources.sync_title
import app.gcaltolotus.shared.resources.sync_unselect_all
import app.gcaltolotus.shared.resources.sync_warnings
import app.gcaltolotus.shared.resources.twin_title_in
import app.gcaltolotus.sync.ActionKind
import app.gcaltolotus.sync.DeleteSubject
import app.gcaltolotus.sync.When
import org.jetbrains.compose.resources.stringResource

/**
 * Ручная синхронизация: маршруты → «Предпросмотр» → действия по маршрутам с галочками →
 * «Применить» → отчёт. «Назад» с предпросмотра, отчёта или ошибки возвращает к выбору маршрутов
 * (как кнопка на экране), следующее «Назад» — на «Состояние» (см. [Navigator.back]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SyncScreen(model: SyncModel, onOpenConnections: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(model) { model.load() }
    val state by model.state.collectAsState()
    val phase = state.phase
    val backgroundRunning by model.backgroundRunning.collectAsState()
    BackHandler(enabled = phase is SyncPhase.Ready || phase is SyncPhase.Done || phase is SyncPhase.Failed) {
        model.reset()
    }

    when (phase) {
        SyncPhase.Idle -> RoutesPane(state.routes, state.autoApply, backgroundRunning, model, modifier)
        SyncPhase.Loading -> Progress(stringResource(Res.string.sync_loading), modifier)
        is SyncPhase.Applying -> Progress(stringResource(Res.string.sync_applying), modifier)
        is SyncPhase.Ready -> PreviewPane(phase, backgroundRunning, model, onOpenConnections, modifier)
        is SyncPhase.Done -> ReportPane(phase.report, onFinish = model::reset, onOpenConnections, modifier)
        is SyncPhase.Failed -> FailedPane(phase, model, onOpenConnections, modifier)
    }
}

@Composable
private fun RoutesPane(routes: Set<Route>, autoApply: Boolean, backgroundRunning: Boolean, model: SyncModel, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.sync_title), style = MaterialTheme.typography.headlineSmall)
        SectionCard {
            SectionTitle(stringResource(Res.string.routes_title))
            Hint(stringResource(Res.string.routes_hint))
            for (route in ALL_ROUTES) {
                CheckRow(
                    checked = route in routes,
                    onChange = { model.setRoute(route, it) },
                ) {
                    Text(routeLabel(route), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (routes.isEmpty()) Hint(stringResource(Res.string.routes_none), color = MaterialTheme.colorScheme.error)
        if (backgroundRunning) Banner(stringResource(Res.string.sync_background_running), Tone.INFO)
        Button(
            onClick = { model.preview() },
            enabled = routes.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (autoApply) Res.string.sync_run else Res.string.sync_preview)) }
        Hint(stringResource(if (autoApply) Res.string.sync_run_hint else Res.string.sync_preview_hint))
        if (autoApply) {
            OutlinedButton(
                onClick = { model.preview(full = true) },
                enabled = routes.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.sync_preview)) }
            Hint(stringResource(Res.string.sync_preview_full_hint))
        }
    }
}

@Composable
private fun Progress(text: String, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = text })
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PreviewPane(
    phase: SyncPhase.Ready,
    backgroundRunning: Boolean,
    model: SyncModel,
    onOpenConnections: () -> Unit,
    modifier: Modifier,
) {
    val preview = phase.preview
    // Пояснение нужно, если есть что запомнить как отказ: вопросы и создания копий.
    val hasRefusable = preview.actions.any { it.isQuestion || it.remembersRefusal }
    var refusedOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = model::reset) { Text(stringResource(Res.string.sync_back_to_routes)) }
                    Spacer(Modifier.weight(1f))
                    Hint("${formatDateTime(preview.window.start)} – ${formatDateTime(preview.window.end)}")
                }
            }
            if (backgroundRunning) {
                item { Banner(stringResource(Res.string.sync_background_running), Tone.INFO) }
            }
            phase.autoApplied?.let { report ->
                item { Banner(stringResource(Res.string.sync_auto_applied, countsText(report.totals)), Tone.SUCCESS) }
            }
            if (phase.rebuiltAfterRun) {
                item { Banner(stringResource(Res.string.sync_preview_rebuilt), Tone.WARNING) }
            }
            if (preview.dominoDisabledNow) {
                item {
                    Banner(stringResource(Res.string.sync_domino_disabled_now), Tone.ERROR) {
                        TextButton(onClick = onOpenConnections) { Text(stringResource(Res.string.action_open_connections)) }
                    }
                }
            }
            preview.dominoUnavailable?.let { reason ->
                item { Banner(stringResource(Res.string.domino_unavailable_run, reason), Tone.WARNING) }
            }
            items(preview.skipped.filter { !(preview.dominoDisabledNow && it.reason == SkipReason.DOMINO_DISABLED) }) {
                Banner(skippedText(it), Tone.INFO)
            }
            if (preview.warnings.isNotEmpty()) {
                item {
                    Banner(
                        stringResource(Res.string.sync_warnings) + "\n" + preview.warnings.joinToString("\n"),
                        Tone.WARNING,
                    )
                }
            }
            if (preview.actions.isEmpty()) {
                item {
                    SectionCard {
                        SectionTitle(stringResource(Res.string.sync_nothing))
                        Hint(stringResource(Res.string.sync_nothing_hint))
                    }
                }
            }
            if (hasRefusable) {
                item { Hint(stringResource(Res.string.sync_questions_note)) }
            }
            for (group in preview.routes) {
                item(key = "head:${group.route}") { RouteHeader(group, phase.selected, model) }
                items(group.actions, key = { it.key }) { action ->
                    ActionRow(action, checked = action.key in phase.selected, onToggle = { model.toggle(action.key) })
                }
            }
            if (preview.refused.isNotEmpty()) {
                item(key = "refused:head") {
                    RefusedHeader(preview.refused.size, open = refusedOpen, onToggle = { refusedOpen = !refusedOpen })
                }
                if (refusedOpen) {
                    items(preview.refused, key = { "refused:${it.key}" }) { row ->
                        RefusedRow(
                            row,
                            checked = row.key in phase.selected,
                            onToggle = { model.toggle(row.key) },
                            onUndo = { model.undoRefusal(row.key) },
                        )
                    }
                }
            }
        }
        ApplyBar(phase, model)
    }
}

@Composable
private fun RouteHeader(group: RouteGroup, selected: Set<String>, model: SyncModel) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(routeLabel(group.route), Modifier.weight(1f))
            if (group.actions.isNotEmpty()) {
                val allOn = group.actions.all { it.key in selected }
                TextButton(onClick = { model.setAll(group.route, !allOn) }) {
                    Text(stringResource(if (allOn) Res.string.sync_unselect_all else Res.string.sync_select_all))
                }
            }
        }
        Hint(stringResource(Res.string.sync_route_default, countsText(group.defaultCounts)))
    }
}

@Composable
private fun ActionRow(action: PreviewAction, checked: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = if (action.isQuestion) {
            CardDefaults.cardColors(containerColor = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        CheckRow(checked = checked, onChange = { onToggle() }, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (action.isQuestion) QuestionBadge()
                    Text(
                        actionLabel(action),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (action.isQuestion) colors.onTertiaryContainer else colors.primary,
                    )
                }
                for (title in titles(action)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(timeText(action), style = MaterialTheme.typography.bodySmall)
                Text(actionDetail(action), style = MaterialTheme.typography.bodySmall)
                answerText(action, checked)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Заголовок раздела «Не копируется (N)»: нажатие раскрывает и сворачивает список. */
@Composable
private fun RefusedHeader(count: Int, open: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(Res.string.sync_refused_title, count), Modifier.weight(1f))
            TextButton(onClick = onToggle) {
                Text(stringResource(if (open) Res.string.sync_refused_collapse else Res.string.sync_refused_expand))
            }
        }
        if (open) Hint(stringResource(Res.string.sync_refused_hint))
    }
}

/**
 * Строка «Не копируется»: приглушённая; галочка — скопировать сейчас, «Вернуть в предложения» —
 * снять отказ и построить предпросмотр заново.
 */
@Composable
private fun RefusedRow(row: PreviewAction, checked: Boolean, onToggle: () -> Unit, onUndo: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant, contentColor = colors.onSurfaceVariant)) {
        CheckRow(checked = checked, onChange = { onToggle() }, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(routeLabel(row.route), style = MaterialTheme.typography.labelLarge)
                Text(
                    row.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(timeText(row), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(if (checked) Res.string.answer_refused_on else Res.string.answer_refused_off),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (checked) FontWeight.Medium else null,
                )
            }
        }
        TextButton(onClick = onUndo, modifier = Modifier.padding(start = 52.dp, bottom = 4.dp)) {
            Text(stringResource(Res.string.sync_refused_undo))
        }
    }
}

@Composable
private fun QuestionBadge() {
    Surface(color = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary, shape = MaterialTheme.shapes.small) {
        Text(
            stringResource(Res.string.question_badge),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ApplyBar(phase: SyncPhase.Ready, model: SyncModel) {
    val total = phase.preview.actions.size
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val refusals = phase.refusals.size
            if (refusals > 0) {
                Banner(stringResource(Res.string.sync_refusals_note, refusals), Tone.WARNING)
            }
            if (total > 0) {
                Hint(stringResource(Res.string.sync_selected, phase.selectedActions, total))
            }
            val copyNow = phase.copyNow.size
            if (copyNow > 0) {
                Hint(stringResource(Res.string.sync_copy_now, copyNow))
            }
            Button(onClick = { model.apply() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.sync_apply))
            }
        }
    }
}

@Composable
private fun ReportPane(report: RunReport, onFinish: () -> Unit, onOpenConnections: () -> Unit, modifier: Modifier) {
    val errors = report.errors
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (errors.isEmpty()) Res.string.report_done else Res.string.report_almost_done),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(countsText(report.totals), style = MaterialTheme.typography.bodyLarge)
            if (report.dominoDisabledNow) {
                Banner(stringResource(Res.string.sync_domino_disabled_now), Tone.ERROR) {
                    TextButton(onClick = onOpenConnections) { Text(stringResource(Res.string.action_open_connections)) }
                }
            }
            report.dominoUnavailable?.let { reason ->
                Banner(stringResource(Res.string.domino_unavailable_run, reason), Tone.WARNING)
            }
            if (report.routes.isNotEmpty()) {
                SectionCard {
                    report.routes.entries.forEachIndexed { i, (route, result) ->
                        if (i > 0) HorizontalDivider()
                        Text(routeLabel(route), style = MaterialTheme.typography.titleSmall)
                        Text(countsText(result.counts), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            for (s in report.skipped) Banner(skippedText(s), Tone.INFO)
            if (errors.isNotEmpty()) {
                SectionCard {
                    SectionTitle(stringResource(Res.string.report_errors, errors.size))
                    for (e in errors) Text(e, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Hint(stringResource(Res.string.report_errors_hint))
                }
            }
        }
        Box(Modifier.padding(16.dp)) {
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.report_finish)) }
        }
    }
}

@Composable
private fun FailedPane(phase: SyncPhase.Failed, model: SyncModel, onOpenConnections: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(if (phase.applying) Res.string.sync_apply_failed else Res.string.sync_preview_failed),
            style = MaterialTheme.typography.headlineSmall,
        )
        Banner(phase.message, Tone.ERROR)
        Button(onClick = { model.preview() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.sync_retry)) }
        OutlinedButton(onClick = onOpenConnections, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.action_open_connections))
        }
        TextButton(onClick = model::reset) { Text(stringResource(Res.string.sync_back_to_routes)) }
    }
}

/** Строка с галочкой: вся строка нажимается и озвучивается как один флажок. */
@Composable
internal fun CheckRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f).padding(end = 8.dp)) { content() }
    }
}

@Composable
private fun skippedText(s: SkippedSystem): String = when (s.reason) {
    SkipReason.NOT_CONFIGURED -> stringResource(Res.string.skipped_not_configured, systemName(s.system))
    SkipReason.DOMINO_DISABLED -> stringResource(Res.string.skipped_domino_disabled)
}

@Composable
private fun actionLabel(a: PreviewAction): String = stringResource(
    when (a.kind) {
        ActionKind.CREATE -> Res.string.action_create
        ActionKind.UPDATE -> Res.string.action_update
        ActionKind.TWIN -> Res.string.action_twin
        ActionKind.DELETE, ActionKind.LINK, ActionKind.UNLINK -> when (a.subject) {
            DeleteSubject.EXTRA -> Res.string.action_delete_extra
            DeleteSubject.ORIGINAL -> Res.string.action_delete_original
            DeleteSubject.TWIN -> Res.string.action_delete_twin
            DeleteSubject.COPY, null -> Res.string.action_delete_copy
        }
    },
)

/** Пояснение к действию, как в `/v2`: откуда, что изменилось, почему удаляется. */
@Composable
private fun actionDetail(a: PreviewAction): String {
    val from = systemName(a.origin)
    val to = systemName(a.target)
    return when (a.kind) {
        ActionKind.CREATE -> stringResource(Res.string.detail_from, from)
        ActionKind.UPDATE -> {
            val fields = a.changed.map { fieldName(it) }.distinct()
            val changed = stringResource(Res.string.detail_changed, fields.joinToString(", "))
            if (!a.selected) changed + " · " + stringResource(Res.string.detail_attendees) else changed
        }
        ActionKind.TWIN -> stringResource(if (a.isTimeTwin) Res.string.detail_same_time else Res.string.detail_same_title, from, to)
        ActionKind.DELETE, ActionKind.LINK, ActionKind.UNLINK -> when (a.subject) {
            DeleteSubject.EXTRA -> stringResource(Res.string.detail_extra, to)
            DeleteSubject.ORIGINAL -> stringResource(Res.string.detail_copy_gone, to, from)
            DeleteSubject.TWIN -> stringResource(Res.string.detail_twin_gone, to, from)
            DeleteSubject.COPY, null -> stringResource(Res.string.detail_original_gone, to)
        }
    }
}

/**
 * Вопрос «это одна встреча?» по времени (ADR 0003): начало и конец совпадают, названия разные.
 * В отличие от вопроса по названию, по умолчанию без галочки.
 */
private val PreviewAction.isTimeTwin: Boolean get() = kind == ActionKind.TWIN && !selected

/**
 * Названия события в строке: у двойников — оба, с системой, если они различаются (иначе не понять,
 * что это одна встреча); у остальных — одно.
 */
@Composable
private fun titles(a: PreviewAction): List<String> =
    if (a.kind == ActionKind.TWIN && a.partnerTitle.isNotEmpty() && a.partnerTitle != a.title) {
        listOf(
            stringResource(Res.string.twin_title_in, systemName(a.origin), a.partnerTitle),
            stringResource(Res.string.twin_title_in, systemName(a.target), a.title),
        )
    } else {
        listOf(a.title)
    }

/** Что значит галочка у действия с запоминаемым отказом. */
@Composable
private fun answerText(a: PreviewAction, checked: Boolean): String? {
    if (!a.remembersRefusal) return null
    return when {
        a.kind == ActionKind.CREATE -> if (checked) null else stringResource(Res.string.answer_create_off)
        a.isTimeTwin -> stringResource(if (checked) Res.string.answer_twin_time_on else Res.string.answer_twin_time_off)
        a.kind == ActionKind.TWIN -> stringResource(if (checked) Res.string.answer_twin_on else Res.string.answer_twin_off)
        a.subject == DeleteSubject.TWIN && checked -> stringResource(Res.string.answer_delete_twin_on)
        checked -> stringResource(Res.string.answer_delete_original_on)
        else -> stringResource(Res.string.answer_delete_original_off)
    }
}

@Composable
private fun fieldName(field: String): String = when (field) {
    "title" -> stringResource(Res.string.field_title)
    "start", "end" -> stringResource(Res.string.field_time)
    "location" -> stringResource(Res.string.field_location)
    "description" -> stringResource(Res.string.field_description)
    else -> field
}

/** «14.11 · 10:00–11:00» или «14.11 · весь день». */
@Composable
private fun timeText(a: PreviewAction): String = when (val start = a.start) {
    is When.AllDay -> "${formatDate(start.date)} · ${stringResource(Res.string.all_day)}"
    is When.Timed -> {
        val end = a.end as? When.Timed
        val range = if (end == null) formatTime(start.instant) else "${formatTime(start.instant)}–${formatTime(end.instant)}"
        "${formatDate(localDate(start.instant))} · $range"
    }
}
