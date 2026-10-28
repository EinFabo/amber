package app.amethyst.ui

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
import app.amethyst.ui.screen.AppStylesScreen
import app.amethyst.ui.screen.BubbleScreen
import app.amethyst.ui.screen.EngineDetailScreen
import app.amethyst.ui.screen.EnginesScreen
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
            dictationState = MutableStateFlow(DictationState.Idle), stats = stats,
            onStartTest = {}, onStopTest = {}, onOpenEngines = {}, onOpenStats = {},
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
            stats = stats,
            onStartTest = {}, onStopTest = {}, onOpenEngines = {}, onOpenStats = {},
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
    fun engines() = shoot("4-engines", "Engine") {
        EnginesScreen(
            settings = settings, hasKeyFor = { true }, medianWaitMs = stats.medianWaitMs,
            onUpdateSettings = {}, onOpenEngine = {},
        )
    }

    @Test
    fun settingsList() = shoot("5-settings", "Settings") {
        SettingsScreen(settings = settings, setup = ready, diagnosticsCount = 12, version = "0.12.0", onOpen = {})
    }

    @Test
    fun appStyles() = shoot("6-app-styles", "App styles") {
        AppStylesScreen(
            settings = settings.withEngine(settings.engines.first { it.id == EnginePresets.TINFOIL.id }.copy(cleanupModel = "deepseek-v4-1-flash"))
                .copy(activeEngineId = EnginePresets.TINFOIL.id),
            onUpdateSettings = {}, loadApps = { emptyList() },
        )
    }

    @Test
    fun bubbleSettings() = shoot("7-bubble", "Bubble") {
        BubbleScreen(
            settings = settings.copy(bubble = settings.bubble.copy(restingOpacity = 0.6f, hiddenIn = listOf("com.whatsapp"))),
            onUpdateSettings = {}, loadApps = { emptyList() }, appName = { APP_NAMES[it] ?: it },
        )
    }

    @Test
    fun groqEngine() = shoot("8-groq", "Engine") {
        EngineDetailScreen(
            config = EngineConfig.fromPreset(EnginePresets.GROQ).copy(cleanupModel = "openai/gpt-oss-20b"),
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

    private fun shoot(name: String, title: String, content: @Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            AmberTheme {
                AmberBackground {
                    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
                        TopBar(title = title, showBack = title != "Amber", showSettings = title == "Amber", onBack = {}, onSettings = {})
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
    }
}
