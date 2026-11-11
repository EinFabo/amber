package app.amethyst.ui.onboarding

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import app.amethyst.core.engine.ServerProvider
import app.amethyst.core.engine.ServerProviders
import app.amethyst.core.onboarding.CleanupPick
import app.amethyst.core.onboarding.CleanupService
import app.amethyst.core.onboarding.EngineNames
import app.amethyst.core.onboarding.ServiceAnswer
import app.amethyst.core.onboarding.ServiceFields
import app.amethyst.core.onboarding.ServiceKey
import app.amethyst.core.onboarding.ServiceRoute
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Tidy the text? Off, Tinfoil with a key, or another service - Claude, Groq and OpenAI told by the key,
 * anything else by its address: an Amber server's own models, another server's list to pick
 * from, or a model's name typed (see [CleanupPick] and [CleanupService]). [onNextWith] moves on
 * as the settings will be once the choice is written, so the styles step appears right after a
 * cleanup was switched on.
 */
@OptIn(ExperimentalFoundationApi::class) // bringIntoViewRequester
@Composable
internal fun CleanupStep(
    settings: AmethystSettings,
    content: OnboardingContent,
    // For the screenshots: the step with a choice made, fields filled and a server's answer.
    initialPick: CleanupPick? = null,
    initialFields: ServiceFields = ServiceFields(),
    initialOtherAddress: Boolean = false,
    initialAnswer: ServiceAnswer? = null,
    onNextWith: (AmethystSettings) -> Unit,
) {
    val tinfoil = EnginePresets.TINFOIL
    val alias = EngineConfig.fromPreset(tinfoil).apiKeyAlias
    val current = settings.cleanup
    var pick by rememberSaveable { mutableStateOf(initialPick ?: CleanupPick.initial(settings)) }
    // Not saveable: a key must never land in the saved-state bundle (security review L1). Nor
    // the address, which can carry an Amber PC's key in its path.
    var key by remember { mutableStateOf("") }
    var fields by remember { mutableStateOf(initialFields) }
    var otherAddress by remember { mutableStateOf(initialOtherAddress) }
    var model by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    // Written once: a second tap on Continue must not add a second server.
    var written by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf(initialAnswer) }
    val hasKey = remember(alias) { content.hasKeyFor(alias) }
    val keyReady = hasKey || key.isNotBlank()
    val read = CleanupService.read(fields.key)
    val asksAddress = CleanupService.asksAddress(fields, otherAddress)
    val addressProblems = CleanupService.addressProblems(fields.address)
    // A server that answered without a ready choice: its model is picked or typed here.
    var query by remember { mutableStateOf("") }
    val chosenPlan = when (val said = answer) {
        is ServiceAnswer.Pick -> CleanupService.picked(said, model)
        is ServiceAnswer.Type -> CleanupService.chosen(said.baseUrl, model)
        else -> null
    }
    val modelFor = answer is ServiceAnswer.Pick || answer is ServiceAnswer.Type
    // A server's answer comes below the fold on a small phone: scrolled to as it arrives.
    val answerInView = remember { BringIntoViewRequester() }
    LaunchedEffect(answer) {
        if (modelFor) {
            withFrameNanos { } // laid out first
            answerInView.bringIntoView()
        }
    }
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    fun write(service: ServiceSetup? = null, serviceKey: String = fields.key) {
        if (written) return
        written = true
        if (pick == CleanupPick.TINFOIL && key.isNotBlank() && alias != null) content.onSaveKey(alias, key.trim())
        // An own server may have no key; a key stored for it before stays.
        if (serviceKey.isNotBlank()) service?.engine?.apiKeyAlias?.let { content.onSaveKey(it, serviceKey.trim()) }
        val phone = PhoneLocales.tags()
        content.onUpdateSettings { pick.applyTo(it, keyReady, phone, service) }
        onNextWith(pick.applyTo(settings, keyReady, phone, service))
    }

    fun writeService(plan: ServicePlan, serviceKey: String = fields.key) =
        write(CleanupService.setup(settings, plan, PhoneLocales.tags()), serviceKey)

    // Any change to the fields makes an earlier answer void.
    fun edit(changed: ServiceFields) {
        fields = changed
        answer = null
        model = ""
        query = ""
    }

    fun check(route: ServiceRoute.Check) {
        // The rows and fields wait meanwhile; the answer counts only for what was asked.
        val asked = fields
        val askedOther = otherAddress
        checking = true
        answer = null
        scope.launch {
            // fetchModels never throws but to cancel; should it all the same, it is no answer.
            val said = try {
                CleanupService.answer(route.address, content.fetchModels(route.address, route.key))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServiceAnswer.Type(route.address, answered = false)
            } finally {
                checking = false
            }
            if (pick != CleanupPick.SERVICE || fields != asked || otherAddress != askedOther) return@launch
            answer = said
            if (said is ServiceAnswer.Ready) writeService(said.plan, asked.key)
        }
    }

    fun apply() {
        if (written || checking) return
        if (pick != CleanupPick.SERVICE) return write()
        if (modelFor) {
            chosenPlan?.let { writeService(it) }
            return
        }
        when (val route = CleanupService.route(fields, otherAddress)) {
            is ServiceRoute.Write -> writeService(route.plan)
            is ServiceRoute.Check -> {
                tried = true
                check(route)
            }
            is ServiceRoute.Fix -> tried = true
            // No key and no address: on without a cleanup.
            ServiceRoute.Skip -> write()
        }
    }

    StepPage(
        title = stringResource(R.string.cleanup_step_title),
        lead = stringResource(R.string.cleanup_step_lead),
        buttons = { ContinueButton(::apply, enabled = !checking && !written && (!modelFor || chosenPlan != null)) },
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
                FieldGroup {
                    KeyField(key, stringResource(R.string.cleanup_step_key)) { key = it }
                    Hint(stringResource(R.string.engine_key_stored))
                    if (key.isBlank()) Hint(stringResource(R.string.cleanup_step_no_key))
                    GhostButton(stringResource(R.string.cleanup_step_get_key), { runCatching { uri.openUri("https://tinfoil.sh") } })
                }
            }
        }
        if (pick == CleanupPick.SERVICE) {
            // An address, an Amber PC's pairing link or its address with the key in it, pasted
            // here, goes where it belongs ([ServiceFields]).
            FieldGroup {
                KeyField(fields.key, stringResource(R.string.engine_api_key), enabled = !checking) { edit(fields.withKey(it)) }
                Hint(stringResource(R.string.engine_key_stored))
                if (!asksAddress) {
                    when (read) {
                        // The service, and the model that will tidy: changed later on the Cleanup page.
                        is ServiceKey.Known -> Recognized(read.quick.provider)
                        ServiceKey.Claude -> Recognized(ServerProviders.ANTHROPIC)
                        else -> Hint(stringResource(R.string.cleanup_step_service_no_key))
                    }
                    // Claude is only ever Anthropic's own address.
                    if (read !is ServiceKey.Claude) {
                        GhostButton(stringResource(R.string.cleanup_step_other_address), { otherAddress = true; answer = null; model = "" })
                    }
                }
            }
            if (asksAddress) {
                FieldGroup {
                    OutlinedTextField(
                        value = fields.address,
                        onValueChange = { edit(fields.withAddress(it)) },
                        enabled = !checking,
                        label = { Text(stringResource(R.string.engine_address)) },
                        placeholder = { Text("http://192.168.1.20:11434/v1") },
                        singleLine = true,
                        shape = AmberShapes.Inner,
                        colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if ((fields.address.isNotBlank() || tried) && addressProblems.isNotEmpty()) {
                        addressProblems.forEach { Hint(words(it), AmberPalette.Error) }
                    } else if (answer == null) {
                        Hint(stringResource(R.string.cleanup_step_address))
                    }
                    // Opened for a Groq or OpenAI key by mistake: one tap back to that service.
                    if (read is ServiceKey.Known && fields.address.isBlank() && !checking) {
                        GhostButton(
                            stringResource(R.string.cleanup_step_back_to, catalog(read.quick.provider.name)),
                            { otherAddress = false; answer = null; model = "" },
                        )
                    }
                    if (checking) ProgressLine(null)
                }
                when (val said = answer) {
                    null, is ServiceAnswer.Ready -> Unit
                    ServiceAnswer.NoCleanupModel -> Hint(stringResource(R.string.cleanup_step_no_model), AmberPalette.Error)
                    is ServiceAnswer.Pick -> FieldGroup(Modifier.bringIntoViewRequester(answerInView)) {
                        // OpenRouter lists hundreds: a long list is searched, a few rows at most.
                        if (CleanupService.searchable(said.models)) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text(stringResource(R.string.cleanup_step_search)) },
                                placeholder = { Text("haiku, llama, qwen ...") }, // English: model names
                                singleLine = true,
                                shape = AmberShapes.Inner,
                                colors = fieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Hint(stringResource(R.string.cleanup_step_search_models, said.models.size), AmberPalette.TrustOnDevice)
                        } else {
                            Hint(stringResource(R.string.cleanup_step_pick_model), AmberPalette.TrustOnDevice)
                        }
                        Column {
                            CleanupService.shownModels(said.models, query).forEach { id ->
                                RadioRow(id, null, selected = model == id, onSelect = { model = id })
                            }
                            // Picked, then searched past: it stays in view as chosen.
                            if (model.isNotEmpty() && model in said.models && model !in CleanupService.shownModels(said.models, query)) {
                                RadioRow(model, null, selected = true, onSelect = {})
                            }
                        }
                    }
                    is ServiceAnswer.Type -> FieldGroup(Modifier.bringIntoViewRequester(answerInView)) {
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = { Text(stringResource(R.string.cleanup_chat_model)) },
                            placeholder = { Text("llama3.2") },
                            singleLine = true,
                            shape = AmberShapes.Inner,
                            colors = fieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Hint(
                            stringResource(if (said.answered) R.string.cleanup_step_type_model else R.string.cleanup_step_no_answer),
                            if (said.answered) AmberPalette.Muted else AmberPalette.Error,
                        )
                    }
                }
            }
        }
        val sendsOut = pick == CleanupPick.TINFOIL || (pick == CleanupPick.SERVICE && (read is ServiceKey.Known || read is ServiceKey.Claude || asksAddress))
        if (sendsOut) {
            if (settings.activeEngine?.trustLevelForDisplay() == TrustLevel.ON_DEVICE) {
                Hint(stringResource(R.string.cleanup_step_leaves), AmberPalette.Primary)
            }
        }
    }
}

/** A field with its notes and buttons: close together, apart from the next choice. */
@Composable
private fun FieldGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** "Recognized: Claude, with Claude Haiku 5.5." - the service a key named, and its recommended cleanup model. */
@Composable
private fun Recognized(provider: ServerProvider) {
    val model = provider.cleanupModels.firstOrNull { it.recommended } ?: provider.cleanupModels.firstOrNull()
    Hint(
        if (model != null) stringResource(R.string.cleanup_step_recognized, catalog(provider.name), model.label)
        else stringResource(R.string.server_recognized, catalog(provider.name)),
        AmberPalette.TrustOnDevice,
    )
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
