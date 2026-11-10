package app.amethyst.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.Texts
import app.amethyst.core.settings.LanguageList
import app.amethyst.core.settings.LanguageUse
import app.amethyst.data.PhoneLocales
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

/**
 * The languages of one speech recognition, the same on every page that has them: the ones
 * picked first, in their order - numbered when there are several, the first being the most
 * likely - then the phone's and the common ones to add, and every other one behind "More
 * languages". A tap adds a language at the end or takes it out ([LanguageList.toggled]); for
 * Android's speech recognition ([LanguageUse.FIRST]) it picks that one alone. [defaults] are
 * the phone's languages as this speech recognition takes them, offered back when changed.
 * Nothing for a speech recognition that detects the language itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguageChips(
    picked: List<String>,
    use: LanguageUse,
    defaults: List<String>,
    onChange: (List<String>) -> Unit,
) {
    if (use == LanguageUse.DETECTS_ITSELF) return
    val context = LocalContext.current
    val phone = remember { PhoneLocales.tags() }
    var showMore by rememberSaveable { mutableStateOf(false) }
    val offered = LanguageList.offered(picked, use, phone)
    val more = remember(offered, showMore) {
        if (!showMore) emptyList() else LanguageList.more(offered).sortedBy { Texts.languageName(context, it) }
    }
    val numbered = use == LanguageUse.SEVERAL && picked.size > 1

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (offered + more).forEach { tag ->
            val position = picked.indexOf(tag)
            val name = Texts.languageName(context, tag, withRegion = use == LanguageUse.FIRST && '-' in tag)
            LanguageChip(
                label = if (numbered && position >= 0) "${position + 1} · $name" else name,
                on = position >= 0,
                role = if (use == LanguageUse.FIRST) Role.RadioButton else Role.Checkbox,
            ) { onChange(LanguageList.toggled(picked, tag, use)) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton(stringResource(if (showMore) R.string.languages_fewer else R.string.languages_more), { showMore = !showMore })
        if (picked != defaults && defaults.isNotEmpty()) {
            GhostButton(stringResource(R.string.languages_phone), { onChange(defaults) })
        }
    }
}

@Composable
private fun LanguageChip(label: String, on: Boolean, role: Role, onToggle: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) AmberPalette.Background else AmberPalette.Text,
        modifier = Modifier
            .clip(AmberShapes.Inner)
            .background(if (on) AmberPalette.Primary else AmberPalette.Surface)
            .toggleable(value = on, role = role, onValueChange = { onToggle() })
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
