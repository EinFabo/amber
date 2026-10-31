package app.amethyst.ui

import app.amethyst.apps.InstalledApp
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import app.amethyst.core.onboarding.OnboardingStep
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
import java.time.Duration
import org.junit.Assert.assertTrue

/**
 * Renders the main screens with sample data into PNGs - the listing screenshots, and a look at
 * the layout without a phone. Real Android drawing (Robolectric's native graphics), a 1080 ×
 * 2340 phone. Written to app/build/store-shots; the numbers are made-up examples.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
class StoreShots {

    private val out = File("build/store-shots").apply { mkdirs() }

    private val ready = SetupState(
        microphoneGranted = true,
        accessibilityEnabled = true,
        notificationsGranted = true,
        micServiceRunning = true,
        batteryUnrestricted = true,
    )

    private val actions = SetupActions({}, {}, {}, {}, {}, {})

    private val settings = AmethystSettings.defaults().let { base ->
        listOf(EnginePresets.LOCAL_PARAKEET, EnginePresets.LOCAL_WHISPER, EnginePresets.TINFOIL)
            .fold(base) { s, preset -> s.withEngine(EngineConfig.fromPreset(preset, EngineConfig.defaultLanguageTag(preset.kind))) }
            .copy(activeEngineId = EnginePresets.LOCAL_PARAKEET.id)
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
            settings = settings, setup = ready, diagnosticsCount = 12, version = "0.12.0",
            advancedOpen = false, onToggleAdvanced = {}, onOpen = {}, onReplayIntroduction = {},
        )
    }

    /** Not part of the listing set: the whole settings list in one picture, "Advanced" unfolded. */
    @Test
    @Config(qualifiers = "w360dp-h1160dp-xxhdpi")
    fun settingsTall() = shoot("x-settings-tall", "Settings") {
        SettingsScreen(
            settings = pipeline.copy(activeEngineId = EnginePresets.TINFOIL.id), setup = ready.copy(batteryUnrestricted = false),
            diagnosticsCount = 12, version = "0.20.0",
            advancedOpen = true, onToggleAdvanced = {}, onOpen = {}, onReplayIntroduction = {},
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
            onUpdateSettings = {}, apps = FakeApps, usage = emptyMap(),
        )
    }

    @Test
    fun groqEngine() = shoot("8-groq", "Engine") {
        EngineDetailScreen(
            config = EngineConfig.fromPreset(EnginePresets.GROQ),
            active = true, storedKey = "gsk_example", whisperModels = emptyMap(),
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
        mapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192, "store" to 512).forEach { (density, size) ->
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
        assertTrue(File(dir, "store-ic_launcher.png").length() > 0)
    }

    /** Recognition, cleanup and fallback chosen apart: Whisper on the phone, cleaned up on Tinfoil. */
    private val pipeline = settings.withEngine(EngineConfig.fromPreset(EnginePresets.GROQ)).copy(
        activeEngineId = EnginePresets.LOCAL_WHISPER.id,
        cleanup = app.amethyst.core.settings.CleanupChoice(EnginePresets.TINFOIL.id, "deepseek-v4-1-flash"),
    )

    /** Not part of the listing set: the cleanup page. */
    @Test
    fun cleanup() = shoot("x-cleanup", "Cleanup") {
        CleanupScreen(settings = pipeline, hasKeyFor = { true }, onUpdateSettings = {})
    }

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
        profile = keep, settings = pipeline.copy(profiles = listOf(keep)), apps = FakeApps, hasKeyFor = { true },
        onUpdateSettings = {}, onRemoved = {},
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
            advancedOpen = false, onToggleAdvanced = {}, onOpen = {}, onReplayIntroduction = {},
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

    /** A fresh install: nothing granted yet. */
    private val fresh = SetupState()

    /** A Pixel-like phone: room for every engine. */
    private fun content(
        parakeet: ParakeetState = ParakeetState.Missing,
        metered: Boolean = false,
        download: app.amethyst.core.engine.ModelDownload? = null,
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
        hasKeyFor = { false },
        onSaveKey = { _, _ -> },
        onUpdateSettings = {},
    )

    private fun onboarding(
        name: String,
        step: OnboardingStep,
        setup: SetupState = fresh,
        settings: AmethystSettings = AmethystSettings.defaults(),
        content: OnboardingContent = content(),
    ) = shoot("x-onboarding-$name", null) {
        OnboardingScreen(step = step, settings = settings, setup = setup, actions = actions, content = content, onStep = {}, onFinish = {})
    }

    @Test
    fun onboardingWelcome() = onboarding("1-welcome", OnboardingStep.WELCOME)

    @Test
    fun onboardingAccessibility() = onboarding("2-accessibility", OnboardingStep.ACCESSIBILITY)

    @Test
    fun onboardingAccessibilityDone() = onboarding("2-accessibility-done", OnboardingStep.ACCESSIBILITY, fresh.copy(accessibilityEnabled = true))

    @Test
    fun onboardingMicrophone() = onboarding("3-microphone", OnboardingStep.MICROPHONE)

    @Test
    fun onboardingEngine() = onboarding("4-engine", OnboardingStep.ENGINE, ready)

    @Test
    fun onboardingEngineDownloading() = onboarding(
        "4-engine-downloading", OnboardingStep.ENGINE, ready,
        settings = AmethystSettings.defaults().withEngine(EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)).copy(activeEngineId = EnginePresets.LOCAL_PARAKEET.id),
        content = content(parakeet = app.amethyst.engine.ParakeetState.Busy(300_000_000, 670_000_000)),
    )

    /** A replay by someone dictating with Groq: the step keeps it instead of forcing a download. */
    @Test
    fun onboardingEngineKept() = onboarding(
        "4-engine-kept", OnboardingStep.ENGINE, ready,
        settings = AmethystSettings.defaults().withEngine(EngineConfig.fromPreset(EnginePresets.GROQ))
            .copy(activeEngineId = EnginePresets.GROQ.id, onboardingDone = true),
    )

    @Test
    fun onboardingEngineMetered() = onboarding("4-engine-metered", OnboardingStep.ENGINE, ready, content = content(metered = true))

    private val parakeetActive = AmethystSettings.defaults()
        .withEngine(EngineConfig.fromPreset(EnginePresets.LOCAL_PARAKEET)).copy(activeEngineId = EnginePresets.LOCAL_PARAKEET.id)

    @Test
    fun onboardingCleanup() = onboarding("5-cleanup", OnboardingStep.CLEANUP, ready, parakeetActive)

    @Test
    fun onboardingStyles() = onboarding(
        "6-styles", OnboardingStep.APP_STYLES, ready,
        parakeetActive.copy(cleanup = app.amethyst.core.settings.CleanupChoice(EnginePresets.TINFOIL.id, "deepseek-v4-1-flash")),
    )

    @Test
    fun onboardingBubble() = onboarding("7-bubble", OnboardingStep.BUBBLE, ready, parakeetActive)

    @Test
    fun onboardingRecording() = onboarding("8-recording", OnboardingStep.RECORDING, ready, parakeetActive)

    @Test
    fun onboardingReliability() = onboarding("9-reliability", OnboardingStep.RELIABILITY, fresh.copy(microphoneGranted = true, accessibilityEnabled = true), parakeetActive)

    @Test
    fun onboardingDone() = onboarding(
        "10-done", OnboardingStep.DONE, ready, parakeetActive,
        content = content(download = app.amethyst.core.engine.ModelDownload.Running(520_000_000, 670_000_000)),
    )

    /** Not part of the listing set: a look at the picker, which lives in its own dialog window. */
    @Test
    fun appPicker() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            AmberTheme {
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
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        val view = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File(out, "x-app-picker.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun shoot(name: String, title: String?, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            AmberTheme {
                AmberBackground {
                    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
                        if (title != null) TopBar(title = title, showBack = title != "Amber", showSettings = title == "Amber", onBack = {}, onSettings = {})
                        content()
                    }
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File(out, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private companion object {
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
