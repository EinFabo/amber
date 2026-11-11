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
import androidx.compose.runtime.rememberCoroutineScope
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
import app.amethyst.core.onboarding.CleanupService
import app.amethyst.core.onboarding.EngineNames
import app.amethyst.core.onboarding.ServiceAnswer
import app.amethyst.core.onboarding.ServiceFields
import app.amethyst.core.onboarding.ServiceKey
import app.amethyst.core.onboarding.ServicePlan
import app.amethyst.core.onboarding.ServiceSetup
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
import app.amethyst.ui.catalog
import app.amethyst.ui.words
import kotlinx.coroutines.launch

/**
 * Tidy the text? Off, Tinfoil with a key, or another service from its key - Groq and OpenAI
 * told by the key, anything else by its address and its model list (see [CleanupPick] and
 * [CleanupService]). [onNextWith] moves on as the settings will be once the choice is written,
 * so the styles step appears right after a cleanup was switched on.
 */
@Composable
internal fun CleanupStep(
    settings: AmethystSettings,
    content: OnboardingContent,
    // For the screenshots: the step with a choice made and a key typed.
    initialPick: CleanupPick? = null,
    initialServiceKey: String = "",
    onNextWith: (AmethystSettings) -> Unit,
) {
    val tinfoil = EnginePresets.TINFOIL
    val alias = EngineConfig.fromPreset(tinfoil).apiKeyAlias
    val current = settings.cleanup
    var pick by rememberSaveable { mutableStateOf(initialPick ?: CleanupPick.initial(settings)) }
    // Not saveable: a key must never land in the saved-state bundle (security review L1). Nor
    // the address, which can carry an Amber PC's key in its path.
    var key by remember { mutableStateOf("") }
    var fields by remember { mutableStateOf(ServiceFields(key = initialServiceKey)) }
    var tried by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    // Written once: a second tap on Continue must not add a second server.
    var written by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<ServiceAnswer?>(null) }
    val hasKey = remember(alias) { content.hasKeyFor(alias) }
    val keyReady = hasKey || key.isNotBlank()
    val read = CleanupService.read(fields.key)
    val addressProblems = CleanupService.addressProblems(fields.address)
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    fun write(service: ServiceSetup? = null, serviceKey: String = fields.key) {
        if (written) return
        written = true
        if (pick == CleanupPick.TINFOIL && key.isNotBlank() && alias != null) content.onSaveKey(alias, key.trim())
        service?.engine?.apiKeyAlias?.let { content.onSaveKey(it, serviceKey.trim()) }
        val phone = PhoneLocales.tags()
        content.onUpdateSettings { pick.applyTo(it, keyReady, phone, service) }
        onNextWith(pick.applyTo(settings, keyReady, phone, service))
    }

    fun writeService(plan: ServicePlan, serviceKey: String = fields.key) =
        write(CleanupService.setup(settings, plan, PhoneLocales.tags()), serviceKey)

    fun apply() {
        if (written || checking) return
        if (pick != CleanupPick.SERVICE) return write()
        when (read) {
            is ServiceKey.Known -> writeService(CleanupService.known(read.quick))
            ServiceKey.Unknown -> {
                tried = true
                CleanupService.knownAt(fields.address)?.let { return writeService(it) }
                if (addressProblems.isNotEmpty()) return
                // An own server says itself which models it has: an Amber PC or Amber Server.
                // The rows and fields wait meanwhile; the answer counts only for what was asked.
                val asked = fields
                val typed = asked.address.trim()
                checking = true
                answer = null
                scope.launch {
                    val said = CleanupService.answer(typed, content.fetchModels(typed, asked.key.trim()))
                    checking = false
                    if (pick != CleanupPick.SERVICE || fields != asked) return@launch
                    answer = said
                    if (said is ServiceAnswer.Ready) writeService(said.plan, asked.key)
                }
            }
            // No key, or one Amber cannot use yet: on without a cleanup, as the hint says.
            ServiceKey.Blank, ServiceKey.Claude -> write()
        }
    }

    StepPage(
        title = stringResource(R.string.cleanup_step_title),
        lead = stringResource(R.string.cleanup_step_lead),
        buttons = { ContinueButton(::apply, enabled = !checking && !written) },
    ) {
        RadioRow(stringResource(R.string.off), stringResource(R.string.cleanup_off_note), selected = pick == CleanupPick.OFF, onSelect = { if (!checking) pick = CleanupPick.OFF })
        RadioRow(
            "Tinfoil", // English: a name
            stringResource(R.string.cleanup_step_tinfoil_note),
            selected = pick == CleanupPick.TINFOIL,
            onSelect = { if (!checking) pick = CleanupPick.TINFOIL },
        )
        RadioRow(
            stringResource(R.string.cleanup_step_service),
            stringResource(R.string.cleanup_step_service_note),
            selected = pick == CleanupPick.SERVICE,
            onSelect = { if (!checking) pick = CleanupPick.SERVICE },
        )
        if (current != null && current.engineId != tinfoil.id) {
            RadioRow(stringResource(R.string.cleanup_step_keep), settings.cleanupSummary(), selected = pick == CleanupPick.KEEP, onSelect = { if (!checking) pick = CleanupPick.KEEP })
        }
        if (pick == CleanupPick.TINFOIL) {
            if (hasKey) {
                Hint(stringResource(R.string.cleanup_step_key_stored), AmberPalette.TrustOnDevice)
            } else {
                KeyField(key, stringResource(R.string.cleanup_step_key)) { key = it }
                Hint(stringResource(R.string.engine_key_stored))
                GhostButton(stringResource(R.string.cleanup_step_get_key), { runCatching { uri.openUri("https://tinfoil.sh") } })
                if (key.isBlank()) Hint(stringResource(R.string.cleanup_step_no_key))
            }
        }
        if (pick == CleanupPick.SERVICE) {
            // An Amber PC's pairing link, or its address with the key in it, goes apart ([ServiceFields]).
            KeyField(fields.key, stringResource(R.string.engine_api_key), enabled = !checking) { typed ->
                fields = fields.withKey(typed)
                answer = null
            }
            Hint(stringResource(R.string.engine_key_stored))
            when (read) {
                ServiceKey.Blank -> Hint(stringResource(R.string.cleanup_step_no_key))
                ServiceKey.Claude -> Hint(stringResource(R.string.cleanup_step_claude), AmberPalette.Error)
                is ServiceKey.Known -> Hint(stringResource(R.string.server_recognized, catalog(read.quick.provider.name)), AmberPalette.TrustOnDevice)
                ServiceKey.Unknown -> {
                    OutlinedTextField(
                        value = fields.address,
                        onValueChange = { typed ->
                            fields = fields.withAddress(typed)
                            answer = null
                        },
                        enabled = !checking,
                        label = { Text(stringResource(R.string.engine_address)) },
                        placeholder = { Text("http://192.168.1.20:8000/v1") },
                        singleLine = true,
                        shape = AmberShapes.Inner,
                        colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint(stringResource(R.string.cleanup_step_address))
                    if (fields.address.isNotBlank() || tried) addressProblems.forEach { Hint(words(it), AmberPalette.Error) }
                    if (checking) ProgressLine(null)
                    when (answer) {
                        null, is ServiceAnswer.Ready -> Unit
                        ServiceAnswer.NotAmber -> Hint(stringResource(R.string.cleanup_step_not_amber), AmberPalette.Error)
                        ServiceAnswer.NoCleanupModel -> Hint(stringResource(R.string.cleanup_step_no_model), AmberPalette.Error)
                        ServiceAnswer.NoAnswer -> Hint(stringResource(R.string.cleanup_step_no_answer), AmberPalette.Error)
                    }
                }
            }
        }
        val sendsOut = pick == CleanupPick.TINFOIL || (pick == CleanupPick.SERVICE && (read is ServiceKey.Known || read == ServiceKey.Unknown))
        if (sendsOut) {
            if (settings.activeEngine?.trustLevelForDisplay() == TrustLevel.ON_DEVICE) {
                Hint(stringResource(R.string.cleanup_step_leaves), AmberPalette.Primary)
            }
        }
    }
}

/** A key, masked: off screenshots, recordings and other eyes. */
@Composable
private fun KeyField(value: String, label: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        shape = AmberShapes.Inner,
        colors = fieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
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
