package app.amethyst.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.theme.badgeColor
import app.amethyst.ui.theme.badgeLabel

/** Quick, plain transitions everywhere: nothing on screen bounces or overshoots. */
private const val FAST_MS = 150

/** A filled area for the few things that are one tappable unit. No border, no shadow. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = AmberShapes.Card,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(AmberPalette.Surface),
        content = content,
    )
}

/**
 * A section of a screen: a hairline, a title in plain words and its content - a list, not a
 * stack of cards. Collapsible sections keep the rarely needed parts one tap away.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded || !collapsible) }

    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = AmberPalette.Outline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (collapsible) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { expanded = !expanded }
                    } else {
                        Modifier
                    },
                )
                .padding(top = 20.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AmberPalette.Text,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberPalette.Muted,
                    )
                }
            }
            trailing?.invoke(this)
            if (collapsible) Chevron(expanded)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(FAST_MS)) + expandVertically(tween(FAST_MS)),
            exit = fadeOut(tween(FAST_MS)) + shrinkVertically(tween(FAST_MS)),
        ) {
            Column(
                Modifier.padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun Chevron(expanded: Boolean) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(FAST_MS), label = "chevron")
    Canvas(
        Modifier
            .size(24.dp)
            .padding(7.dp)
            .rotate(rotation),
    ) {
        val path = Path().apply {
            moveTo(0f, size.height * 0.3f)
            lineTo(size.width / 2, size.height * 0.75f)
            lineTo(size.width, size.height * 0.3f)
        }
        drawPath(path, AmberPalette.Muted, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
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
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StatusDot(done)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = AmberPalette.Text,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = AmberPalette.Muted,
            )
        }
        AnimatedVisibility(visible = !done && actionLabel != null, enter = fadeIn(), exit = fadeOut()) {
            GhostButton(text = actionLabel.orEmpty(), onClick = onAction)
        }
    }
}

/** An empty ring that fills amber and shows a tick when the step is done. */
@Composable
private fun StatusDot(done: Boolean) {
    val fill by animateColorAsState(
        if (done) AmberPalette.Primary else Color.Transparent,
        tween(FAST_MS),
        label = "dotFill",
    )
    val tick by animateFloatAsState(if (done) 1f else 0f, tween(250), label = "tick")
    Canvas(Modifier.size(22.dp)) {
        drawCircle(fill)
        if (!done) {
            drawCircle(AmberPalette.Outline, style = Stroke(1.5.dp.toPx()))
        }
        if (tick > 0f) {
            val path = Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.67f)
                lineTo(size.width * 0.72f, size.height * 0.36f)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val partial = Path()
            measure.getSegment(0f, measure.length * tick, partial, true)
            drawPath(partial, AmberPalette.Background, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** The main action: solid amber with dark text. One per section at most. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = AmberShapes.Button,
        colors = ButtonDefaults.buttonColors(
            containerColor = AmberPalette.Primary,
            contentColor = AmberPalette.Background,
            disabledContainerColor = AmberPalette.Elevated,
            disabledContentColor = AmberPalette.Muted,
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A secondary action: just the words, in amber. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = AmberPalette.Primary,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = AmberShapes.Button,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        modifier = modifier,
    ) {
        Text(text, color = if (enabled) color else AmberPalette.Muted, style = MaterialTheme.typography.labelLarge)
    }
}

/** A plain icon button for the top bar, drawing its own glyph. */
@Composable
fun IconAction(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) { glyph(AmberPalette.Text) }
    }
}

/** Gear for "settings", drawn as a ring with teeth so no icon library is needed. */
val GearGlyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit = { color ->
    val stroke = 1.6.dp.toPx()
    val r = size.minDimension / 2
    drawCircle(color, radius = r * 0.3f, style = Stroke(stroke))
    for (i in 0 until 8) {
        val angle = Math.toRadians(i * 45.0)
        val inner = Offset(center.x + (r * 0.6f) * kotlin.math.cos(angle).toFloat(), center.y + (r * 0.6f) * kotlin.math.sin(angle).toFloat())
        val outer = Offset(center.x + (r * 0.9f) * kotlin.math.cos(angle).toFloat(), center.y + (r * 0.9f) * kotlin.math.sin(angle).toFloat())
        drawLine(color, inner, outer, stroke * 1.4f, cap = StrokeCap.Round)
    }
    drawCircle(color, radius = r * 0.62f, style = Stroke(stroke))
}

/** Arrow pointing left, for "back". */
val BackGlyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit = { color ->
    val stroke = 1.8.dp.toPx()
    val path = Path().apply {
        moveTo(size.width * 0.62f, size.height * 0.18f)
        lineTo(size.width * 0.3f, size.height * 0.5f)
        lineTo(size.width * 0.62f, size.height * 0.82f)
    }
    drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
}

/** Where a dictation was processed: a coloured dot and the words, nothing around them. */
@Composable
fun TrustBadge(level: TrustLevel, modifier: Modifier = Modifier) {
    val color = level.badgeColor()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(color, CircleShape),
        )
        Text(
            text = level.badgeLabel(),
            style = MaterialTheme.typography.bodySmall,
            color = AmberPalette.Muted,
        )
    }
}

@Composable
fun Hint(text: String, color: Color = AmberPalette.Muted) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** A thin progress line. */
@Composable
fun ProgressLine(fraction: Float?, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(fraction ?: 0f, tween(250), label = "progress")
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(AmberShapes.Pill)
            .background(AmberPalette.Outline),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(if (fraction == null) 0.35f else animated.coerceIn(0.02f, 1f))
                .clip(AmberShapes.Pill)
                .background(AmberPalette.Primary),
        )
    }
}

/** A choice among a few, with an optional note; the chosen one is filled amber. */
@Composable
fun ChoiceChip(label: String, note: String?, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(AmberShapes.Inner)
            .background(if (on) AmberPalette.Primary else AmberPalette.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (on) AmberPalette.Background else AmberPalette.Text,
        )
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = if (on) AmberPalette.Background.copy(alpha = 0.7f) else AmberPalette.Muted,
            )
        }
    }
}
