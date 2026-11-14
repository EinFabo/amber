package app.amethyst.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.amethyst.R
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.ui.SectionCard
import app.amethyst.ui.ToggleRow

/**
 * Everything that changes the text by rules, in the order it happens to a dictation: first its
 * words and commas, then how it arrives at the cursor - and what is done when it cannot be
 * typed there. The Recording page holds what is about the recording, the Cleanup page the
 * language model.
 */
@Composable
fun TextScreen(
    settings: AmethystSettings,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    SubPage(modifier) {
        SectionCard(
            title = stringResource(R.string.text_words),
            learnMore = stringResource(R.string.text_words_more),
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
        }
        SectionCard(
            title = stringResource(R.string.text_inserting),
            subtitle = stringResource(R.string.inserting_subtitle),
            learnMore = stringResource(R.string.inserting_more),
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
            ToggleRow(
                label = stringResource(R.string.inserting_clipboard),
                description = stringResource(R.string.inserting_clipboard_note),
                checked = settings.insertion.allowClipboard,
                onChange = { value ->
                    onUpdateSettings { it.copy(insertion = it.insertion.copy(allowClipboard = value)) }
                },
            )
        }
    }
}
