package app.amethyst.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import app.amethyst.ui.theme.AmberPalette

/** The app's name, the same in every language. */
const val APP_NAME = "sayEmber" // English: the app's name

/** Where the name's colour changes: "say" in the text's colour, then "Ember" in the theme's. */
private const val SAY = 3

/** The disc a little larger than the letters are tall, as a capital with its line. */
private const val MARK_TO_TEXT = 1.15f

/**
 * The name as a mark: the flame of the launcher icon on its amber disc, then "say" in the
 * text's colour and "Ember" in the theme's primary colour, one weight - so it reads say-Ember,
 * never sayE-mber. Read out as one word. Home's header and the introduction's welcome; everywhere
 * else the name is plain text.
 */
@Composable
fun Wordmark(modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.headlineMedium) {
    val markSize = with(LocalDensity.current) { (style.fontSize * MARK_TO_TEXT).toDp() }
    Row(
        modifier.clearAndSetSemantics {
            contentDescription = APP_NAME
            heading()
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(markSize * 0.3f),
    ) {
        FlameMark(size = markSize, disc = 1f)
        Text(
            buildAnnotatedString {
                append(APP_NAME.take(SAY))
                withStyle(SpanStyle(color = AmberPalette.Primary)) { append(APP_NAME.drop(SAY)) }
            },
            style = style,
            color = AmberPalette.Text,
        )
    }
}
