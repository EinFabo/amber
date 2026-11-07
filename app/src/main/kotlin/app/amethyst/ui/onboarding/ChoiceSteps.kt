package app.amethyst.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.Texts
import app.amethyst.core.engine.DownloadNotice
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.onboarding.CleanupPick
import app.amethyst.core.onboarding.EngineNames
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.EngineConfig
import app.amethyst.data.PhoneLocales
import app.amethyst.ui.AppIcon
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.ProgressLine
import app.amethyst.ui.RadioRow
import app.amethyst.ui.SetupActions
import app.amethyst.ui.SetupState
import app.amethyst.ui.ToggleRow
import app.amethyst.ui.screen.BubbleSizeRow
import app.amethyst.ui.screen.ReliabilityRows
import app.amethyst.ui.screen.cleanupSummary
import app.amethyst.ui.screen.fieldColors
import app.amethyst.ui.screen.label
import app.amethyst.ui.screen.trustLevelForDisplay
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import app.amethyst.ui.words

/**
 * Tidy the text? Off, or Tinfoil with a key (see [CleanupPick]). [onNextWith] moves on as the
 * settings will be once the choice is written, so the styles step appears right after a cleanup
 * was switched on.
 */
@Composable
internal fun CleanupStep(settings: AmethystSettings, content: OnboardingContent, onNextWith: (AmethystSettings) -> Unit) {
    val tinfoil = EnginePresets.TINFOIL
    val alias = EngineConfig.fromPreset(tinfoil).apiKeyAlias
    val current = settings.cleanup
    var pick by rememberSaveable { mutableStateOf(CleanupPick.initial(settings)) }
    // Not saveable: a key must never land in the saved-state bundle (security review L1).
    var key by remember { mutableStateOf("") }
    val hasKey = remember(alias) { content.hasKeyFor(alias) }
    val keyReady = hasKey || key.isNotBlank()
    val uri = LocalUriHandler.current

    fun apply() {
        if (pick == CleanupPick.TINFOIL && key.isNotBlank() && alias != null) content.onSaveKey(alias, key.trim())
        val phone = PhoneLocales.tags()
        content.onUpdateSettings { pick.applyTo(it, keyReady, phone) }
        onNextWith(pick.applyTo(settings, keyReady, phone))
    }

    StepPage(
        title = stringResource(R.string.cleanup_step_title),
        lead = stringResource(R.string.cleanup_step_lead),
        buttons = { ContinueButton(::apply) },
    ) {
        RadioRow(stringResource(R.string.off), stringResource(R.string.cleanup_off_note), selected = pick == CleanupPick.OFF, onSelect = { pick = CleanupPick.OFF })
        RadioRow(
            "Tinfoil", // English: a name
            stringResource(R.string.cleanup_step_tinfoil_note),
            selected = pick == CleanupPick.TINFOIL,
            onSelect = { pick = CleanupPick.TINFOIL },
        )
        if (current != null && current.engineId != tinfoil.id) {
            RadioRow(stringResource(R.string.cleanup_step_keep), settings.cleanupSummary(), selected = pick == CleanupPick.KEEP, onSelect = { pick = CleanupPick.KEEP })
        }
        if (pick == CleanupPick.TINFOIL) {
            if (hasKey) {
                Hint(stringResource(R.string.cleanup_step_key_stored), AmberPalette.TrustOnDevice)
            } else {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.cleanup_step_key)) },
                    singleLine = true,
                    shape = AmberShapes.Inner,
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(stringResource(R.string.engine_key_stored))
                GhostButton(stringResource(R.string.cleanup_step_get_key), { runCatching { uri.openUri("https://tinfoil.sh") } })
                if (key.isBlank()) Hint(stringResource(R.string.cleanup_step_no_key))
            }
            if (settings.activeEngine?.trustLevelForDisplay() == TrustLevel.ON_DEVICE) {
                Hint(stringResource(R.string.cleanup_step_leaves), AmberPalette.Primary)
            }
        }
    }
}

/** Messages casual, mail formal, notes short - the stock styles, for the apps on this phone. */
@Composable
internal fun AppStylesStep(settings: AmethystSettings, content: OnboardingContent, onNext: () -> Unit) {
    val installed by produceState<Set<String>?>(null, content.apps) {
        value = content.apps.all().map { it.packageName }.toSet()
    }
    StepPage(
        title = stringResource(R.string.settings_app_styles),
        lead = stringResource(R.string.styles_step_lead),
        buttons = { ContinueButton(onNext) },
    ) {
        val here = installed?.let { apps -> AppProfiles.DEFAULTS.filter { it.packageName in apps } }
        when {
            here == null -> Unit
            here.isEmpty() -> Hint(stringResource(R.string.styles_step_none))
            else -> here.forEach { profile ->
                val on = settings.profiles.any { it.packageName == profile.packageName }
                ToggleRow(
                    label = profile.label,
                    description = profile.style.label(),
                    checked = on,
                    onChange = { checked ->
                        content.onUpdateSettings { s ->
                            if (checked) s.copy(profiles = AppProfiles.upsert(s.profiles, profile))
                            else s.copy(profiles = s.profiles.filterNot { it.packageName == profile.packageName })
                        }
                    },
                    leading = { AppIcon(profile.packageName, content.apps) },
                )
            }
        }
        Hint(stringResource(R.string.styles_step_more))
    }
}

@Composable
internal fun BubbleStep(settings: AmethystSettings, content: OnboardingContent, onNext: () -> Unit) {
    val bubble = settings.bubble
    StepPage(
        title = stringResource(R.string.bubble_step_title),
        lead = stringResource(R.string.bubble_step_lead),
        buttons = { ContinueButton(onNext) },
    ) {
        RadioRow(
            stringResource(R.string.bubble_step_typing), stringResource(R.string.bubble_step_typing_note),
            selected = bubble.onlyWithTextField,
            onSelect = { content.onUpdateSettings { it.copy(bubble = it.bubble.copy(onlyWithTextField = true)) } },
        )
        RadioRow(
            stringResource(R.string.bubble_step_always), stringResource(R.string.bubble_step_always_note),
            selected = !bubble.onlyWithTextField,
            onSelect = { content.onUpdateSettings { it.copy(bubble = it.bubble.copy(onlyWithTextField = false)) } },
        )
        BubbleSizeRow(sizeDp = bubble.sizeDp, onChange = { size -> content.onUpdateSettings { it.copy(bubble = it.bubble.copy(sizeDp = size)) } })
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Gesture(stringResource(R.string.gesture_tap), stringResource(R.string.gesture_tap_note))
            Gesture(stringResource(R.string.gesture_hold), stringResource(R.string.gesture_hold_note))
            Gesture(stringResource(R.string.gesture_drag), stringResource(R.string.gesture_drag_note))
        }
    }
}

/** One way to touch the flame, and what it does. */
@Composable
private fun Gesture(gesture: String, what: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(gesture, style = MaterialTheme.typography.labelLarge, color = AmberPalette.Text)
        Hint(what)
    }
}

@Composable
internal fun ReliabilityStep(setup: SetupState, actions: SetupActions, onNext: () -> Unit) {
    StepPage(
        title = stringResource(R.string.ready_step_title),
        lead = stringResource(R.string.ready_step_lead),
        buttons = { ContinueButton(onNext) },
    ) {
        ReliabilityRows(setup, actions)
    }
}

/** What was chosen and where to change it; "Try it" leads to the flame on Home. */
@Composable
internal fun DoneStep(settings: AmethystSettings, download: ModelDownload?, onFinish: () -> Unit) {
    StepPage(
        title = stringResource(R.string.done_title),
        lead = stringResource(R.string.done_lead),
        buttons = { ContinueButton(onFinish, stringResource(R.string.done_try)) },
    ) {
        // By the name the engine step gave it, not the one Settings lists it under.
        Chosen(
            stringResource(R.string.settings_recognition),
            settings.activeEngine?.let { words(EngineNames.of(it)) } ?: stringResource(R.string.done_none),
            stringResource(R.string.done_where_recognition),
        )
        Chosen(stringResource(R.string.settings_cleanup), settings.cleanupSummary() ?: stringResource(R.string.off), stringResource(R.string.done_where_cleanup))
        Chosen(
            stringResource(R.string.settings_bubble),
            stringResource(if (settings.bubble.onlyWithTextField) R.string.bubble_step_typing else R.string.bubble_step_always),
            stringResource(R.string.done_where_bubble),
        )
        Chosen(stringResource(R.string.done_else), stringResource(R.string.done_else_value), stringResource(R.string.done_where_advanced))
        if (download != null) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(Texts.download(LocalContext.current, download), AmberPalette.Text)
                ProgressLine((download as? ModelDownload.Running)?.let { if (it.total > 0) it.received.toFloat() / it.total else 0f })
                Hint(stringResource(R.string.done_download_note))
            }
        }
    }
}

@Composable
private fun Chosen(what: String, value: String, where: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(what, style = MaterialTheme.typography.labelLarge, color = AmberPalette.Muted)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = AmberPalette.Text)
        Hint(where)
    }
}
