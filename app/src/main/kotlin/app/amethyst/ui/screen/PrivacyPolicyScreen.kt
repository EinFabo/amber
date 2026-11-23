package app.amethyst.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.core.privacy.PolicyBlock
import app.amethyst.core.privacy.PrivacyPolicy
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.SectionCard
import app.amethyst.ui.theme.AmberPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The privacy policy in the phone's language (res/raw/privacy_policy.txt, res/raw-de/ for
 * German), read on the IO pool. Google Play accepts the policy as text in the app; once it is
 * on the web too ([PrivacyPolicy.PRIVACY_URL]), the page offers that copy as well.
 */
@Composable
fun PrivacyPolicyScreen(
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The text itself, for the screenshots; null reads the resource. */
    text: String? = null,
) {
    val context = LocalContext.current
    // The text kept across a rotation: read again, the page would be short for a moment and
    // lose its place. Empty when it could not be read. Kept with the language it was read in:
    // a change of the phone's language recreates the page too, and brings the saved text back -
    // in the old language, which then is read again in the new one.
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    var read by rememberSaveable { mutableStateOf(text) }
    var readIn by rememberSaveable { mutableStateOf<String?>(null) }
    val current = read?.takeIf { text != null || readIn == language }
    LaunchedEffect(language) {
        if (current != null) return@LaunchedEffect
        read = withContext(Dispatchers.IO) {
            runCatching { context.resources.openRawResource(R.raw.privacy_policy).bufferedReader().use { it.readText() } }.getOrDefault("")
        }
        readIn = language
    }
    val blocks = remember(current) { current?.let(PrivacyPolicy::parse) }
    SubPage(modifier, readable = true) {
        PrivacyPolicy.webAddress()?.let { url ->
            GhostButton(stringResource(R.string.privacy_policy_web), { onOpenUrl(url) })
        }
        SectionCard(title = null) {
            val shown = blocks
            when {
                shown == null -> Hint(stringResource(R.string.loading))
                shown.isEmpty() -> Hint(stringResource(R.string.privacy_policy_unreadable), AmberPalette.Error)
                // Selectable, so a passage can be copied into a question about it.
                else -> SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { shown.forEach { Block(it) } }
                }
            }
        }
    }
}

@Composable
private fun Block(block: PolicyBlock) {
    when (block) {
        // The bar over the page already says "Privacy policy".
        is PolicyBlock.Title -> Unit
        is PolicyBlock.Heading -> Text(
            block.text,
            style = MaterialTheme.typography.titleMedium,
            color = AmberPalette.Primary,
            modifier = Modifier.padding(top = 12.dp).semantics { heading() },
        )
        is PolicyBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium, color = AmberPalette.Text)
        is PolicyBlock.Point -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("•", style = MaterialTheme.typography.bodyMedium, color = AmberPalette.Muted)
            Text(block.text, style = MaterialTheme.typography.bodyMedium, color = AmberPalette.Text)
        }
        // Not filled in yet: in the warning colour, so it reads as missing, not as a fact.
        is PolicyBlock.Missing -> Text(block.text, style = MaterialTheme.typography.bodyMedium, color = AmberPalette.Error)
    }
}
