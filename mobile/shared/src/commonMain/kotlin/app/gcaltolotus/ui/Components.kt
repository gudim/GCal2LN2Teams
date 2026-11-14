package app.gcaltolotus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.gcaltolotus.app.Route
import app.gcaltolotus.shared.resources.Res
import app.gcaltolotus.shared.resources.route_label
import app.gcaltolotus.shared.resources.system_google
import app.gcaltolotus.shared.resources.system_lotus
import app.gcaltolotus.shared.resources.system_teams
import app.gcaltolotus.sync.System
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/** Название системы по глоссарию: Google, Lotus, Teams. */
@Composable
internal fun systemName(system: System): String = stringResource(
    when (system) {
        System.GOOGLE -> Res.string.system_google
        System.DOMINO -> Res.string.system_lotus
        System.TEAMS -> Res.string.system_teams
    },
)

/** «Google → Lotus». */
@Composable
internal fun routeLabel(route: Route): String =
    stringResource(Res.string.route_label, systemName(route.origin), systemName(route.target))

/** Заголовок раздела с ролью heading для TalkBack/VoiceOver. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.semantics { heading() },
    )
}

/** Карточка раздела с отступами. */
@Composable
internal fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

enum class Tone { INFO, WARNING, ERROR, SUCCESS }

/** Плашка с сообщением; озвучивается при появлении. */
@Composable
internal fun Banner(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        Tone.INFO -> colors.secondaryContainer to colors.onSecondaryContainer
        Tone.WARNING -> colors.tertiaryContainer to colors.onTertiaryContainer
        Tone.ERROR -> colors.errorContainer to colors.onErrorContainer
        Tone.SUCCESS -> colors.primaryContainer to colors.onPrimaryContainer
    }
    Card(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tone == Tone.WARNING || tone == Tone.ERROR) {
                Icon(AppIcons.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            action?.invoke()
        }
    }
}

/** Пояснение мелким шрифтом. */
@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}

private fun two(n: Int): String = n.toString().padStart(2, '0')

/** «14.11 10:00» в часовом поясе телефона. */
internal fun formatDateTime(instant: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = instant.toLocalDateTime(zone)
    return "${two(t.day)}.${two(t.month.ordinal + 1)} ${two(t.hour)}:${two(t.minute)}"
}

/** «14.11». */
internal fun formatDate(date: LocalDate): String = "${two(date.day)}.${two(date.month.ordinal + 1)}"

/** «10:00». */
internal fun formatTime(instant: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = instant.toLocalDateTime(zone)
    return "${two(t.hour)}:${two(t.minute)}"
}

/** Дата момента в часовом поясе телефона. */
internal fun localDate(instant: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    instant.toLocalDateTime(zone).date
