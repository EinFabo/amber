package app.amethyst.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amethyst.AmethystApp
import app.amethyst.R
import app.amethyst.apps.Launcher
import app.amethyst.core.bubble.QuickActions
import app.amethyst.core.settings.AppProfile
import app.amethyst.core.settings.AppProfiles
import app.amethyst.ui.screen.StyleAndInstruction
import app.amethyst.ui.theme.AmberPalette
import app.amethyst.ui.theme.AmberTheme
import kotlinx.coroutines.launch

/**
 * The quick menu's "Style here": a card at the bottom of the screen over the app in front, with
 * that app's style and own instruction - the same two as on its page in Amber
 * ([StyleAndInstruction]), saved the same way. Done, a tap outside or Back closes it, and the
 * app is in front again: the card runs in a task of its own, out of Recents, so Amber's main
 * screen never comes forward with it.
 */
class QuickStyleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = Launcher.launchable(this, runCatching { intent.getStringExtra(EXTRA_PACKAGE) }.getOrNull())
        if (app == null) {
            finish()
            return
        }
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        val graph = AmethystApp.graph(this)
        val apps = AndroidAppSource(this)
        val label = apps.label(app)

        fun edit(change: (AppProfile) -> AppProfile) {
            graph.appScope.launch { graph.settingsStore.update { it.copy(profiles = AppProfiles.edit(it.profiles, app, change)) } }
        }

        setContent {
            AmberTheme {
                // Read from the store, not the defaults the process starts on.
                val settings = graph.loadedSettings.collectAsStateWithLifecycle().value ?: return@AmberTheme
                val profile = AppProfiles.forPackage(settings.profiles, app)
                if (profile == null) {
                    // The quick menu makes it before it opens the card; removed meanwhile, made again.
                    LaunchedEffect(app) { graph.settingsStore.update { it.copy(profiles = AppProfiles.ensure(it.profiles, app, label)) } }
                    return@AmberTheme
                }
                val cleanupRuns = remember(settings) { QuickActions.cleanupHere(settings, app) { graph.secretStore.has(it) }.runs }
                QuickStyleCard(
                    profile = profile,
                    cleanupRuns = cleanupRuns,
                    apps = apps,
                    onEdit = ::edit,
                    onDone = ::finish,
                    onOpenAmber = {
                        startActivity(MainActivity.intent(this, appPage = app))
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        /** The app the card is for; only an installed app with a launcher icon is taken. */
        private const val EXTRA_PACKAGE = "app.amethyst.extra.PACKAGE"

        /** The card for [packageName], from the accessibility service: a task of its own, fresh each time. */
        fun intent(context: Context, packageName: String): Intent =
            Intent(context, QuickStyleActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}

/**
 * The card itself, over a dimmed screen: the app's icon and name, its style as chips, its own
 * instruction, a line when no cleanup runs there (with the way into Amber to set one up), and
 * Done. A tap on the dimmed part closes it too.
 */
@Composable
fun QuickStyleCard(
    profile: AppProfile,
    /** A cleanup runs in this app, so the style takes effect ([app.amethyst.core.bubble.CleanupHere.runs]). */
    cleanupRuns: Boolean,
    apps: AppSource,
    onEdit: ((AppProfile) -> AppProfile) -> Unit,
    onDone: () -> Unit,
    onOpenAmber: () -> Unit,
) {
    val outside = remember { MutableInteractionSource() }
    val inside = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(AmberPalette.Background.copy(alpha = SCRIM_ALPHA))
            .clickable(interactionSource = outside, indication = null, onClick = onDone),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .background(AmberPalette.Background, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                // Taps on the card stay on it.
                .clickable(interactionSource = inside, indication = null, onClick = {})
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AppIcon(profile.packageName, apps, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(profile.label, style = MaterialTheme.typography.titleLarge, color = AmberPalette.Text)
                    Hint(stringResource(R.string.style_subtitle, profile.label))
                }
            }
            StyleAndInstruction(profile, onEdit, chips = true)
            if (!cleanupRuns) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hint(stringResource(R.string.style_here_no_cleanup, profile.label), AmberPalette.Primary, Modifier.weight(1f))
                    GhostButton(stringResource(R.string.quick_open_amber), onOpenAmber)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrimaryButton(stringResource(R.string.style_here_done), onDone)
            }
        }
    }
}

/** How dark the app behind the card goes. */
private const val SCRIM_ALPHA = 0.55f
