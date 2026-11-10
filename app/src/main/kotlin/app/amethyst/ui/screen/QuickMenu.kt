package app.amethyst.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.Texts
import app.amethyst.bubble.MenuGlyph
import app.amethyst.bubble.MenuGlyphs
import app.amethyst.bubble.glyph
import app.amethyst.core.bubble.QuickMenuGeometry
import app.amethyst.core.bubble.QuickMenuItem
import app.amethyst.core.bubble.QuickMenuLayout
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.NavRow
import app.amethyst.ui.RadioRow
import app.amethyst.ui.RowDivider
import app.amethyst.ui.SettingsGroup
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

/**
 * Settings -> Bubble -> Quick menu (advanced settings only): the four places of the idle menu,
 * top to bottom as the fan shows them on both edges, each with its glyph and name. A tap picks
 * what goes there; one already in another place swaps with it ([QuickMenuLayout.choose]).
 * Listening and working stay fixed, and the page says so.
 */
@Composable
fun QuickMenuScreen(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = QuickMenuLayout.idle(settings.bubble.quickMenuIdle)
    var picking by remember { mutableStateOf<Int?>(null) }

    // From what is stored when the change lands, not from what this page last drew.
    fun choose(place: Int, item: QuickMenuItem?) = onUpdateSettings { current ->
        val now = QuickMenuLayout.idle(current.bubble.quickMenuIdle)
        current.copy(bubble = current.bubble.copy(quickMenuIdle = QuickMenuLayout.store(QuickMenuLayout.choose(now, place, item))))
    }

    SubPage(modifier) {
        SettingsGroup(label = null, note = stringResource(R.string.quick_menu_subtitle)) {
            layout.forEachIndexed { place, item ->
                if (place > 0) RowDivider()
                NavRow(
                    title = item?.let { quickMenuItemName(it) } ?: stringResource(R.string.quick_menu_empty),
                    summary = placeName(place),
                    leading = { MenuGlyphTile(item?.glyph) },
                    titleColor = if (item == null) AmberPalette.Muted else AmberPalette.Text,
                    onClick = { picking = place },
                )
            }
        }
        Hint(stringResource(R.string.quick_menu_fixed), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        GhostButton(
            stringResource(R.string.quick_menu_reset),
            onClick = { onUpdateSettings { it.copy(bubble = it.bubble.copy(quickMenuIdle = null)) } },
            enabled = settings.bubble.quickMenuIdle != null,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }

    picking?.let { place ->
        QuickMenuPicker(
            place = place,
            layout = layout,
            onPick = { item ->
                picking = null
                choose(place, item)
            },
            onDismiss = { picking = null },
        )
    }
}

/** Every idle action and "Empty" for one place; those in another place say where. */
@Composable
internal fun QuickMenuPicker(place: Int, layout: List<QuickMenuItem?>, onPick: (QuickMenuItem?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AmberPalette.Surface,
        shape = AmberShapes.Card,
        title = { Text(placeName(place), color = AmberPalette.Text) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Hint(stringResource(R.string.quick_menu_swap_note))
                QuickMenuItem.entries.forEach { item ->
                    val elsewhere = QuickMenuLayout.placeOf(layout, item)?.takeIf { it != place }
                    RadioRow(
                        label = quickMenuItemName(item),
                        note = elsewhere?.let { stringResource(R.string.quick_menu_in_use, placeName(it)) },
                        selected = layout.getOrNull(place) == item,
                        onSelect = { onPick(item) },
                        trailing = { MenuGlyphTile(item.glyph, size = 32.dp) },
                    )
                }
                RadioRow(
                    label = stringResource(R.string.quick_menu_empty),
                    note = null,
                    selected = layout.getOrNull(place) == null,
                    onSelect = { onPick(null) },
                    trailing = { MenuGlyphTile(null, size = 32.dp) },
                )
            }
        },
        confirmButton = { GhostButton(stringResource(R.string.cancel), onDismiss) },
    )
}

/** The place's name: "Top", "Place 2", "Place 3", "Bottom". */
@Composable
internal fun placeName(place: Int): String = when (place) {
    0 -> stringResource(R.string.quick_menu_place_top)
    QuickMenuGeometry.SLOTS - 1 -> stringResource(R.string.quick_menu_place_bottom)
    else -> stringResource(R.string.quick_menu_place_n, place + 1)
}

@Composable
internal fun quickMenuItemName(item: QuickMenuItem): String = Texts.quickMenuItem(LocalContext.current, item)

/** The menu's names in their order, for the row on the Bubble page; "Empty" when it holds none. */
@Composable
internal fun quickMenuSummary(layout: List<QuickMenuItem?>): String {
    val context = LocalContext.current
    return layout.filterNotNull().joinToString(" · ") { Texts.quickMenuItem(context, it) }
        .ifEmpty { stringResource(R.string.quick_menu_empty) }
}

/**
 * A quick menu button as the bubble draws it: the raised round button with its glyph in amber
 * ([MenuGlyphs], the very drawing the menu uses). [glyph] null is an empty place: a faint ring.
 */
@Composable
internal fun MenuGlyphTile(glyph: MenuGlyph?, size: Dp = 36.dp) {
    val raised = AmberPalette.Elevated
    val amber = AmberPalette.Primary
    val ring = AmberPalette.Outline
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        if (glyph == null) {
            drawCircle(ring, radius = r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            return@Canvas
        }
        drawCircle(raised, radius = r)
        drawIntoCanvas { canvas ->
            MenuGlyphs.draw(canvas.nativeCanvas, glyph, center.x, center.y, r, amber.toArgb(), behind = raised.toArgb())
        }
    }
}
