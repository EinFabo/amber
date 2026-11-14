package app.amethyst.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.bubble.Fire
import app.amethyst.ui.drawBars
import app.amethyst.ui.drawBurst
import app.amethyst.ui.drawFlame
import app.amethyst.ui.drawFuse
import app.amethyst.ui.drawTick
import app.amethyst.ui.pageSpark
import app.amethyst.ui.rememberTimeline
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import kotlin.math.PI
import kotlin.math.sin

/**
 * What Amber does, shown in six seconds on a small stage: a chat, a tap into its field, the
 * keyboard coming up and the bubble with it; a tap on the bubble, the bubble listening, a
 * second tap, and the sentence going into the field - burst, tick. It plays twice and stays on
 * its last picture, which is all it shows with animations off: the sentence in the field, the
 * bubble ticked.
 *
 * One [app.amethyst.ui.Timeline] drives all of it; every part below is drawn from the time
 * alone. No microphone is involved - the bubble's level is made up.
 */
@Composable
internal fun WelcomeDemo(modifier: Modifier = Modifier) {
    val time = rememberTimeline(DEMO_MS, plays = 2)
    val sentence = stringResource(R.string.welcome_demo_sentence)
    val style = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    val words = remember(sentence, style, measurer) { measurer.measure(sentence, style, softWrap = false, maxLines = 1) }
    val colors = DemoColors(
        raised = AmberPalette.Elevated,
        line = AmberPalette.Outline,
        key = AmberPalette.Muted.copy(alpha = 0.3f),
        text = AmberPalette.Text,
        amber = AmberPalette.Primary,
        disc = AmberPalette.Flame,
        spark = pageSpark,
    )
    Canvas(
        modifier
            .fillMaxWidth()
            .height(STAGE_HEIGHT)
            .clip(AmberShapes.Card)
            .background(AmberPalette.Surface)
            .border(1.dp, AmberPalette.Outline, AmberShapes.Card)
            // The step's lead says the same in words.
            .clearAndSetSemantics {},
    ) {
        drawDemo(time.ms, words, colors)
    }
}

private class DemoColors(val raised: Color, val line: Color, val key: Color, val text: Color, val amber: Color, val disc: Color, val spark: Color)

private val STAGE_HEIGHT = 160.dp
private const val DEMO_MS = 6_000

// The demo's moments, in milliseconds from its start.
private const val FIELD_TAP = 250f
private const val KEYS_FROM = 550f
private const val KEYS_UP = 1_200f
private const val BUBBLE_FROM = 1_250f
private const val BUBBLE_THERE = 1_550f
private const val TAP_START = 1_950f
private const val LISTEN_FROM = 2_100f
private const val TAP_STOP = 3_600f
private const val WORK_FROM = 3_750f
private const val TYPE_FROM = 4_100f
private const val DONE_FROM = 5_300f

private fun DrawScope.drawDemo(ms: Float, words: TextLayoutResult, c: DemoColors) {
    val w = size.width
    val h = size.height
    val edge = 12.dp.toPx()
    fun part(from: Float, to: Float) = ((ms - from) / (to - from)).coerceIn(0f, 1f)

    // The keyboard, and with it everything that rides on it.
    val keysHeight = 74.dp.toPx()
    val keysTop = h - keysHeight * Fire.easeOut(part(KEYS_FROM, KEYS_UP))
    val fieldHeight = 36.dp.toPx()
    val fieldTop = keysTop - 8.dp.toPx() - fieldHeight

    // The chat: two messages someone else wrote.
    val line = 22.dp.toPx()
    val second = fieldTop - 12.dp.toPx() - line
    drawRoundRect(c.raised, Offset(edge, second), Size(w * 0.34f, line), CornerRadius(line / 2))
    drawRoundRect(c.raised, Offset(edge, second - 8.dp.toPx() - line), Size(w * 0.52f, line), CornerRadius(line / 2))

    // The field, tapped once so the keyboard comes; then the sentence goes in letter by letter.
    // It ends where the bubble will sit, as a chat's field ends at its send button.
    val bubble = 46.dp.toPx()
    val fieldWidth = w - 2 * edge - bubble - 8.dp.toPx()
    drawRoundRect(c.raised, Offset(edge, fieldTop), Size(fieldWidth, fieldHeight), CornerRadius(fieldHeight / 2))
    drawRoundRect(c.line, Offset(edge, fieldTop), Size(fieldWidth, fieldHeight), CornerRadius(fieldHeight / 2), style = Stroke(1.dp.toPx()))
    val textLeft = edge + 14.dp.toPx()
    val textTop = fieldTop + (fieldHeight - words.size.height) / 2
    val letters = (words.layoutInput.text.length * part(TYPE_FROM, DONE_FROM)).toInt()
    val written = if (letters == 0) 0f else words.getHorizontalPosition(letters, usePrimaryDirection = true)
    clipRect(left = textLeft, right = minOf(textLeft + written, edge + fieldWidth - 14.dp.toPx())) {
        drawText(words, c.text, Offset(textLeft, textTop))
    }
    if (ms >= FIELD_TAP + 150f) {
        val caret = minOf(textLeft + written, edge + fieldWidth - 14.dp.toPx()) + 1.dp.toPx()
        drawLine(c.amber, Offset(caret, fieldTop + 9.dp.toPx()), Offset(caret, fieldTop + fieldHeight - 9.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
    }
    ripple(c.amber, Offset(edge + fieldWidth * 0.3f, fieldTop + fieldHeight / 2), 6.dp.toPx(), part(FIELD_TAP, FIELD_TAP + 400f))

    // The keyboard: three rows of grey keys.
    if (keysTop < h) {
        drawRect(c.raised, Offset(0f, keysTop), Size(w, keysHeight))
        drawLine(c.line, Offset(0f, keysTop), Offset(w, keysTop), 1.dp.toPx())
        val gap = 4.dp.toPx()
        val keyHeight = 16.dp.toPx()
        val keyWidth = (w - 2 * edge - 9 * gap) / 10
        for ((row, keys) in intArrayOf(10, 9, 7).withIndex()) {
            val rowWidth = keys * keyWidth + (keys - 1) * gap
            val top = keysTop + 8.dp.toPx() + row * (keyHeight + 5.dp.toPx())
            for (i in 0 until keys) {
                drawRoundRect(c.key, Offset((w - rowWidth) / 2 + i * (keyWidth + gap), top), Size(keyWidth, keyHeight), CornerRadius(4.dp.toPx()))
            }
        }
    }

    // The bubble, at the edge just above the keyboard: it comes once the keyboard is up.
    val there = Fire.easeOut(part(BUBBLE_FROM, BUBBLE_THERE))
    if (there <= 0f) return
    val r = bubble / 2 * Fire.DISC * (0.6f + 0.4f * there)
    val cx = w - edge - bubble / 2
    val cy = fieldTop + fieldHeight / 2
    val ink = Color(Fire.INK)
    drawCircle(c.disc.copy(alpha = there), r, Offset(cx, cy))
    when {
        ms >= DONE_FROM -> {
            val since = ms - DONE_FROM
            if (since < Fire.BURST_MS) {
                drawCircle(c.disc.copy(alpha = 1 - since / Fire.BURST_MS), r * Fire.FUSE_RADIUS, Offset(cx, cy), style = Stroke(bubble * 0.034f))
                drawBurst(cx, cy, r, bubble, since, c.spark)
            }
            drawTick(ink, cx, cy, r, ((since - 40f) / 240f).coerceIn(0f, 1f))
        }
        ms >= WORK_FROM -> {
            drawFuse(cx, cy, r, bubble, ms - WORK_FROM, still = false)
            drawFlame(ink, c.disc, cx, cy, r, burningMs = ms - WORK_FROM)
        }
        ms >= LISTEN_FROM -> {
            val since = ms - LISTEN_FROM
            // A voice that rises and falls, as a few words would.
            val level = 0.45f + 0.3f * sin(since / 1000f * 2f * PI.toFloat() * 1.3f) + 0.15f * sin(since / 1000f * 2f * PI.toFloat() * 3.1f)
            drawBars(ink, cx, cy, r, level.coerceIn(0f, 1f), since, still = false)
            sparks(cx, cy, r, bubble, since, TAP_STOP - LISTEN_FROM)
        }
        else -> drawFlame(ink.copy(alpha = there), c.disc.copy(alpha = there), cx, cy, r)
    }
    ripple(c.amber, Offset(cx, cy), r, part(TAP_START, TAP_START + 450f))
    ripple(c.amber, Offset(cx, cy), r, part(TAP_STOP, TAP_STOP + 450f))
}

/** A tap: a ring that leaves the touched spot and fades. Nothing before it starts or after it ends. */
private fun DrawScope.ripple(color: Color, at: Offset, from: Float, u: Float) {
    if (u <= 0f || u >= 1f) return
    drawCircle(color.copy(alpha = 1f - u), from + 14.dp.toPx() * Fire.easeOut(u), at, style = Stroke(2.dp.toPx()))
}

/** Sparks off the top of the disc while it listens: a fixed handful, each rising and burning down in turn. */
private fun DrawScope.sparks(cx: Float, cy: Float, r: Float, bubble: Float, since: Float, until: Float) {
    for (i in 0 until 7) {
        val life = 520f + 37f * i
        val born = i * 83f
        val age = since - born
        // None is thrown once the listening is about to end.
        if (age < 0f || since - age % life > until - 200f) continue
        val u = (age % life) / life
        val round = (age / life).toInt()
        val side = sin((i * 2.4f + round * 1.7f)) * 0.75f
        val x = cx + side * r * 0.8f + side * bubble * 0.08f * u
        val y = cy - r * 0.9f - bubble * (0.3f + 0.12f * (i % 3)) * Fire.easeOut(u)
        val color = Color(Fire.mix(Fire.AMBER, Fire.EMBER_RED, u)).copy(alpha = 1f - u)
        drawLine(color, Offset(x, y), Offset(x, y + bubble * 0.1f * (1f - u)), bubble * 0.05f, StrokeCap.Round)
    }
}
