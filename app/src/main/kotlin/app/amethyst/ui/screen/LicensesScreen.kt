package app.amethyst.ui.screen

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import app.amethyst.R
import app.amethyst.ui.Hint
import app.amethyst.ui.SectionCard
import app.amethyst.ui.theme.AmberPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Amber's own license and what it is built from: the NOTICE and LICENSE files of the
 * repository, shipped as assets (app/build.gradle.kts, copyLicenses). Read on the IO pool.
 */
@Composable
fun LicensesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Kept across a rotation: read again, the page would be short for a moment and lose its place.
    var texts by rememberSaveable(stateSaver = TextsSaver) { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(Unit) {
        if (texts != null) return@LaunchedEffect
        texts = withContext(Dispatchers.IO) {
            fun read(name: String) = runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")
            read("NOTICE") to read("LICENSE") // English: asset names
        }
    }
    SubPage(modifier, readable = true) {
        SectionCard(title = "Embersay", subtitle = stringResource(R.string.licenses_subtitle)) { // English: the app's name
            val notice = texts?.first
            if (notice.isNullOrBlank()) Hint(stringResource(if (texts == null) R.string.loading else R.string.licenses_notice_unreadable))
            else LicenseText(notice)
        }
        SectionCard(title = "Apache License 2.0") { // English: the license's name
            val license = texts?.second
            if (license.isNullOrBlank()) Hint(stringResource(if (texts == null) R.string.loading else R.string.licenses_text_unreadable))
            else LicenseText(license)
        }
    }
}

@Composable
private fun LicenseText(text: String) {
    SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = AmberPalette.Text)
    }
}

/** The NOTICE and the LICENSE, or nothing while they are not read yet. */
private val TextsSaver = listSaver<Pair<String, String>?, String>(
    save = { texts -> texts?.toList().orEmpty() },
    restore = { saved -> if (saved.size == 2) saved[0] to saved[1] else null },
)
