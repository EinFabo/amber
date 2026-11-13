package app.amethyst.ui

import app.amethyst.apps.InstalledApp
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import app.amethyst.core.engine.EnginePresets
import app.amethyst.core.insert.InsertionStrategy
import app.amethyst.core.privacy.TrustLevel
import app.amethyst.core.settings.AmethystSettings
import app.amethyst.core.settings.EngineConfig
import app.amethyst.core.stats.DictationStat
import app.amethyst.core.stats.StatsSummary
import app.amethyst.core.text.Transcript
import app.amethyst.dictation.DictationState
import app.amethyst.core.onboarding.FirstRun
import app.amethyst.core.onboarding.OnboardingPath
import app.amethyst.core.onboarding.OnboardingStep
import app.amethyst.core.onboarding.QuickPlan
import app.amethyst.engine.ParakeetState
import app.amethyst.ui.onboarding.OnboardingContent
import app.amethyst.ui.onboarding.OnboardingScreen
import app.amethyst.ui.screen.AppPickerDialog
import app.amethyst.ui.screen.AppStylesScreen
import app.amethyst.ui.screen.BubbleScreen
import app.amethyst.ui.screen.EngineDetailScreen
import app.amethyst.ui.screen.CleanupScreen
import app.amethyst.ui.screen.FallbackScreen
import app.amethyst.ui.screen.RecognitionScreen
import app.amethyst.ui.screen.HomeScreen
import app.amethyst.ui.screen.SettingsScreen
import app.amethyst.ui.screen.StatsScreen
import app.amethyst.ui.screen.UpdatesScreen
import app.amethyst.core.settings.UpdateSettings
import app.amethyst.core.update.ReleaseChannel
import app.amethyst.core.update.UpdateNotice
import app.amethyst.ui.theme.AmberTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.time.Duration
import org.junit.Assert.assertTrue

/** The phone in the screenshots: German first, then English, as they always showed. */
private val PHONE = listOf("de-DE", "en-US")

/**
 * Renders the main screens with sample data into PNGs - the listing screenshots, and a look at
 * the layout without a phone. Real Android drawing (Robolectric's native graphics), a 1080 ×
 * 2340 phone. Written to app/build/screenshots; the numbers are made-up examples.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
open class Screenshots {

    /** English into build/screenshots, a German run ([ScreenshotsGerman]) beside it. */
    private val out = File(if (Locale.getDefault().language == "de") "build/screenshots-de" else "build/screenshots").apply { mkdirs() }

    private val ready = SetupState(
        microphoneGranted = true,
        accessibilityEnabled = true,
        notificationsGranted = true,
        micServiceRunning = true,
        batteryUnrestricted = true,
    )

    private val actions = SetupActions({}, {}, {}, {}, {}, {}, {})

    /** The theme of the picture being taken: dark as the listing shows Amber, or light ([light]). */
    private var dark = true
    private val suffix get() = if (dark) "" else "-light"

    /** [shot] once more in the light theme, into "<name>-light.png". */
    private fun light(shot: () -> Unit) {
        dark = false
        shot()
    }

    private val settings = AmethystSettings.defaults(PHONE).let { base ->
        listOf(EnginePresets.LOCAL_PARAKEET, EnginePresets.LOCAL_WHISPER, EnginePresets.TINFOIL)
            .fold(base) { s, preset -> s.withEngine(EngineConfig.fromPreset(preset, EngineConfig.defaultLanguageTag(preset.kind, PHONE))) }
            .copy(activeEngineId = EnginePresets.LOCAL_PARAKEET.id, profiles = app.amethyst.core.settings.AppProfiles.DEFAULTS)
    }

    private val today = 20_390L

    /** A plausible week: a few dictations a day into the usual apps. */
    private val sample: List<DictationStat> = buildList {
        val apps = listOf("com.whatsapp", "com.google.android.gm", "org.telegram.messenger", "com.google.android.keep")
        val perDay = listOf(4, 7, 0, 5, 9, 6, 8, 3, 6, 9)
        perDay.forEachIndexed { i, n ->
            val day = today - perDay.size + 1 + i
            repeat(n) { k ->
                val words = 18 + (k * 13 + i * 7) % 60
                val tinfoil = (k + i) % 5 == 0
                add(
                    DictationStat(
                        at = day * 86_400_000 + k * 3_600_000,
                        day = day,
                        engine = if (tinfoil) EnginePresets.TINFOIL.id else EnginePresets.LOCAL_PARAKEET.id,
                        trust = if (tinfoil) TrustLevel.ENCLAVE_VERIFIED else TrustLevel.ON_DEVICE,
                        words = words,
                        audioMs = words * 420L,
                        waitMs = if (tinfoil) 4_800 else 650 + k * 40L,
                        app = apps[(k + i) % apps.size],
                        costMicroUsd = if (tinfoil) 90 else null,
                    ),
                )
            }
        }
    }

    private val stats = StatsSummary.of(sample, today)

    @Test
    fun home() = shoot("1-home", "Amber") {
        HomeScreen(
            setup = ready, actions = actions, settings = settings,
            dictationState = MutableStateFlow(DictationState.Idle), stats = stats, download = null,
            onStartTest = {}, onStopTest = {}, onOpenRecognition = {}, onOpenCleanup = {}, onOpenStats = {},
            onOpenActiveEngine = {}, onInstallLanguage = {}, onUseLocalWhisper = {},
        )
    }

    @Test
    fun done() = shoot("2-dictated", "Amber") {
        HomeScreen(
            setup = ready, actions = actions, settings = settings,
            dictationState = MutableStateFlow(
                DictationState.Done(
                    Transcript("Bin in zehn Minuten da, bring den Schlüssel mit.", EnginePresets.LOCAL_PARAKEET.id, TrustLevel.ON_DEVICE, 689),
                    InsertionStrategy.COMMIT_TEXT,
                    "com.whatsapp",
                ),
            ),
            stats = stats, download = null,
            onStartTest = {}, onStopTest = {}, onOpenRecognition = {}, onOpenCleanup = {}, onOpenStats = {},
            onOpenActiveEngine = {}, onInstallLanguage = {}, onUseLocalWhisper = {},
        )
    }

    @Test
    fun statistics() = shoot("3-statistics", "Statistics") {
        StatsScreen(
            settings = settings, stats = stats, today = today,
            engineName = { id -> settings.engines.firstOrNull { it.id == id }?.displayName ?: id },
            appName = { pkg -> APP_NAMES[pkg] ?: pkg },
            onUpdateSettings = {}, onClear = {},
        )
    }

    @Test
    fun engines() = shoot("4-engines", "Recognition") {
        RecognitionScreen(
            settings = settings, hasKeyFor = { true }, medianWaitMs = stats.medianWaitMs,
            onUpdateSettings = {}, onOpenEngine = {},
        )
    }

    @Test
    fun settingsList() = shoot("5-settings", "Settings") {
        SettingsScreen(
            settings = settings, setup = ready, diagnosticsCount = 12, version = "0.12.0", releaseChannel = ReleaseChannel.OTHER,
            onAdvancedChange = {}, onThemeChange = {}, onOpen = {}, onReplayIntroduction = {},
        )
    }

    /** Not part of the listing set: the whole settings list in one picture, "Advanced settings" on. */
    @Test
    @Config(qualifiers = "w360dp-h1160dp-xxhdpi")
    fun settingsTall() = shoot("x-settings-tall", "Settings") {
        SettingsScreen(
            settings = pipeline.copy(activeEngineId = EnginePresets.TINFOIL.id, ui = app.amethyst.core.settings.UiSettings(advanced = true)),
            setup = ready.copy(batteryUnrestricted = false),
            diagnosticsCount = 12, version = "0.20.0", releaseChannel = ReleaseChannel.GITHUB,
            onAdvancedChange = {}, onThemeChange = {}, onOpen = {}, onReplayIntroduction = {},
        )
    }

    /** Not part of the listing set: the simple settings while a release is out, said on top. */
    @Test
    fun settingsAttention() = shoot("x-settings-attention", "Settings") {
        SettingsScreen(
            settings = settings.copy(updates = app.amethyst.core.settings.UpdateSettings(latestVersion = "0.33.0")),
            setup = ready, diagnosticsCount = 12, version = "0.32.0", releaseChannel = ReleaseChannel.GITHUB,
            onAdvancedChange = {}, onThemeChange = {}, onOpen = {}, onReplayIntroduction = {},
        )
    }

    /** Not part of the listing set: the simple settings with two reliability things open, said on top. */
    @Test
    fun settingsAttentionReliability() = shoot("x-settings-attention-reliability", "Settings") {
        SettingsScreen(
            settings = settings, setup = ready.copy(notificationsGranted = false, batteryUnrestricted = false),
            diagnosticsCount = 12, version = "0.32.0", releaseChannel = ReleaseChannel.OTHER,
            onAdvancedChange = {}, onThemeChange = {}, onOpen = {}, onReplayIntroduction = {},
        )
    }

    @Test
    fun appStyles() = shoot("6-app-styles", "App styles") {
        AppStylesScreen(
            settings = settings.copy(
                activeEngineId = EnginePresets.TINFOIL.id,
                cleanup = app.amethyst.core.settings.CleanupChoice(EnginePresets.TINFOIL.id, "deepseek-v4-1-flash"),
            ),
            onUpdateSettings = {}, apps = FakeApps, usage = emptyMap(), onOpenApp = {},
        )
    }

    @Test
    fun bubbleSettings() = shoot("7-bubble", "Bubble") {
        BubbleScreen(
            settings = settings.copy(bubble = settings.bubble.copy(restingOpacity = 0.6f, hiddenIn = listOf("com.whatsapp"))),
            onUpdateSettings = {}, apps = FakeApps, usage = emptyMap(), onOpenQuickMenu = {},
        )
    }

    /** A quick menu put together by hand: Open Amber on top, an empty place, the style actions. */
    private val ownMenu = settings.copy(
        ui = app.amethyst.core.settings.UiSettings(advanced = true),
        bubble = settings.bubble.copy(quickMenuIdle = listOf("open_amber", null, "style_here", "next_style")),
    )

    /** Not part of the listing set: the Bubble page with the advanced settings, leading to the quick menu. */
    @Test
    @Config(qualifiers = "w360dp-h1100dp-xxhdpi")
    fun bubbleSettingsAdvanced() = shoot("x-bubble-advanced", "Bubble") {
        BubbleScreen(settings = ownMenu, onUpdateSettings = {}, apps = FakeApps, usage = emptyMap(), onOpenQuickMenu = {})
    }

    /** Not part of the listing set: the quick menu editor. */
    @Test
    fun quickMenu() = shoot("x-quick-menu", "Quick menu") {
        app.amethyst.ui.screen.QuickMenuScreen(settings = ownMenu, onUpdateSettings = {})
    }

    /** Not part of the listing set: the picker for one place of the quick menu. */
    @Test
    fun quickMenuPicker() = shootDialog("x-quick-menu-picker") {
        app.amethyst.ui.screen.QuickMenuPicker(
            place = 1,
            layout = app.amethyst.core.bubble.QuickMenuLayout.idle(ownMenu.bubble.quickMenuIdle),
            onPick = {},
            onDismiss = {},
        )
    }

    /** Not part of the listing set: the quick menu's "Style here" card over an app, with no cleanup there. */
    @Test
    fun styleHere() = shootBare("x-style-here") {
        QuickStyleCard(
            profile = app.amethyst.core.settings.AppProfile(
                "com.whatsapp", "WhatsApp", app.amethyst.core.settings.WritingStyle.CASUAL, instruction = "Duzen, mit Fabi unterschreiben",
            ),
            cleanupRuns = false,
            apps = FakeApps,
            onEdit = {},
            onDone = {},
            onOpenAmber = {},
        )
    }

    @Test
    fun groqEngine() = shoot("8-groq", "Engine") {
        EngineDetailScreen(
            config = EngineConfig.fromPreset(EnginePresets.GROQ), catalog = null,
            active = true, storedKey = "gsk_example", whisperModels = emptyMap(),
            onSaveKey = { _, _ -> }, onSave = {}, onActivate = {}, onDelete = {},
            onTest = { ConnectionTester.Result.Ok("") }, onDownloadModel = {}, onImportModel = {},
            onCancelModel = {}, onDeleteModel = {}, enclaveLog = emptyList(),
            onVerifyEnclave = { app.amethyst.core.privacy.AttestationResult(false, "") },
            parakeetState = app.amethyst.engine.ParakeetState.Missing,
            onDownloadParakeet = {}, onCancelParakeet = {}, onDeleteParakeet = {},
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h1100dp-xxhdpi")
    fun newServer() = shoot("x-new-server", "Engine") {
        EngineDetailScreen(
            config = app.amethyst.core.engine.Servers.newServer("server-1a2b3c", "de"), catalog = null,
            active = false, storedKey = null, whisperModels = emptyMap(),
            onSaveKey = { _, _ -> }, onSave = {}, onActivate = {}, onDelete = {},
            onTest = { ConnectionTester.Result.Ok("") }, onDownloadModel = {}, onImportModel = {},
            onCancelModel = {}, onDeleteModel = {}, enclaveLog = emptyList(),
            onVerifyEnclave = { app.amethyst.core.privacy.AttestationResult(false, "") },
            parakeetState = app.amethyst.engine.ParakeetState.Missing,
            onDownloadParakeet = {}, onCancelParakeet = {}, onDeleteParakeet = {},
        )
    }

    /**
     * The launcher icon as PNGs, for app/src/main/res/mipmap-*dpi: Android uses the adaptive
     * vector icon, but stores and installers read a bitmap from the APK. Drawn from the same
     * two layers, cropped to the 72 of 108 dp an adaptive icon shows, and cut round like the
     * bubble.
     */
    @Test
    fun launcherIcons() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val background = context.getDrawable(app.amethyst.R.drawable.ic_launcher_background)!!
        val foreground = context.getDrawable(app.amethyst.R.drawable.ic_launcher_foreground)!!
        val dir = File(out, "icons").apply { mkdirs() }
        mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192, "listing" to 512).forEach { (density, size) ->
            for (round in listOf(false, true)) {
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val clip = android.graphics.Path().apply { addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW) }
                canvas.clipPath(clip)
                val inset = size * 18 / 72
                listOf(background, foreground).forEach { layer ->
                    layer.setBounds(-inset, -inset, size + inset, size + inset)
                    layer.draw(canvas)
                }
                val name = if (round) "ic_launcher_round" else "ic_launcher"
                File(dir, "$density-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        assertTrue(File(dir, "listing-ic_launcher.png").length() > 0)
    }

    /** Recognition, cleanup and fallback chosen apart: Whisper on the phone, cleaned up on Tinfoil. */
    private val pipeline = settings.withEngine(EngineConfig.fromPreset(EnginePresets.GROQ)).copy(
        activeEngineId = EnginePresets.LOCAL_WHISPER.id,
        cleanup = app.amethyst.core.settings.CleanupChoice(EnginePresets.TINFOIL.id, "deepseek-v4-1-flash"),
    )

    private val cleanupAccess = app.amethyst.ui.screen.CleanupAccess(hasKeyFor = { true }, onSaveKey = { _, _ -> }, onUpdateSettings = {})

    /** No key stored anywhere: every service one key away. */
    private val noKeys = app.amethyst.ui.screen.CleanupAccess(hasKeyFor = { false }, onSaveKey = { _, _ -> }, onUpdateSettings = {})

    /** Not part of the listing set: the cleanup page. */
    @Test
    fun cleanup() = shoot("x-cleanup", "Cleanup") {
        CleanupScreen(settings = pipeline, access = cleanupAccess)
    }

    @Test
    @Config(qualifiers = "+w411dp-h891dp")
    fun cleanupW411() = shoot("x-cleanup-w411", "Cleanup") {
        CleanupScreen(settings = pipeline, access = cleanupAccess)
    }

    /** Not part of the listing set: the cleanup page on a new phone - off, nothing set up. */
    @Test
    fun cleanupOff() = shoot("x-cleanup-off", "Cleanup") {
        CleanupScreen(settings = settings.copy(engines = settings.engines.filterNot { it.id == EnginePresets.TINFOIL.id }), access = noKeys)
    }

    /** Claude for the cleanup only: its two models, the recognition on the phone. */
    private val claude = app.amethyst.core.settings.EngineConfig(
        id = "server-c1a0de",
        kind = app.amethyst.core.engine.EngineKind.OPENAI_COMPATIBLE,
        displayName = "Claude",
        baseUrl = EnginePresets.ANTHROPIC_BASE_URL,
        apiKeyAlias = "key.server-c1a0de",
        keyHost = "api.anthropic.com",
    )

    private val onClaude = pipeline.withEngine(claude).copy(cleanup = app.amethyst.core.settings.CleanupChoice(claude.id, "claude-haiku-5-5"))

    /** Not part of the listing set: the cleanup page with Claude in use. */
    @Test
    fun cleanupClaude() = shoot("x-cleanup-claude", "Cleanup") {
        CleanupScreen(settings = onClaude, access = cleanupAccess)
    }

    /** A sheet over the cleanup page: a dialog window of its own. */
    private fun cleanupSheet(name: String, settings: AmethystSettings, access: app.amethyst.ui.screen.CleanupAccess, sheet: app.amethyst.ui.screen.ServiceSheet) =
        shootDialog("x-cleanup-sheet-$name") {
            AmberBackground { CleanupScreen(settings = settings, access = access, initialSheet = sheet) }
        }

    /** Not part of the listing set: the sheets - a service in use, one that needs its key, a key of another service, a new own server. */
    @Test
    fun cleanupSheetTinfoil() = cleanupSheet("tinfoil", pipeline, cleanupAccess, app.amethyst.ui.screen.ServiceSheet.Of(EnginePresets.TINFOIL.id))

    @Test
    fun cleanupSheetClaudeNew() = cleanupSheet("claude-new", pipeline, noKeys, app.amethyst.ui.screen.ServiceSheet.Of("CLAUDE"))

    @Test
    @Config(qualifiers = "+w411dp-h891dp")
    fun cleanupSheetClaudeNewW411() = cleanupSheet("claude-new-w411", pipeline, noKeys, app.amethyst.ui.screen.ServiceSheet.Of("CLAUDE"))

    @Test
    fun cleanupSheetOtherKey() = cleanupSheet("other-key", pipeline, noKeys, app.amethyst.ui.screen.ServiceSheet.Of("CLAUDE", key = "gsk_abc"))

    @Test
    fun cleanupSheetNewServer() = cleanupSheet("new-server", pipeline, cleanupAccess, app.amethyst.ui.screen.ServiceSheet.NewServer())

    /** An own server that turned out to be an Amber PC, with the one model of each kind it uses. */
    private val amberPc = EngineConfig.fromPreset(EnginePresets.SELF_HOSTED)
        .copy(baseUrl = "http://192.168.1.20:8765/k3y/v1", modelId = "acft-large-v3-turbo-q8_0")

    private val amberPcCatalog = app.amethyst.core.engine.AmberCatalog(
        service = "amber-pc",
        version = "0.4.2",
        speech = listOf(EnginePresets.ModelChoice("acft-large-v3-turbo-q8_0", "Large v3 Turbo", "on this PC · NVIDIA GPU (CUDA)", recommended = true)),
        cleanup = listOf(EnginePresets.ModelChoice("qwen3-4b-instruct-2507-q4km", "Qwen3 4B Instruct", "on this PC · NVIDIA GPU (CUDA)", recommended = true)),
    )

    /** Not part of the listing set: the engine page of an Amber PC, its models listed. */
    @Test
    @Config(qualifiers = "w360dp-h1100dp-xxhdpi")
    fun amberPcEngine() = shoot("x-amber-pc-engine", "Engine") {
        EngineDetailScreen(
            config = amberPc, catalog = amberPcCatalog,
            active = true, storedKey = null, whisperModels = emptyMap(),
            onSaveKey = { _, _ -> }, onSave = {}, onActivate = {}, onDelete = {},
            onTest = { ConnectionTester.Result.Ok("") }, onDownloadModel = {}, onImportModel = {},
            onCancelModel = {}, onDeleteModel = {}, enclaveLog = emptyList(),
            onVerifyEnclave = { app.amethyst.core.privacy.AttestationResult(false, "") },
            parakeetState = app.amethyst.engine.ParakeetState.Missing,
            onDownloadParakeet = {}, onCancelParakeet = {}, onDeleteParakeet = {},
        )
    }

    /**
     * Not part of the listing set: the cleanup page with the Amber PC's list - the model chosen
     * earlier is no longer on it, and says so.
     */
    @Test
    fun amberPcCleanup() = shoot("x-amber-pc-cleanup", "Cleanup") {
        CleanupScreen(settings = onAmberPc, access = cleanupAccess)
    }

    private val onAmberPc get() = pipeline.withEngine(amberPc).copy(
        activeEngineId = amberPc.id,
        cleanup = app.amethyst.core.settings.CleanupChoice(amberPc.id, "qwen2.5-3b-instruct-q4km"),
        serverCatalogs = mapOf(amberPc.id to app.amethyst.core.settings.StoredCatalog.of(amberPc.baseUrl!!, amberPcCatalog)),
    )

    /** Not part of the listing set: the Amber PC's sheet - its own list, the model in use no longer on it. */
    @Test
    fun cleanupSheetAmberPc() = cleanupSheet("amber-pc", onAmberPc, cleanupAccess, app.amethyst.ui.screen.ServiceSheet.Of(amberPc.id))

    /** Keep with a cleanup model of its own. */
    private val keep = app.amethyst.core.settings.AppProfile(
        "com.google.android.keep", "Keep", app.amethyst.core.settings.WritingStyle.CONCISE,
        appCleanup = app.amethyst.core.settings.AppCleanup(
            app.amethyst.core.settings.AppCleanupMode.MODEL,
            app.amethyst.core.settings.CleanupChoice(EnginePresets.TINFOIL.id, "gemma4-31b"),
        ),
    )

    @Composable
    private fun KeepStyle() = app.amethyst.ui.screen.AppStyleScreen(
        profile = keep, settings = pipeline.copy(profiles = listOf(keep)), apps = FakeApps, access = cleanupAccess,
        modelReady = { true }, onUpdateSettings = {}, onRemoved = {},
    )

    /** Not part of the listing set: one app's page with a cleanup model of its own. */
    @Test
    fun appStyle() = shoot("x-app-style", "App style") { KeepStyle() }

    /** Not part of the listing set: the same page in one picture, down to removing the app. */
    @Test
    @Config(qualifiers = "w360dp-h1480dp-xxhdpi")
    fun appStyleTall() = shoot("x-app-style-tall", "App style") { KeepStyle() }

    /** Not part of the listing set: the voice commands page, switched on. */
    @Test
    fun voiceCommands() = shoot("x-voice-commands", "Voice commands") {
        app.amethyst.ui.screen.VoiceCommandsScreen(
            settings = settings.copy(commands = app.amethyst.core.settings.CommandSettings(enabled = true)),
            onUpdateSettings = {},
        )
    }

    /** Not part of the listing set: the whole voice commands page in one picture, every kind of command. */
    @Test
    @Config(qualifiers = "w360dp-h1500dp-xxhdpi")
    fun voiceCommandsTall() = shoot("x-voice-commands-tall", "Voice commands") {
        app.amethyst.ui.screen.VoiceCommandsScreen(
            settings = settings.copy(commands = app.amethyst.core.settings.CommandSettings(enabled = true)),
            onUpdateSettings = {},
        )
    }

    /** Not part of the listing set: what switching voice commands on asks first. */
    @Test
    fun voiceCommandsConsent() = shootDialog("x-voice-commands-consent") {
        app.amethyst.ui.screen.VoiceCommandsConsent(onAgree = {}, onDismiss = {})
    }

    /** Not part of the listing set: the recording page. */
    @Test
    fun recording() = shoot("x-recording", "Recording") {
        app.amethyst.ui.screen.RecordingScreen(settings = settings, onUpdateSettings = {})
    }

    /** Not part of the listing set: the inserting page. */
    @Test
    fun inserting() = shoot("x-inserting", "Inserting") {
        app.amethyst.ui.screen.InsertingScreen(settings = settings, onUpdateSettings = {})
    }

    /** Not part of the listing set: the reliability page with the battery still restricted. */
    @Test
    fun reliability() = shoot("x-reliability", "Reliability") {
        app.amethyst.ui.screen.ReliabilityScreen(setup = ready.copy(batteryUnrestricted = false), actions = actions)
    }

    /** Not part of the listing set: the diagnostics page before the first dictation. */
    @Test
    fun diagnostics() = shoot("x-diagnostics", "Diagnostics") {
        app.amethyst.ui.screen.DiagnosticsScreen(records = emptyList(), keyboardLog = emptyList(), onClear = {})
    }

    /** A few commands as Diagnostics shows them, the newest open to its steps. */
    private val commandLog = app.amethyst.core.dictation.DictationLog { 0L }.apply {
        val apps = listOf("WhatsApp", "Telegram", "Telefon", "Google Maps", "Spotify")
        fun explain(heard: String) = app.amethyst.core.commands.VoiceCommands.explain(heard) { apps }
        val timing = "route BUBBLE/RECORD_NOW · start 0.1 s · recording 1.3 s · recognition 0.4 s, model was loaded"
        recordSuccess(
            Transcript("Bin in zehn Minuten da.", engineId = EnginePresets.LOCAL_PARAKEET.id, trustLevel = TrustLevel.ON_DEVICE, latencyMs = 410),
            InsertionStrategy.SET_TEXT, inserted = true, targetPackage = "com.whatsapp", timing = timing,
        )
        recordCommand(
            "Tele", app.amethyst.core.commands.CommandOutcome.Failed("Not a command: “Tele”. Try “open WhatsApp” or “go back”."),
            EnginePresets.LOCAL_PARAKEET.id, timing, details = explain("Tele") + "outcome: failed",
        )
        recordCommand(
            "Öffne Kalender", app.amethyst.core.commands.CommandOutcome.Failed("No app called “kalender”."), EnginePresets.LOCAL_PARAKEET.id, timing,
            details = explain("Öffne Kalender") + listOf(
                "gate: passed (commands on, phone unlocked)",
                "app search: “kalender” among 84 launcher apps",
                "NotFound: no exact name, start, word inside or near miss",
                "outcome: failed",
            ),
        )
        recordCommand(
            "Scroll zehnmal nach unten", app.amethyst.core.commands.CommandOutcome.Done("Scrolled down 4 times - nothing more to scroll"),
            EnginePresets.LOCAL_PARAKEET.id, timing,
            details = explain("Scroll zehnmal nach unten") + listOf(
                "gate: passed (commands on, phone unlocked)",
                "scrollable nodes: 2",
                "picked: RecyclerView, vertical, in the window in use, node action DOWN",
                "step 1: moved", "step 2: moved", "step 3: moved", "step 4: moved",
                "step 5: end of the list - it offers down no more",
                "stopped at step 5 of 10: end of the list - it offers down no more",
                "outcome: done",
            ),
        )
        recordCommand(
            "Alles markieren und kopieren", app.amethyst.core.commands.CommandOutcome.Done("Selected all, copied"),
            EnginePresets.LOCAL_PARAKEET.id, timing,
            details = explain("Alles markieren und kopieren") + listOf(
                "step 1/2: Edit SELECT_ALL",
                "gate: passed (commands on, phone unlocked)",
                "target check: same field",
                "field: the input-focus node (EditText) in com.whatsapp",
                "step 2/2: Edit COPY",
                "gate: passed (commands on, phone unlocked)",
                "target check: same field",
                "field: the input-focus node (EditText) in com.whatsapp",
                "outcome: done, all 2 steps",
            ),
        )
    }.records.value

    /** Not part of the listing set: what a voice command leaves in Diagnostics, one opened to its steps. */
    @Test
    @Config(qualifiers = "w360dp-h1400dp-xxhdpi")
    fun diagnosticsCommands() = shoot("x-diagnostics-commands", "Diagnostics") {
        val newest = 1_793_455_351_000L
        app.amethyst.ui.screen.DiagnosticsScreen(
            records = commandLog.mapIndexed { i, record -> record.copy(atMillis = newest - i * 47_000L) },
            keyboardLog = emptyList(),
            onClear = {},
            // Two commands in one go, and "Tele", which the rules did not take.
            initiallyOpen = setOf(newest, newest - 3 * 47_000L),
        )
    }

    /** Not part of the listing set: the fallback page, for a Tinfoil recognition. */
    @Test
    fun fallback() = shoot("x-fallback", "When the server fails") {
        FallbackScreen(settings = pipeline.copy(activeEngineId = EnginePresets.TINFOIL.id), onUpdateSettings = {})
    }

    /** Not part of the listing set: the settings list with the whole pipeline in use. */
    @Test
    fun settingsPipeline() = shoot("x-settings-pipeline", "Settings") {
        SettingsScreen(
            settings = pipeline.copy(activeEngineId = EnginePresets.TINFOIL.id), setup = ready, diagnosticsCount = 12, version = "0.17.1",
            releaseChannel = ReleaseChannel.OTHER, onAdvancedChange = {}, onThemeChange = {}, onOpen = {}, onReplayIntroduction = {},
        )
    }

    /** Not part of the listing set: home with a cleanup in use. */
    @Test
    fun homePipeline() = shoot("x-home-pipeline", "Amber") {
        HomeScreen(
            setup = ready, actions = actions, settings = pipeline,
            dictationState = MutableStateFlow(DictationState.Idle), stats = stats, download = null,
            onStartTest = {}, onStopTest = {}, onOpenRecognition = {}, onOpenCleanup = {}, onOpenStats = {},
            onOpenActiveEngine = {}, onInstallLanguage = {}, onUseLocalWhisper = {},
        )
    }

    /** Not part of the listing set: Parakeet still downloading after the introduction. */
    @Test
    fun homeDownloading() = shoot("x-home-downloading", "Amber") {
        HomeScreen(
            setup = ready, actions = actions, settings = settings,
            dictationState = MutableStateFlow(DictationState.Idle), stats = stats,
            download = app.amethyst.core.engine.ModelDownload.Running(300_000_000, 670_000_000),
            onStartTest = {}, onStopTest = {}, onOpenRecognition = {}, onOpenCleanup = {}, onOpenStats = {},
            onOpenActiveEngine = {}, onInstallLanguage = {}, onUseLocalWhisper = {},
        )
    }

    /** Not part of the listing set: a sideloaded first run, back from Android's settings without the service. */
    @Test
    @Config(qualifiers = "w360dp-h1300dp-xxhdpi")
    fun homeSetup() = shoot("x-home-setup", "Amber") {
        HomeScreen(
            setup = SetupState(microphoneGranted = true, restrictedSettings = true, accessibilityTried = true),
            actions = actions, settings = settings,
            dictationState = MutableStateFlow(DictationState.Idle), stats = null, download = null,
            onStartTest = {}, onStopTest = {}, onOpenRecognition = {}, onOpenCleanup = {}, onOpenStats = {},
            onOpenActiveEngine = {}, onInstallLanguage = {}, onUseLocalWhisper = {},
        )
    }

    /** Not part of the listing set: Home's way to the service, the same rows as the introduction. */
    @Test
    fun homeDisclosure() = shootDialog("x-home-disclosure") {
        AccessibilityConsentDialog(restricted = true, onAgree = {}, onDismiss = {})
    }

    /** A fresh install: nothing granted yet. */
    private val fresh = SetupState()

    /** A Pixel-like phone: room for every engine. */
    private fun content(
        parakeet: ParakeetState = ParakeetState.Missing,
        metered: Boolean = false,
        download: app.amethyst.core.engine.ModelDownload? = null,
        bubbleEvents: List<app.amethyst.core.bubble.BubbleEvent> = emptyList(),
        pending: PendingModel? = null,
    ) = OnboardingContent(
        phone = app.amethyst.core.engine.PhoneFacts(totalRamMb = 8_000, freeStorageMb = 20_000, whisperCpu = true, androidOnDevice = true),
        metered = metered,
        parakeet = parakeet,
        whisper = emptyMap(),
        waitingFor = null,
        onChooseEngine = { _, _ -> },
        onWaitForWifi = {},
        onOpenRecognition = {},
        download = download,
        apps = FakeApps,
        onUpdateSettings = {},
        cleanup = noKeys,
        bubbleEvents = kotlinx.coroutines.flow.flowOf(*bubbleEvents.toTypedArray()),
        pending = pending,
    )

    private fun onboarding(
        name: String,
        step: OnboardingStep,
        setup: SetupState = fresh,
        settings: AmethystSettings = AmethystSettings.defaults(PHONE),
        content: OnboardingContent = content(),
        path: OnboardingPath = OnboardingPath.DEEP,
    ) = shoot("x-onboarding-$name", null) {
        OnboardingScreen(step = step, settings = settings, setup = setup, actions = actions, content = content, onStep = {}, onFinish = {}, path = path)
    }

    @Test
    fun onboardingWelcome() = onboarding("1-welcome", OnboardingStep.WELCOME)

    @Test
    fun onboardingAccessibility() = onboarding("2-accessibility", OnboardingStep.ACCESSIBILITY)

    /** The whole page before the button, rows and notes - taller than a phone. */
    @Test
    @Config(qualifiers = "w360dp-h1560dp-xxhdpi")
    fun onboardingAccessibilityTall() = onboarding("2-accessibility-tall", OnboardingStep.ACCESSIBILITY, fresh.copy(restrictedSettings = true))

    /** Installed from a file on Android 13+: the step says before the button that Android may block it. */
    @Test
    fun onboardingAccessibilitySideloaded() = onboarding("2-accessibility-sideloaded", OnboardingStep.ACCESSIBILITY, fresh.copy(restrictedSettings = true))

    /** Sent to the settings, back without the service on (or on the way there). */
    @Test
    fun onboardingAccessibilityWaiting() = onboarding("2-accessibility-waiting", OnboardingStep.ACCESSIBILITY, fresh.copy(accessibilityTried = true))

    /** The same on an install Android may lock: the way past "Restricted setting". */
    @Test
    @Config(qualifiers = "w360dp-h900dp-xxhdpi")
    fun onboardingAccessibilityRestricted() = onboarding(
        "2-accessibility-restricted", OnboardingStep.ACCESSIBILITY,
        fresh.copy(accessibilityTried = true, restrictedSettings = true),
    )

    @Test
    fun onboardingAccessibilityDone() = onboarding("2-accessibility-done", OnboardingStep.ACCESSIBILITY, fresh.copy(accessibilityEnabled = true))

    @Test
    fun onboardingMicrophone() = onboarding("3-microphone", OnboardingStep.MICROPHONE)

    @Test
    fun onboardingEngine() = onboarding("4-engine", OnboardingStep.ENGINE, ready)

    @Test
    fun onboardingEngineDownloading() = onboarding(
        "4-engine-downloading", OnboardingStep.ENGINE, ready,
        settings = AmethystSettings.defaults(PHONE).withEngine(EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)).copy(activeEngineId = EnginePresets.LOCAL_PARAKEET.id),
        content = content(parakeet = app.amethyst.engine.ParakeetState.Busy(300_000_000, 670_000_000)),
    )

    /** A replay by someone dictating with Groq: the step keeps it instead of forcing a download. */
    @Test
    fun onboardingEngineKept() = onboarding(
        "4-engine-kept", OnboardingStep.ENGINE, ready,
        settings = AmethystSettings.defaults(PHONE).withEngine(EngineConfig.fromPreset(EnginePresets.GROQ))
            .copy(activeEngineId = EnginePresets.GROQ.id, onboardingDone = true),
    )

    @Test
    fun onboardingEngineMetered() = onboarding("4-engine-metered", OnboardingStep.ENGINE, ready, content = content(metered = true))

    /** A new install on its way through the introduction: the known apps on the phone have their styles. */
    private val parakeetActive = AmethystSettings.defaults(PHONE)
        .withEngine(EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET))
        .copy(activeEngineId = EnginePresets.LOCAL_PARAKEET.id, profiles = app.amethyst.core.settings.AppProfiles.defaultsFor(APP_NAMES.keys))

    @Test
    fun onboardingCleanup() = onboarding("5-cleanup", OnboardingStep.CLEANUP, ready, parakeetActive)

    /** The step with a sheet open over it: a dialog window of its own. */
    private fun cleanupStepSheet(name: String, sheet: app.amethyst.ui.screen.ServiceSheet) = shootDialog("x-onboarding-5-cleanup-$name") {
        AmberBackground { app.amethyst.ui.onboarding.CleanupStep(parakeetActive, content(), initialSheet = sheet) {} }
    }

    /** Not part of the listing set: the step's sheets - a service's key and models, a new own server. */
    @Test fun onboardingCleanupClaude() = cleanupStepSheet("claude", app.amethyst.ui.screen.ServiceSheet.Of("CLAUDE"))
    @Test fun onboardingCleanupTinfoil() = cleanupStepSheet("tinfoil", app.amethyst.ui.screen.ServiceSheet.Of("TINFOIL"))
    @Test fun onboardingCleanupNewServer() = cleanupStepSheet("new-server", app.amethyst.ui.screen.ServiceSheet.NewServer())

    /** Not part of the listing set: the step on a replay, a cleanup in use. */
    @Test
    fun onboardingCleanupInUse() = onboarding(
        "5-cleanup-in-use", OnboardingStep.CLEANUP, ready,
        parakeetActive.withEngine(EngineConfig.fromPreset(EnginePresets.TINFOIL))
            .copy(cleanup = app.amethyst.core.settings.CleanupChoice(EnginePresets.TINFOIL.id, "deepseek-v4-1-flash"), onboardingDone = true),
    )

    @Test
    @Config(qualifiers = "+w411dp-h891dp")
    fun onboardingCleanupW411() = onboarding("5-cleanup-w411", OnboardingStep.CLEANUP, ready, parakeetActive)

    /**
     * Not part of the listing set: an own server's sheet in each case - its list to pick from, a
     * long one searched, the model's name typed, no answer.
     */
    private fun newServer(name: String, fields: app.amethyst.core.onboarding.ServiceFields, answer: app.amethyst.core.onboarding.ServiceAnswer?) =
        shootBare("x-cleanup-new-server-$name") {
            Box(Modifier.fillMaxSize().background(app.amethyst.ui.theme.AmberPalette.Surface).padding(top = 48.dp)) {
                app.amethyst.ui.screen.NewServerSheetBody(
                    settings = parakeetActive, access = cleanupAccess, carriedKey = "", onSwitch = { _, _ -> }, onUse = { _, _ -> },
                    initialFields = fields, initialAnswer = answer,
                )
            }
        }

    @Test fun newServerModelList() = newServer(
        "model-list", app.amethyst.core.onboarding.ServiceFields("", OLLAMA),
        app.amethyst.core.onboarding.ServiceAnswer.Pick(OLLAMA, listOf("llama3.2:3b", "qwen3:4b", "gemma3:4b")),
    )
    @Test fun newServerLongList() = newServer(
        "long-list", app.amethyst.core.onboarding.ServiceFields("sk-or-v1-abc", OPENROUTER),
        app.amethyst.core.onboarding.ServiceAnswer.Pick(OPENROUTER, (1..320).map { "vendor-${it % 7}/model-$it" }),
    )
    @Test fun newServerTypedModel() = newServer(
        "typed-model", app.amethyst.core.onboarding.ServiceFields("", OLLAMA),
        app.amethyst.core.onboarding.ServiceAnswer.Type(OLLAMA, answered = true),
    )
    @Test fun newServerNoAnswer() = newServer(
        "no-answer", app.amethyst.core.onboarding.ServiceFields("", OLLAMA),
        app.amethyst.core.onboarding.ServiceAnswer.Type(OLLAMA, answered = false),
    )
    @Test fun newServerKnownAddress() = newServer(
        "known-address", app.amethyst.core.onboarding.ServiceFields("gsk_abc", "https://api.groq.com/openai/v1"), null,
    )
    @Test fun newServerAmberPc() = newServer(
        "amber-pc", app.amethyst.core.onboarding.ServiceFields("", "http://192.168.1.20:8765/v1"),
        app.amethyst.core.onboarding.CleanupService.answer("http://192.168.1.20:8765/v1", app.amethyst.core.engine.ModelList.Amber(amberPcCatalog)),
    )

    @Test
    fun onboardingStyles() = onboarding(
        "6-styles", OnboardingStep.APP_STYLES, ready,
        parakeetActive.copy(cleanup = app.amethyst.core.settings.CleanupChoice(EnginePresets.TINFOIL.id, "deepseek-v4-1-flash")),
    )

    @Test
    fun onboardingBubble() = onboarding("7-bubble", OnboardingStep.BUBBLE, ready, parakeetActive)

    private val parakeetReady = ParakeetState.Ready(670_000_000)

    /** The practice step as it opens: the field focused, nothing tried yet. */
    @Test
    fun onboardingPractice() = onboarding("7b-practice", OnboardingStep.PRACTICE, ready, parakeetActive, content(parakeet = parakeetReady))

    /** A dictation went in and Copy last ran; the drag is next. */
    @Test
    fun onboardingPracticeTwoDone() = onboarding(
        "7b-practice-two-done", OnboardingStep.PRACTICE, ready, parakeetActive,
        content(
            parakeet = parakeetReady,
            bubbleEvents = listOf(
                app.amethyst.core.bubble.BubbleEvent.DictationInserted,
                app.amethyst.core.bubble.BubbleEvent.QuickActionRun(app.amethyst.core.bubble.QuickAction.COPY_LAST),
            ),
        ),
    )

    /** All three seen: the success line, Continue filled. */
    @Test
    fun onboardingPracticeAllDone() = onboarding(
        "7b-practice-all-done", OnboardingStep.PRACTICE, ready, parakeetActive,
        content(
            parakeet = parakeetReady,
            bubbleEvents = listOf(
                app.amethyst.core.bubble.BubbleEvent.DictationInserted,
                app.amethyst.core.bubble.BubbleEvent.QuickActionRun(app.amethyst.core.bubble.QuickAction.COPY_LAST),
                app.amethyst.core.bubble.BubbleEvent.Docked(app.amethyst.core.bubble.BubbleSide.LEFT),
            ),
        ),
    )

    /** "Not now" on the service: no flame, so the step says so and offers the way back. */
    @Test
    fun onboardingPracticeNoService() = onboarding(
        "7b-practice-no-service", OnboardingStep.PRACTICE, fresh.copy(microphoneGranted = true), parakeetActive, content(parakeet = parakeetReady),
    )

    /** The model still on its way: the tap waits for it, the drag does not. */
    @Test
    fun onboardingPracticeDownloading() = onboarding(
        "7b-practice-downloading", OnboardingStep.PRACTICE, ready, parakeetActive,
        content(parakeet = ParakeetState.Busy(300_000_000, 670_000_000), download = app.amethyst.core.engine.ModelDownload.Running(300_000_000, 670_000_000)),
    )

    @Test
    fun onboardingReliability() = onboarding("8-reliability", OnboardingStep.RELIABILITY, fresh.copy(microphoneGranted = true, accessibilityEnabled = true), parakeetActive)

    @Test
    fun onboardingDone() = onboarding(
        "9-done", OnboardingStep.DONE, ready, parakeetActive,
        content = content(download = app.amethyst.core.engine.ModelDownload.Running(520_000_000, 670_000_000)),
    )

    /** The quick setup on a phone with room: Android's recognition until Parakeet is there. */
    private val quick = FirstRun.quick(
        AmethystSettings.defaults(PHONE),
        QuickPlan(app.amethyst.core.engine.EngineOption.ANDROID, app.amethyst.core.engine.EngineOption.PARAKEET),
        installed = APP_NAMES.keys, phoneTags = PHONE,
    )

    private fun parakeetPending(download: app.amethyst.core.engine.ModelDownload?, failed: Boolean = false) =
        PendingModel(app.amethyst.core.engine.EngineOption.PARAKEET, download, failed) {}

    private val parakeetLoading = parakeetPending(app.amethyst.core.engine.ModelDownload.Running(300_000_000, 670_000_000))

    /** The quick way's steps count to five. */
    @Test
    fun onboardingQuickAccessibility() = onboarding("q2-accessibility", OnboardingStep.ACCESSIBILITY, settings = quick, path = OnboardingPath.QUICK)

    /** The try right after the permissions, Android's recognition hearing it. */
    @Test
    fun onboardingQuickPractice() = onboarding(
        "q4-practice", OnboardingStep.PRACTICE, ready, quick, content(pending = parakeetLoading), OnboardingPath.QUICK,
    )

    /** The quick way's last step: what was decided, and one line on the download. */
    @Test
    fun onboardingQuickDone() = onboarding(
        "q5-done", OnboardingStep.DONE, ready, quick, content(pending = parakeetLoading), OnboardingPath.QUICK,
    )

    /** The same on mobile data: the download waits for Wi-Fi, with the way to start it anyway. */
    @Test
    fun onboardingQuickDoneWifi() = onboarding(
        "q5-done-wifi", OnboardingStep.DONE, ready, quick,
        content(metered = true, pending = parakeetPending(app.amethyst.core.engine.ModelDownload.WaitingForWifi)), OnboardingPath.QUICK,
    )

    /** The download gave up. */
    @Test
    fun onboardingQuickDoneFailed() = onboarding(
        "q5-done-failed", OnboardingStep.DONE, ready, quick, content(pending = parakeetPending(null, failed = true)), OnboardingPath.QUICK,
    )

    /** Not part of the listing set: Home after "Skip the introduction" - what is missing, and the download held for Wi-Fi. */
    @Test
    @Config(qualifiers = "w360dp-h1100dp-xxhdpi")
    fun homeSkipped() = shoot("x-home-skipped", "Amber") {
        HomeScreen(
            setup = fresh, actions = actions, settings = quick.copy(onboardingDone = true),
            dictationState = MutableStateFlow(DictationState.Idle), stats = null, download = null,
            onStartTest = {}, onStopTest = {}, onOpenRecognition = {}, onOpenCleanup = {}, onOpenStats = {},
            onOpenActiveEngine = {}, onInstallLanguage = {}, onUseLocalWhisper = {},
            pending = parakeetPending(app.amethyst.core.engine.ModelDownload.WaitingForWifi),
        )
    }

    /** What the GitHub build knows after a check three hours ago: 0.24.0 is out. */
    private val updateSeen = UpdateSettings(
        lastCheckedAt = NOW - 3 * 3_600_000L,
        latestVersion = "0.24.0",
        latestUrl = "https://github.com/EinFabo/amber/releases/tag/v0.24.0",
        notifiedVersion = "0.24.0",
    )

    /** Not part of the listing set: the Updates page of the GitHub build, a newer release found. */
    @Test
    fun updates() = shoot("x-updates", "Updates") {
        UpdatesScreen(
            settings = settings.copy(updates = updateSeen), channel = ReleaseChannel.GITHUB, installedVersion = "0.23.0",
            checking = false, now = NOW, onUpdateSettings = {}, onCheckNow = {}, onOpenUrl = {},
        )
    }

    /** Not part of the listing set: the Updates page after a failed check, switched off. */
    @Test
    fun updatesOff() = shoot("x-updates-off", "Updates") {
        UpdatesScreen(
            settings = settings.copy(updates = updateSeen.copy(enabled = false, lastCheckFailed = true, lastCheckedAt = NOW - 26 * 3_600_000L)),
            channel = ReleaseChannel.GITHUB, installedVersion = "0.24.0",
            checking = false, now = NOW, onUpdateSettings = {}, onCheckNow = {}, onOpenUrl = {},
        )
    }

    /** Not part of the listing set: the Updates page of a build that does not check. */
    @Test
    fun updatesElsewhere() = shoot("x-updates-elsewhere", "Updates") {
        UpdatesScreen(
            settings = settings, channel = ReleaseChannel.OTHER, installedVersion = "0.23.0",
            checking = false, now = NOW, onUpdateSettings = {}, onCheckNow = {}, onOpenUrl = {},
        )
    }

    /** Not part of the listing set: home with a newer release on GitHub. */
    @Test
    fun homeUpdate() = shoot("x-home-update", "Amber") {
        HomeScreen(
            setup = ready, actions = actions, settings = settings,
            dictationState = MutableStateFlow(DictationState.Idle), stats = stats, download = null,
            onStartTest = {}, onStopTest = {}, onOpenRecognition = {}, onOpenCleanup = {}, onOpenStats = {},
            onOpenActiveEngine = {}, onInstallLanguage = {}, onUseLocalWhisper = {},
            update = UpdateNotice("0.24.0", "https://github.com/EinFabo/amber/releases/tag/v0.24.0"),
        )
    }

    /** Not part of the listing set: the choice of light or dark, in both. */
    @Test
    fun appearance() = shootDialog("x-appearance") {
        app.amethyst.ui.screen.ThemeDialog(app.amethyst.core.settings.ThemeMode.SYSTEM, onChange = {}, onDismiss = {})
    }

    // Not part of the listing set: a handful of screens in the light theme - each kind of
    // surface once (cards, rows, badges, fields, a sheet, a dialog), not every picture twice.
    @Test fun homeLight() = light(::home)
    @Test fun doneLight() = light(::done)
    @Test fun statisticsLight() = light(::statistics)
    @Test fun enginesLight() = light(::engines)

    @Test
    @Config(qualifiers = "w360dp-h1160dp-xxhdpi")
    fun settingsTallLight() = light(::settingsTall)

    @Test fun bubbleSettingsLight() = light(::bubbleSettings)
    @Test fun groqEngineLight() = light(::groqEngine)
    @Test fun cleanupLight() = light(::cleanup)
    @Test fun onboardingWelcomeLight() = light(::onboardingWelcome)
    @Test fun onboardingEngineLight() = light(::onboardingEngine)
    @Test fun onboardingCleanupClaudeLight() = light(::onboardingCleanupClaude)
    @Test fun appearanceLight() = light(::appearance)
    @Test fun appPickerLight() = light(::appPicker)

    /** Not part of the listing set: a look at the picker, which lives in its own dialog window. */
    @Test
    fun appPicker() = shootDialog("x-app-picker") {
        AppPickerDialog(
            title = "Hide the bubble in",
            existing = emptySet(),
            apps = FakeApps,
            usage = mapOf("com.whatsapp" to 31, "com.google.android.gm" to 4),
            multiple = true,
            confirmLabel = { n -> if (n <= 1) "Hide" else "Hide in $n apps" },
            onDismiss = {},
            onPick = {},
        )
    }

    /** A dialog draws in a window of its own; this takes that window. */
    private fun shootDialog(name: String, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { AmberTheme(dark) { content() } }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        val view = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File(out, "$name$suffix.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    /** A window without Amber's page around it: a stand-in for the app behind, then [content] over it. */
    private fun shootBare(name: String, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            // Over another app the card is dark, whatever Amber's theme (QuickStyleActivity).
            AmberTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Color(0xFFECE5DD))) { content() }
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File(out, "$name$suffix.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun shoot(name: String, title: String?, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            AmberTheme(dark) {
                AmberBackground {
                    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
                        if (title != null) TopBar(title = title, showBack = title != "Amber", showSettings = title == "Amber", onBack = {}, onSettings = {})
                        // Every drawn scene at rest, as with animations off: a looping one would never let the clock below run out.
                        androidx.compose.runtime.CompositionLocalProvider(LocalTimelineAt provides 1f) { content() }
                    }
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File(out, "$name$suffix.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private companion object {
        /** An own server in the home network, for the cleanup step. */
        const val OLLAMA = "http://192.168.1.20:11434/v1"
        const val OPENROUTER = "https://openrouter.ai/api/v1"

        /** A fixed "now" for the pages that say how long ago something was. */
        const val NOW = 1_800_000_000_000L

        val APP_NAMES = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.google.android.gm" to "Gmail",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.keep" to "Keep",
        )

        /** Plain coloured discs in place of the real launcher icons, which are not ours to show. */
        private val ICON_COLORS = listOf(0xFF3DBE5A, 0xFFE0533F, 0xFF3A9AD9, 0xFFF2BE2C)

        object FakeApps : AppSource {
            override suspend fun all() = APP_NAMES.map { (pkg, label) -> InstalledApp(pkg, label) }
            override fun label(packageName: String) = APP_NAMES[packageName] ?: packageName
            override fun cachedIcon(packageName: String): ImageBitmap {
                val color = ICON_COLORS[APP_NAMES.keys.indexOf(packageName).coerceAtLeast(0) % ICON_COLORS.size]
                val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawCircle(ICON_PX / 2f, ICON_PX / 2f, ICON_PX / 2f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toInt() })
                return bitmap.asImageBitmap()
            }
            override suspend fun icon(packageName: String, sizePx: Int) = cachedIcon(packageName)
        }

        private const val ICON_PX = 108
    }
}

/**
 * The same screens with the phone set to German (values-de), into build/screenshots-de. The tall
 * variants set their own size and stay English.
 */
@Config(qualifiers = "+de")
class ScreenshotsGerman : Screenshots()
