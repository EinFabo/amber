package app.amethyst.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.amethyst.R
import app.amethyst.core.engine.LanguagePack
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.theme.AmberPalette
import kotlinx.coroutines.delay

/**
 * What Android's recognition still needs before a dictation works: the offline pack for its
 * language, with the way to get it - or, where Android has none for the language, the way to
 * another recognition. Nothing while the pack is there or nobody could ask.
 */
@Composable
internal fun LanguagePackNote(
    pack: LanguagePack?,
    onGetLanguagePack: () -> Unit,
    onOpenRecognition: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (text, action) = when (pack) {
        LanguagePack.MISSING -> R.string.language_pack_missing to (R.string.home_get_language_pack to onGetLanguagePack)
        LanguagePack.DOWNLOADING -> R.string.language_pack_downloading to null
        LanguagePack.UNSUPPORTED -> R.string.language_pack_unsupported to (R.string.done_choose_another to onOpenRecognition)
        LanguagePack.INSTALLED, null -> return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Hint(stringResource(text), AmberPalette.Primary)
        action?.let { (label, onClick) -> GhostButton(stringResource(label), onClick) }
    }
}

/**
 * Asks Android about the language pack again while the step is in front: on coming back to it -
 * Android's download dialog was over it - and every few seconds while the pack is on its way,
 * since Android says nothing when it is done. Again for another recognition ([active]).
 */
@Composable
internal fun LanguagePackWatch(active: Any?, check: suspend () -> LanguagePack?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, active) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                when (check()) {
                    LanguagePack.MISSING, LanguagePack.DOWNLOADING -> delay(LANGUAGE_PACK_RECHECK_MS)
                    LanguagePack.INSTALLED, LanguagePack.UNSUPPORTED, null -> break
                }
            }
        }
    }
}

/** A question to Android's recognition service is a binder call: every few seconds is plenty. */
private const val LANGUAGE_PACK_RECHECK_MS = 4_000L
