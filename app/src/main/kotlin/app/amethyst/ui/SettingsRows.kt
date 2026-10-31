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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

/*
 * The settings language, shared by the list and every page behind it: groups on one rounded
 * surface under a small label, rows with one line of state, a main switch for a feature that
 * is on or off as a whole, and longer explanations folded behind "Learn more".
 */

/** Space between the screen edge and a group's surface. */
internal val PageGutter = 16.dp

/** Space between a group's edge and what is in it; labels and notes line up with it. */
private val Inset = 16.dp
private val TileSize = 36.dp

/** Where a row's text starts behind a leading tile or app icon - and where its divider starts. */
private val TextStart = Inset + TileSize + Inset

/**
 * One group of a page: an optional small [label] and one-line [note] over a rounded surface
 * holding the rows. Rows sit edge to edge on it, separated by [RowDivider]. [learnMore] folds a
 * longer explanation in below.
 */
@Composable
fun SettingsGroup(
    label: String?,
    modifier: Modifier = Modifier,
    note: String? = null,
    noteColor: Color = AmberPalette.Muted,
    learnMore: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = 24.dp)) {
        if (label != null || note != null || trailing != null) {
            Column(
                Modifier.padding(horizontal = Inset).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (label != null || trailing != null) GroupLabel(label.orEmpty(), trailing = trailing)
                if (note != null) Hint(note, noteColor)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(AmberShapes.Card)
                .background(AmberPalette.Surface),
            content = content,
        )
        if (learnMore != null) LearnMore(learnMore)
    }
}

/**
 * A group whose content is not a list of rows - choices, sliders, a field: the same surface,
 * with the content padded and spaced inside it.
 */
@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    learnMore: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsGroup(title, modifier, note = subtitle, learnMore = learnMore, trailing = trailing) {
        Column(
            Modifier.padding(Inset),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/**
 * One row of a group that leads somewhere: a leading [icon] in a tinted square (or any
 * [leading], such as an app icon), the title, ONE line of [summary] and a chevron. Without
 * [onClick] it is a plain row, its [trailing] doing the acting. [below] adds a line of its
 * own under the summary, such as a trust badge; a row that states facts rather than settings
 * lets its summary run to [summaryMaxLines].
 */
@Composable
fun NavRow(
    title: String,
    summary: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    titleColor: Color = AmberPalette.Text,
    summaryColor: Color = AmberPalette.Muted,
    summaryMaxLines: Int = 1,
    below: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = { ChevronRight() },
    onClick: (() -> Unit)?,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Inset, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Inset),
    ) {
        when {
            leading != null -> leading()
            icon != null -> IconTile(icon)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = summaryColor, maxLines = summaryMaxLines, overflow = TextOverflow.Ellipsis)
            }
            below?.invoke()
        }
        trailing?.invoke()
    }
}

/** An icon on a square of amber at low strength: what a row is about, before its words. */
@Composable
fun IconTile(icon: ImageVector) {
    Box(
        Modifier
            .size(TileSize)
            .clip(RoundedCornerShape(10.dp))
            .background(AmberPalette.Deep),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = AmberPalette.Primary, modifier = Modifier.size(20.dp))
    }
}

/** A step's number on the same tinted square, for "do this, then that" lists. */
@Composable
fun NumberTile(number: Int) {
    Box(
        Modifier
            .size(TileSize)
            .clip(RoundedCornerShape(10.dp))
            .background(AmberPalette.Deep),
        contentAlignment = Alignment.Center,
    ) {
        Text("$number", style = MaterialTheme.typography.titleMedium, color = AmberPalette.Primary)
    }
}

/** A line under a group, lined up with its label, with a small [icon] in front. */
@Composable
fun GroupFootnote(text: String, icon: ImageVector, modifier: Modifier = Modifier, color: Color = AmberPalette.Muted) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Inset)
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Hint(text, color)
    }
}

/** The line between two rows of a group, starting where their text does. */
@Composable
fun RowDivider(start: Dp = TextStart) =
    HorizontalDivider(Modifier.padding(start = start), thickness = 1.dp, color = AmberPalette.Outline)

/** Something that cannot be undone, alone in its own group at the bottom of a page. */
@Composable
fun DangerAction(label: String, onClick: () -> Unit) {
    SettingsGroup(label = null) {
        NavRow(label, summary = null, titleColor = AmberPalette.Error, trailing = null, onClick = onClick)
    }
}

/**
 * The switch at the top of a page whose feature is on or off as a whole: tinted amber while
 * on, the whole card toggles.
 */
@Composable
fun MainSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
) {
    val background by animateColorAsState(if (checked) AmberPalette.Deep else AmberPalette.Elevated, tween(FAST_MS), label = "mainSwitch")
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(AmberShapes.Card)
            .background(background)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = AmberPalette.Text)
            if (summary != null) Hint(summary, if (checked) AmberPalette.Text.copy(alpha = 0.72f) else AmberPalette.Muted)
        }
        AmberSwitch(checked)
    }
}

/** A setting that is on or off, with an optional short line under it. The whole row toggles. */
@Composable
fun ToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AmberShapes.Inner)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text)
            if (description != null) Hint(description)
        }
        AmberSwitch(checked)
    }
}

/** The switch itself; its row does the toggling, so it takes no clicks of its own. */
@Composable
private fun AmberSwitch(checked: Boolean) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        colors = SwitchDefaults.colors(
            checkedThumbColor = AmberPalette.Background,
            checkedTrackColor = AmberPalette.Primary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = AmberPalette.Muted,
            uncheckedTrackColor = AmberPalette.Elevated,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** An explanation longer than a line or two, one tap away instead of in the way. */
@Composable
fun LearnMore(text: String, modifier: Modifier = Modifier, label: String = "Learn more") {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .padding(top = 4.dp)
                .clip(AmberShapes.Button)
                .clickable { open = !open }
                .foldState(open)
                .padding(horizontal = Inset, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = AmberPalette.Primary)
            ExpandChevron(open, AmberPalette.Primary)
        }
        Unfold(open) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = AmberPalette.Muted,
                modifier = Modifier.padding(horizontal = Inset).padding(bottom = 8.dp),
            )
        }
    }
}

/** What a fold row hides, shown and hidden with a quick fade - nothing slides far or bounces. */
@Composable
fun Unfold(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(FAST_MS)) + expandVertically(tween(FAST_MS)),
        exit = fadeOut(tween(FAST_MS)) + shrinkVertically(tween(FAST_MS)),
    ) {
        content()
    }
}

/** Tells a screen reader whether a row that folds something away is open. */
fun Modifier.foldState(open: Boolean): Modifier =
    semantics { stateDescription = if (open) "Expanded" else "Collapsed" }

/** Points down while folded and up while open. */
@Composable
fun ExpandChevron(expanded: Boolean, color: Color = AmberPalette.Muted) {
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
        drawPath(path, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun ChevronRight() {
    Canvas(
        Modifier
            .size(24.dp)
            .padding(7.dp),
    ) {
        val path = Path().apply {
            moveTo(size.width * 0.3f, 0f)
            lineTo(size.width * 0.75f, size.height / 2)
            lineTo(size.width * 0.3f, size.height)
        }
        drawPath(path, AmberPalette.Muted, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
    }
}
