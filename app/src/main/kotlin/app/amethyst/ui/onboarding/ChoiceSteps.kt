package app.amethyst.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amethyst.R
import app.amethyst.Texts
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.onboarding.CleanupServices
import app.amethyst.core.onboarding.EngineNames
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.AppCleanupMode
import app.amethyst.core.settings.AppProfiles
import app.amethyst.core.settings.KnownApps
import app.amethyst.ui.AppIcon
import app.amethyst.ui.Hint
import app.amethyst.ui.PendingModel
import app.amethyst.ui.PendingModelNote
import app.amethyst.ui.ProgressLine
import app.amethyst.ui.RadioRow
import app.amethyst.ui.SetupActions
import app.amethyst.ui.SetupState
import app.amethyst.ui.ToggleRow
import app.amethyst.ui.screen.BubbleSizeRow
import app.amethyst.ui.screen.CleanupLeavesPhoneHint
import app.amethyst.ui.screen.CleanupServiceList
import app.amethyst.ui.screen.ServiceSheet
import app.amethyst.ui.screen.ReliabilityRows
import app.amethyst.ui.screen.cleanupSummary
import app.amethyst.ui.screen.label
import app.amethyst.ui.screen.rememberServiceRows
import app.amethyst.ui.screen.trustLevelForDisplay
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.words

/**
 * Tidy the text? Off, or one of the cleanup's services - the same list as Settings → Cleanup
 * ([CleanupServiceList]): a tap on a service opens its sheet with the key and the models, and
 * Use writes the choice at once, as the other steps write theirs. Continue only moves on - on
 * an unchanged choice nothing is rewritten. [onNextWith] gets the settings as they will be once
 * a choice just made is stored, so the styles step appears right after a cleanup was switched on.
 */
@Composable
internal fun CleanupStep(
    settings: AmethystSettings,
    content: OnboardingContent,
    // For the screenshots: a sheet open.
    initialSheet: ServiceSheet? = null,
    onNextWith: (AmethystSettings) -> Unit,
) {
    val access = content.cleanup
    val services = rememberServiceRows(settings, access)
    // A cleanup whose server was removed runs nowhere: shown as off, which is what happens.
    val row = CleanupServices.rowOf(services.rows, settings.cleanup)
    val current = settings.cleanup?.takeIf { row != null }
    val sheet = remember { mutableStateOf(initialSheet) }
    // What a tap here wrote last. It counts while the store has not answered with it - told by
    // the cleanup, the one thing this step writes that decides which step comes next.
    var pending by remember { mutableStateOf<AmethystSettings?>(null) }
    fun asWritten(): AmethystSettings = pending?.takeIf { it.cleanup != settings.cleanup } ?: settings

    StepPage(
        title = stringResource(R.string.cleanup_step_title),
        lead = stringResource(R.string.cleanup_step_lead),
        buttons = { ContinueButton({ onNextWith(asWritten()) }) },
    ) {
        RadioRow(
            stringResource(R.string.off),
            stringResource(R.string.cleanup_off_note),
            selected = current == null,
            onSelect = {
                if (asWritten().cleanup != null) {
                    access.onUpdateSettings { it.copy(cleanup = null) }
                    pending = asWritten().copy(cleanup = null)
                }
            },
        )
        CleanupServiceList(settings, access, services, inUse = current, sheet = sheet, write = CleanupServices::forEveryApp, onWritten = { pending = it })
        CleanupLeavesPhoneHint(settings.activeEngine?.trustLevelForDisplay(), row?.engine?.trustLevelForDisplay())
    }
}

/** Messages casual, mail formal, notes short - the stock styles, for the known apps on this phone ([KnownApps]). */
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
        val here = installed?.let { apps -> KnownApps.ALL.filter { it.packageName in apps }.map(KnownApps::profile) }
        when {
            here == null -> Unit
            here.isEmpty() -> Hint(stringResource(R.string.styles_step_none))
            else -> here.forEach { profile ->
                // Shown as the user has it, if they do; switching it on gives the stock one.
                val own = AppProfiles.forPackage(settings.profiles, profile.packageName)
                val on = own != null
                ToggleRow(
                    label = profile.label,
                    description = listOfNotNull(
                        (own ?: profile).style.label(),
                        stringResource(R.string.styles_cleanup_off).takeIf { (own ?: profile).appCleanup.mode == AppCleanupMode.OFF },
                    ).joinToString(" · "),
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

/**
 * What was chosen - or, on the quick way, decided for this phone - and where to change it;
 * "Try it" leads to the flame on Home. A recognition that still waits for its model is named
 * with one line on what happens until then ([PendingModelNote]).
 */
@Composable
internal fun DoneStep(settings: AmethystSettings, download: ModelDownload?, pending: PendingModel?, onFinish: () -> Unit) {
    StepPage(
        title = stringResource(R.string.done_title),
        lead = stringResource(R.string.done_lead),
        buttons = { ContinueButton(onFinish, stringResource(R.string.done_try)) },
    ) {
        // By the name the engine step gave it, not the one Settings lists it under.
        Chosen(
            stringResource(R.string.settings_recognition),
            pending?.let { stringResource(R.string.pending_then, words(EngineNames.of(it.option))) }
                ?: settings.activeEngine?.let { words(EngineNames.of(it)) } ?: stringResource(R.string.done_none),
            stringResource(R.string.done_where_recognition),
        )
        if (pending != null) PendingModelNote(pending)
        Chosen(stringResource(R.string.settings_cleanup), settings.cleanupSummary() ?: stringResource(R.string.off), stringResource(R.string.done_where_cleanup))
        Chosen(
            stringResource(R.string.settings_bubble),
            stringResource(if (settings.bubble.onlyWithTextField) R.string.bubble_step_typing else R.string.bubble_step_always),
            stringResource(R.string.done_where_bubble),
        )
        Chosen(stringResource(R.string.done_else), stringResource(R.string.done_else_value), stringResource(R.string.done_where_advanced))
        if (download != null && pending == null) {
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
