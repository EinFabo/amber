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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import app.amethyst.apps.InstalledApp
import app.amethyst.core.bubble.BubbleEvent
import app.amethyst.core.engine.ModelDownload
import app.amethyst.core.engine.PhoneFacts
import app.amethyst.engine.ParakeetState
import app.amethyst.ui.onboarding.OnboardingContent
import app.amethyst.ui.screen.CleanupAccess
import app.amethyst.ui.theme.AmberTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * A look at what moves without a phone: the introduction's drawn scenes held at chosen moments
 * of their [Timeline] and rendered to PNGs, in English and German, into app/build/frames as
 * "<name>-<language>-<percent>.png". Nothing is asserted about the pictures - they are there
 * to be looked at, each phase by eye. Like [Screenshots]: real Android drawing, a 1080 × 2340
 * phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xxhdpi")
abstract class TimelineFrames {

    private val out = File("build/frames").apply { mkdirs() }

    protected val actions = SetupActions({}, {}, {}, {}, {}, {}, {})

    /** [content] with every timeline in it held at each of [at] (0..1) in turn, one picture each. */
    protected fun frames(name: String, vararg at: Float, dark: Boolean = true, languages: List<String> = LANGUAGES, content: @Composable () -> Unit) {
        for (language in languages) {
            RuntimeEnvironment.setQualifiers("+$language")
            for (point in at) {
                val activity = page(dark) { CompositionLocalProvider(LocalTimelineAt provides point) { content() } }
                pass(1_500)
                save(activity, "$name${if (dark) "" else "-light"}-$language-${"%03d".format((point * 100).toInt())}")
                closePages()
            }
        }
    }

    /** Amber's page with [content] on it, running as it does on a phone; [pass] moves its clock, [save] takes the picture. */
    protected fun page(dark: Boolean = true, content: @Composable () -> Unit): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).also { pages += it }.setup().get()
        activity.setContent {
            AmberTheme(dark) {
                AmberBackground { Column(Modifier.fillMaxSize().padding(top = 28.dp)) { content() } }
            }
        }
        return activity
    }

    protected fun pass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    protected fun save(activity: ComponentActivity, name: String) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File(out, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        assertTrue(file.length() > 0)
    }

    private val pages = mutableListOf<ActivityController<ComponentActivity>>()

    /** Many pages in one test: each is closed, or the pictures of all of them would not fit the tests' memory. */
    @After
    fun closePages() {
        pages.forEach { it.pause().stop().destroy() }
        pages.clear()
    }

    /** What the introduction's later steps ask of the app, on a phone with room and nothing chosen yet. */
    protected fun content(
        bubbleEvents: Flow<BubbleEvent> = emptyFlow(),
        download: ModelDownload? = null,
        pending: PendingModel? = null,
        parakeet: ParakeetState = ParakeetState.Missing,
    ) = OnboardingContent(
        phone = PhoneFacts(totalRamMb = 8_000, freeStorageMb = 20_000, whisperCpu = true, androidOnDevice = true),
        metered = false,
        parakeet = parakeet,
        whisper = emptyMap(),
        waitingFor = null,
        onChooseEngine = { _, _ -> },
        onWaitForWifi = {},
        onOpenRecognition = {},
        download = download,
        apps = NoApps,
        onUpdateSettings = {},
        cleanup = CleanupAccess(hasKeyFor = { false }, onSaveKey = { _, _ -> }, onUpdateSettings = {}),
        bubbleEvents = bubbleEvents,
        pending = pending,
    )

    protected companion object {
        val LANGUAGES = listOf("en", "de")

        object NoApps : AppSource {
            override suspend fun all() = emptyList<InstalledApp>()
            override fun label(packageName: String) = packageName
            override fun cachedIcon(packageName: String): ImageBitmap? = null
            override suspend fun icon(packageName: String, sizePx: Int): ImageBitmap? = null
        }
    }
}
