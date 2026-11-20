package app.amethyst.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.OwnWordsReach
import app.amethyst.core.text.OwnWords
import app.amethyst.ui.AmberIcons
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.SectionCard
import app.amethyst.ui.ToggleRow
import app.amethyst.ui.recognitionRowName
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes

/**
 * Everything that changes the text by fixed rules on the phone, in the order it happens to a
 * dictation: its words and commas, then how it arrives at the cursor - and what is done when it
 * cannot be inserted there. The Recording page holds what is about the recording, the Cleanup
 * page the language model, the own words a page of their own ([OwnWordsScreen]).
 */
@Composable
fun TextScreen(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubPage(modifier) {
        // Cleanup and these rules both change the text; which is which, said once on top.
        Hint(stringResource(R.string.text_page_lead))
        SectionCard(
            title = stringResource(R.string.text_words),
        ) {
            ToggleRow(
                label = stringResource(R.string.text_fillers),
                description = stringResource(R.string.text_fillers_note),
                checked = settings.capture.stripFillers,
                onChange = { value ->
                    onUpdateSettings { it.copy(capture = it.capture.copy(stripFillers = value)) }
                },
            )
            ToggleRow(
                label = stringResource(R.string.text_commas),
                description = stringResource(R.string.text_commas_note),
                checked = settings.capture.germanCommas,
                onChange = { value ->
                    onUpdateSettings { it.copy(capture = it.capture.copy(germanCommas = value)) }
                },
            )
            ToggleRow(
                label = stringResource(R.string.text_spoken),
                description = stringResource(R.string.text_spoken_note),
                checked = settings.words.spokenPunctuation,
                onChange = { value ->
                    onUpdateSettings { it.copy(words = it.words.copy(spokenPunctuation = value)) }
                },
            )
        }
        SectionCard(
            title = stringResource(R.string.text_inserting),
            subtitle = stringResource(R.string.inserting_subtitle),
        ) {
            ToggleRow(
                label = stringResource(R.string.inserting_spaces),
                description = stringResource(R.string.inserting_spaces_note),
                checked = settings.insertion.autoSpace,
                onChange = { value ->
                    onUpdateSettings { it.copy(insertion = it.insertion.copy(autoSpace = value)) }
                },
            )
            ToggleRow(
                label = stringResource(R.string.inserting_capitals),
                description = stringResource(R.string.inserting_capitals_note),
                checked = settings.insertion.autoCapitalize,
                onChange = { value ->
                    onUpdateSettings { it.copy(insertion = it.insertion.copy(autoCapitalize = value)) }
                },
            )
            ToggleRow(
                label = stringResource(R.string.inserting_punctuation),
                description = stringResource(R.string.inserting_punctuation_note),
                checked = settings.insertion.gluePunctuation,
                onChange = { value ->
                    onUpdateSettings { it.copy(insertion = it.insertion.copy(gluePunctuation = value)) }
                },
            )
            // The security mode never writes the clipboard: off, and it stays off until the mode is.
            val secure = app.amethyst.core.privacy.SecurityMode.on(settings)
            ToggleRow(
                label = stringResource(R.string.inserting_clipboard),
                description = stringResource(if (secure) R.string.inserting_clipboard_secure else R.string.inserting_clipboard_note),
                checked = app.amethyst.core.privacy.SecurityMode.mayUseClipboard(settings),
                onChange = { value ->
                    if (!secure) onUpdateSettings { it.copy(insertion = it.insertion.copy(allowClipboard = value)) }
                },
            )
        }
    }
}

/** The own words, a row of the main settings group: names and terms the recognition and the cleanup are told. */
@Composable
fun OwnWordsScreen(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubPage(modifier) {
        OwnWordsCard(settings.words.own) { words ->
            onUpdateSettings { it.copy(words = it.words.copy(own = words)) }
        }
        // Said only while it matters: the active recognition cannot take the list.
        val active = settings.activeEngine
        if (active != null && !OwnWordsReach.recognitionTakes(active.kind)) {
            Hint(stringResource(R.string.own_words_not_used, recognitionRowName(active)), AmberPalette.Primary)
        }
    }
}

/**
 * The user's own names and terms: a field to add one (or several, split at commas and line
 * breaks - [OwnWords.add]) and the list as chips, a tap on one taking it off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OwnWordsCard(words: List<String>, onChange: (List<String>) -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    val full = words.size >= OwnWords.MAX_WORDS
    fun add() {
        if (typed.isBlank() || full) return
        onChange(OwnWords.add(words, typed))
        typed = ""
    }
    SectionCard(
        title = stringResource(R.string.own_words),
        subtitle = stringResource(R.string.own_words_subtitle),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.take(TYPED_CHARS) },
                enabled = !full,
                label = { Text(stringResource(R.string.own_words_field)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                shape = AmberShapes.Inner,
                colors = fieldColors(),
                modifier = Modifier.weight(1f),
            )
            GhostButton(stringResource(R.string.own_words_add), ::add, enabled = typed.isNotBlank() && !full)
        }
        if (words.isEmpty()) {
            Hint(stringResource(R.string.own_words_none))
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                words.forEach { word -> WordChip(word) { onChange(OwnWords.remove(words, word)) } }
            }
            Hint(stringResource(if (full) R.string.own_words_full else R.string.own_words_count, words.size, OwnWords.MAX_WORDS))
        }
    }
}

/** One own word; a tap takes it off the list, and a screen reader says so. */
@Composable
private fun WordChip(word: String, onRemove: () -> Unit) {
    val remove = stringResource(R.string.own_words_remove, word)
    Row(
        Modifier
            .clip(AmberShapes.Inner)
            .background(AmberPalette.Elevated)
            .clickable(onClick = onRemove)
            .semantics { contentDescription = remove }
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(word, style = MaterialTheme.typography.labelLarge, color = AmberPalette.Text)
        Icon(AmberIcons.Close, contentDescription = null, tint = AmberPalette.Muted, modifier = Modifier.size(16.dp))
    }
}

/** Enough for a few words pasted at once; each is cut to [OwnWords.MAX_CHARS] when added. */
private const val TYPED_CHARS = 1_000
