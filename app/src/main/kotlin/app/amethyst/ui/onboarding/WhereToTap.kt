package app.amethyst.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.bubble.Fire
import app.amethyst.ui.FlameMark
import app.amethyst.ui.GhostButton
import app.amethyst.ui.PrimaryButton
import app.amethyst.ui.RestrictedSettingsSteps
import app.amethyst.ui.SwitchOnSteps
import app.amethyst.ui.Timeline
import app.amethyst.ui.Unfold
import app.amethyst.ui.rememberTimeline
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import kotlin.math.PI
import kotlin.math.cos

/*
 * Where to tap in Android's settings, as small drawn pictures: a page of plain rows - no
 * maker's settings look like another's - in which the one thing to tap pulses, one tap after
 * the other, over and over. They show what the numbered steps say; the steps stay one tap away.
 */

/**
 * The accessibility page: the row with Amber's service pulses, its switch slides on, "Allow"
 * pulses. At rest - animations off - it shows all three done.
 */
@Composable
internal fun SwitchOnPicture(modifier: Modifier = Modifier) {
    val time = rememberTimeline(3 * TAP_MS, plays = Timeline.LOOP)
    val amber = AmberPalette.Primary
    val tint = AmberPalette.Deep
    MiniPage(time, modifier) {
        PlainRow(0.5f)
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    // Amber's own row: lit while it is the one to tap, a little afterwards.
                    val lit = if (time.ms < TAP_MS) 0.45f + 0.55f * beat(time.ms) else 0.45f
                    drawRoundRect(tint.copy(alpha = tint.alpha * lit), cornerRadius = CornerRadius(10.dp.toPx()))
                    if (time.ms < TAP_MS) ring(amber, time.ms, 10.dp)
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlameMark(size = 24.dp)
            Text(
                stringResource(R.string.accessibility_label),
                style = MaterialTheme.typography.bodyMedium,
                color = AmberPalette.Text,
                modifier = Modifier.weight(1f),
            )
            MiniSwitch(time)
        }
        // Android's question, with the answer to give.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .graphicsLayer { alpha = time.part(2f * TAP_MS, 2f * TAP_MS + 200f) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            Bar(46.dp)
            Text(
                stringResource(R.string.where_allow),
                style = MaterialTheme.typography.labelLarge,
                color = AmberPalette.Background,
                modifier = Modifier
                    .drawBehind { if (time.fraction < 1f && time.ms >= 2f * TAP_MS) ring(amber, time.ms - 2f * TAP_MS, 18.dp) }
                    .background(amber, AmberShapes.Pill)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * Amber's app info, for an install Android locked: ⋮ pulses, then its menu opens and "Allow
 * restricted settings" pulses. At rest the menu is open.
 */
@Composable
internal fun RestrictedPicture(modifier: Modifier = Modifier) {
    val time = rememberTimeline(2 * TAP_MS, plays = Timeline.LOOP)
    val amber = AmberPalette.Primary
    val tint = AmberPalette.Deep
    val dots = AmberPalette.Text
    MiniPage(time, modifier, heading = false) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The app's name stays in sight beside the menu; what the menu covers is any app info's.
                Row(
                    Modifier.padding(start = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FlameMark(size = 28.dp)
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, color = AmberPalette.Text)
                }
                Row(Modifier.padding(start = 10.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(3) { Bar(64.dp, height = 22.dp) }
                }
                PlainRow(0.5f)
                PlainRow(0.38f)
            }
            Canvas(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
                    .drawBehind { if (time.ms < TAP_MS) ring(amber, time.ms, 14.dp) },
            ) {
                for (i in -1..1) drawCircle(dots, 1.7.dp.toPx(), Offset(size.width / 2, size.height / 2 + i * 6.dp.toPx()))
            }
            // The menu ⋮ opens, with the one item that matters.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 30.dp)
                    .widthIn(max = 230.dp)
                    .graphicsLayer {
                        val open = time.part(TAP_MS.toFloat(), TAP_MS + 160f)
                        alpha = open
                        scaleX = 0.92f + 0.08f * open
                        scaleY = 0.92f + 0.08f * open
                    }
                    .clip(AmberShapes.Inner)
                    .background(AmberPalette.Elevated)
                    .border(1.dp, AmberPalette.Outline, AmberShapes.Inner)
                    .padding(6.dp),
            ) {
                Text(
                    stringResource(R.string.where_restricted_item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmberPalette.Text,
                    modifier = Modifier
                        .drawBehind {
                            val lit = if (time.fraction < 1f) 0.45f + 0.55f * beat(time.ms - TAP_MS) else 1f
                            drawRoundRect(tint.copy(alpha = tint.alpha * lit), cornerRadius = CornerRadius(8.dp.toPx()))
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** The taps as a picture, and under it the way to the same in words. */
@Composable
internal fun SwitchOnWay() {
    var asText by rememberSaveable { mutableStateOf(false) }
    SwitchOnPicture()
    AsText(asText, { asText = !asText }) { SwitchOnSteps() }
}

/** The way past Android's lock as a picture, the button that starts it, and the same in words. */
@Composable
internal fun RestrictedWay(onOpenAppInfo: () -> Unit) {
    var asText by rememberSaveable { mutableStateOf(false) }
    RestrictedPicture()
    // The steps in words bring this button themselves.
    if (!asText) PrimaryButton(stringResource(R.string.open_app_info), onOpenAppInfo, Modifier.fillMaxWidth())
    AsText(asText, { asText = !asText }) { RestrictedSettingsSteps(onOpenAppInfo) }
}

@Composable
private fun AsText(open: Boolean, onToggle: () -> Unit, steps: @Composable () -> Unit) {
    Column {
        GhostButton(stringResource(if (open) R.string.where_hide_text else R.string.where_as_text), onToggle, color = AmberPalette.Muted)
        Unfold(open, steps)
    }
}

/** How long one tap has the picture to itself. */
private const val TAP_MS = 1_500

/** The small page the rows sit on: a bar for its heading if it has one, then [rows]. It dims for a moment where the loop starts over. */
@Composable
private fun MiniPage(time: Timeline, modifier: Modifier, heading: Boolean = true, rows: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(AmberShapes.Card)
            .background(AmberPalette.Surface)
            .border(1.dp, AmberPalette.Outline, AmberShapes.Card)
            // Said by the step's text and by the steps in words.
            .clearAndSetSemantics {}
            .padding(14.dp)
            .graphicsLayer {
                val end = time.durationMs.toFloat()
                alpha = if (time.fraction < 1f) minOf(time.part(0f, 180f), 1f - time.part(end - 180f, end)) else 1f
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (heading) Bar(88.dp, Modifier.padding(start = 10.dp, bottom = 6.dp), strong = true)
        rows()
    }
}

/** Any other row of a settings page: a dot where its icon is, a bar where its name is. */
@Composable
private fun PlainRow(widthShare: Float) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(24.dp).background(AmberPalette.Elevated, CircleShape))
        Box(Modifier.fillMaxWidth(widthShare).height(8.dp).background(AmberPalette.Elevated, AmberShapes.Pill))
    }
}

/** A grey bar standing in for words or a button. */
@Composable
private fun Bar(width: Dp, modifier: Modifier = Modifier, height: Dp = 8.dp, strong: Boolean = false) {
    Box(modifier.size(width, height).background(if (strong) AmberPalette.Outline else AmberPalette.Elevated, RoundedCornerShape(percent = 50)))
}

/** The row's switch: off in the first tap's time, sliding on in the second, on from then. */
@Composable
private fun MiniSwitch(time: Timeline) {
    val amber = AmberPalette.Primary
    val off = AmberPalette.Outline
    val knobOff = AmberPalette.Muted
    val knobOn = AmberPalette.Background
    Canvas(Modifier.size(width = 36.dp, height = 20.dp)) {
        val on = Fire.easeOut(time.part(TAP_MS + 500f, TAP_MS + 800f))
        if (time.fraction < 1f && time.ms in TAP_MS.toFloat()..2f * TAP_MS) ring(amber, time.ms - TAP_MS, 10.dp)
        drawRoundRect(lerp(off, amber, on), cornerRadius = CornerRadius(size.height / 2))
        val r = size.height / 2 - 3.dp.toPx()
        val x = size.height / 2 + (size.width - size.height) * on
        drawCircle(lerp(knobOff, knobOn, on), r, Offset(x, size.height / 2))
    }
}

/** A slow swell, 0..1..0, twice in one tap's time. */
private fun beat(ms: Float): Float = 0.5f - 0.5f * cos(ms / (TAP_MS / 2f) * 2f * PI.toFloat())

/** "Here": an outline that leaves the thing it is drawn behind and fades, twice in one tap's time. */
private fun DrawScope.ring(color: Color, ms: Float, corner: Dp) {
    val u = (ms % (TAP_MS / 2f)) / (TAP_MS / 2f)
    val grow = 7.dp.toPx() * Fire.easeOut(u)
    drawRoundRect(
        color.copy(alpha = (1f - u) * 0.9f),
        topLeft = Offset(-grow, -grow),
        size = Size(size.width + 2 * grow, size.height + 2 * grow),
        cornerRadius = CornerRadius(corner.toPx() + grow),
        style = Stroke(1.5.dp.toPx()),
    )
}
