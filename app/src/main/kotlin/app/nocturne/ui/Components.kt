package app.nocturne.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nocturne.core.privacy.TrustLevel
import app.nocturne.ui.theme.NocturnePalette
import app.nocturne.ui.theme.badgeColor
import app.nocturne.ui.theme.badgeLabel

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = NocturnePalette.Surface),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = NocturnePalette.Muted,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NocturnePalette.Muted,
                    )
                }
            }
            content()
        }
    }
}

@Composable
fun StatusRow(
    label: String,
    description: String,
    done: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(done)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = NocturnePalette.Text,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = NocturnePalette.Muted,
            )
        }
        if (!done && actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, color = NocturnePalette.Primary)
            }
        }
    }
}

@Composable
private fun StatusDot(done: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(
                color = if (done) NocturnePalette.Primary else NocturnePalette.Elevated,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (done) "✓" else "·",
            color = if (done) NocturnePalette.Background else NocturnePalette.Muted,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The badge that says where a dictation was processed. */
@Composable
fun TrustBadge(level: TrustLevel, modifier: Modifier = Modifier) {
    val color = level.badgeColor()
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text = level.badgeLabel(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
fun Hint(text: String, color: Color = NocturnePalette.Muted) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}
