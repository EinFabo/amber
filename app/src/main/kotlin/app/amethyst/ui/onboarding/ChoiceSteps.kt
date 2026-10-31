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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.amethyst.core.engine.DownloadNotice
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.onboarding.CleanupPick
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.EngineConfig
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
        content.onUpdateSettings { pick.applyTo(it, keyReady) }
        onNextWith(pick.applyTo(settings, keyReady))
    }

    StepPage(
        title = "Tidy the text?",
        lead = "A chat model can fix punctuation, filler words and mis-hearings before the text is typed.",
        buttons = { ContinueButton(::apply) },
    ) {
        RadioRow("Off", "Text goes in as recognized", selected = pick == CleanupPick.OFF, onSelect = { pick = CleanupPick.OFF })
        RadioRow(
            "Tinfoil",
            "A private enclave: the text is checked there and never stored. Needs a key.",
            selected = pick == CleanupPick.TINFOIL,
            onSelect = { pick = CleanupPick.TINFOIL },
        )
        if (current != null && current.engineId != tinfoil.id) {
            RadioRow("Keep as it is", settings.cleanupSummary(), selected = pick == CleanupPick.KEEP, onSelect = { pick = CleanupPick.KEEP })
        }
        if (pick == CleanupPick.TINFOIL) {
            if (hasKey) {
                Hint("Your Tinfoil key is stored.", AmberPalette.TrustOnDevice)
            } else {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Tinfoil key") },
                    singleLine = true,
                    shape = AmberShapes.Inner,
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("Stored encrypted on this phone only.")
                GhostButton("Get a key at tinfoil.sh", { runCatching { uri.openUri("https://tinfoil.sh") } })
                if (key.isBlank()) Hint("Without a key, cleanup stays off. You can add one later in Settings → Cleanup.")
            }
            if (settings.activeEngine?.trustLevelForDisplay() == TrustLevel.ON_DEVICE) {
                Hint("The recognized text leaves the phone for the cleanup - your recording does not.", AmberPalette.Primary)
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
        title = "Style per app",
        lead = "The cleanup writes casually in messengers, formally in mail and briefly in notes.",
        buttons = { ContinueButton(onNext) },
    ) {
        val here = installed?.let { apps -> AppProfiles.DEFAULTS.filter { it.packageName in apps } }
        when {
            here == null -> Unit
            here.isEmpty() -> Hint("None of the usual apps are on this phone.")
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
        Hint("Add more apps any time in Settings → App styles.")
    }
}

@Composable
internal fun BubbleStep(settings: AmethystSettings, content: OnboardingContent, onNext: () -> Unit) {
    val bubble = settings.bubble
    StepPage(
        title = "The flame",
        lead = "It floats over other apps. Tap it to dictate, hold it to talk.",
        buttons = { ContinueButton(onNext) },
    ) {
        RadioRow(
            "Only when you type", "Appears when a text field is active",
            selected = bubble.onlyWithTextField,
            onSelect = { content.onUpdateSettings { it.copy(bubble = it.bubble.copy(onlyWithTextField = true)) } },
        )
        RadioRow(
            "Always", "Stays on screen in every app",
            selected = !bubble.onlyWithTextField,
            onSelect = { content.onUpdateSettings { it.copy(bubble = it.bubble.copy(onlyWithTextField = false)) } },
        )
        BubbleSizeRow(sizeDp = bubble.sizeDp, onChange = { size -> content.onUpdateSettings { it.copy(bubble = it.bubble.copy(sizeDp = size)) } })
        Hint("Drag it anywhere; drop it on the X to hide it.")
    }
}

@Composable
internal fun RecordingStep(settings: AmethystSettings, content: OnboardingContent, onNext: () -> Unit) {
    val auto = settings.capture.autoStopOnSilence
    StepPage(
        title = "Ending a dictation",
        lead = "How Amber knows you are done.",
        buttons = { ContinueButton(onNext) },
    ) {
        RadioRow(
            "Stop when I pause", "About a second of silence ends it",
            selected = auto,
            onSelect = { content.onUpdateSettings { it.copy(capture = it.capture.copy(autoStopOnSilence = true)) } },
        )
        RadioRow(
            "Run until I tap again", "For long thoughts with pauses",
            selected = !auto,
            onSelect = { content.onUpdateSettings { it.copy(capture = it.capture.copy(autoStopOnSilence = false)) } },
        )
    }
}

@Composable
internal fun ReliabilityStep(setup: SetupState, actions: SetupActions, onNext: () -> Unit) {
    StepPage(
        title = "Keep Amber ready",
        lead = "Optional. Some phones cut the microphone off in the background otherwise.",
        buttons = { ContinueButton(onNext) },
    ) {
        ReliabilityRows(setup, actions)
    }
}

/** What was chosen and where to change it; "Try it" leads to the flame on Home. */
@Composable
internal fun DoneStep(settings: AmethystSettings, download: ModelDownload?, onFinish: () -> Unit) {
    StepPage(
        title = "All set",
        lead = "Change any of this in Settings, top right on the home screen.",
        buttons = { ContinueButton(onFinish, "Try it") },
    ) {
        Chosen("Recognition", settings.activeEngine?.displayName ?: "None", "Settings → Recognition")
        Chosen("Cleanup", settings.cleanupSummary() ?: "Off", "Settings → Cleanup")
        Chosen(
            "Flame",
            if (settings.bubble.onlyWithTextField) "Only when you type" else "Always",
            "Settings → Bubble",
        )
        Chosen(
            "Ending",
            if (settings.capture.autoStopOnSilence) "Stops when you pause" else "Runs until you tap",
            "Settings → Recording",
        )
        Chosen("Everything else", "Inserting, statistics, diagnostics", "Settings")
        if (download != null) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(DownloadNotice.short(download), AmberPalette.Text)
                ProgressLine((download as? ModelDownload.Running)?.let { if (it.total > 0) it.received.toFloat() / it.total else 0f })
                Hint("Amber is ready when it is done; until then the flame says so.")
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
