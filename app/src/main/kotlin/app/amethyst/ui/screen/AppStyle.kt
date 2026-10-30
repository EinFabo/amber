package app.amethyst.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.amethyst.core.engine.CleanupRouting
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanup
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.WritingStyle
import app.amethyst.core.text.ChatCleanup
import app.amethyst.ui.AppIcon
import app.amethyst.ui.AppSource
import app.amethyst.ui.GhostButton
import app.amethyst.ui.Hint
import app.amethyst.ui.RadioRow
import app.amethyst.ui.SectionCard
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberShapes
import kotlinx.coroutines.delay

/** Typing pauses this long before the instruction is saved. */
private const val INSTRUCTION_SAVE_MS = 600L

/**
 * One app's page: how the text reads there (style, own instruction) and who tidies it - the
 * cleanup for every app, none, or a model of its own (a chat app may keep small slips, a notes
 * app gets the best model).
 */
@Composable
fun AppStyleScreen(
    profile: AppProfile,
    settings: AmethystSettings,
    apps: AppSource,
    hasKeyFor: (String?) -> Boolean,
    onUpdateSettings: ((AmethystSettings) -> AmethystSettings) -> Unit,
    onRemoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = rememberCleanupReadiness(settings, hasKeyFor)
    // hasKeyFor() decrypts, so once per settings change rather than on every recomposition.
    val decision = remember(settings) { CleanupRouting.decide(settings, profile.packageName, null, hasKeyFor) }
    var instruction by remember(profile.packageName) { mutableStateOf(profile.instruction.orEmpty()) }
    val own = profile.appCleanup

    // Edits only a profile that is still there, so a late save cannot bring back a removed one.
    fun edit(change: (AppProfile) -> AppProfile) =
        onUpdateSettings { it.copy(profiles = AppProfiles.edit(it.profiles, profile.packageName, change)) }

    fun persistInstruction() {
        val typed = instruction.trim().ifEmpty { null }
        if (typed != profile.instruction) edit { it.copy(instruction = typed) }
    }

    SubPage(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AppIcon(profile.packageName, apps, size = 44.dp)
            Text(profile.label, style = MaterialTheme.typography.titleLarge, color = AmberPalette.Text)
        }

        SectionCard(title = "Style", subtitle = "How the text reads in ${profile.label}.") {
            WritingStyle.entries.forEach { option ->
                RadioRow(option.label(), option.note(), selected = option == profile.style, onSelect = {
                    edit { it.copy(style = option) }
                })
            }
            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it.take(ChatCleanup.INSTRUCTION_CHARS) },
                label = { Text("Own instruction (optional)") },
                placeholder = { Text("e.g. use \"du\", sign with my first name") },
                supportingText = { Text("${instruction.length} / ${ChatCleanup.INSTRUCTION_CHARS}") },
                minLines = 2,
                maxLines = 8,
                shape = AmberShapes.Inner,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard(title = "Cleanup", subtitle = "Who tidies the text in ${profile.label}.") {
            RadioRow(
                "Default",
                settings.cleanupSummary() ?: "Off - as recognized, like every app",
                selected = own.mode == AppCleanupMode.DEFAULT,
                onSelect = { edit { it.copy(appCleanup = AppCleanup()) } },
            )
            RadioRow(
                "Off",
                "Text goes in as recognized",
                selected = own.mode == AppCleanupMode.OFF,
                onSelect = { edit { it.copy(appCleanup = AppCleanup(AppCleanupMode.OFF)) } },
            )
            CleanupModelChoices(settings, ready, selected = own.choice.takeIf { own.mode == AppCleanupMode.MODEL }) { choice ->
                edit { it.copy(appCleanup = AppCleanup(AppCleanupMode.MODEL, choice)) }
            }
        }

        decision.note?.takeIf { own.mode == AppCleanupMode.MODEL }?.let { Hint(it, AmberPalette.Error) }
        if (decision.choice == null && profile.style != WritingStyle.AS_SPOKEN) {
            Hint("Without a cleanup the style is not applied - the text goes in as recognized.", AmberPalette.Primary)
        }
        CleanupLeavesPhoneHint(
            settings.activeEngine?.trustLevelForDisplay(),
            settings.cleanupEngine(decision.choice)?.trustLevelForDisplay(),
        )

        GhostButton("Remove ${profile.label}", {
            onUpdateSettings { it.copy(profiles = it.profiles.filterNot { p -> p.packageName == profile.packageName }) }
            onRemoved()
        }, color = AmberPalette.Error)
    }

    // Saved a moment after typing stops: Home and a dictation right away, or a rotation, must
    // not leave the old instruction in use.
    LaunchedEffect(instruction) {
        delay(INSTRUCTION_SAVE_MS)
        persistInstruction()
    }
    // Typed an instruction and went back at once: keep it, as the engine page does.
    val latestPersist by rememberUpdatedState(::persistInstruction)
    DisposableEffect(profile.packageName) {
        onDispose { latestPersist() }
    }
}
