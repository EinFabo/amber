package app.amethyst.ui.screen

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
    val texts by produceState<Pair<String, String>?>(null) {
        value = withContext(Dispatchers.IO) {
            fun read(name: String) = runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")
            read("NOTICE") to read("LICENSE")
        }
    }
    SubPage(modifier) {
        SectionCard(title = "Amber", subtitle = "Free software under the Apache License 2.0.") {
            val notice = texts?.first
            if (notice.isNullOrBlank()) Hint(if (texts == null) "Loading …" else "The notice could not be read.")
            else LicenseText(notice)
        }
        SectionCard(title = "Apache License 2.0") {
            val license = texts?.second
            if (license.isNullOrBlank()) Hint(if (texts == null) "Loading …" else "The license text could not be read.")
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
