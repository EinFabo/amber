package app.amethyst.ui

import android.content.Context
import android.util.LruCache
import app.amethyst.apps.InstalledApp
import app.amethyst.apps.Launcher
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The installed apps with their names and icons, for picking one and for showing picked ones. */
interface AppSource {
    /** Apps with a launcher icon, Amber itself left out. */
    suspend fun all(): List<InstalledApp>

    /** An app's name as the launcher shows it, or its package when it is gone. */
    fun label(packageName: String): String

    /** Already loaded, so a list scrolled back up does not flicker. */
    fun cachedIcon(packageName: String): ImageBitmap?

    suspend fun icon(packageName: String, sizePx: Int): ImageBitmap?
}

class AndroidAppSource(context: Context) : AppSource {

    private val context = context.applicationContext
    private val packages = context.packageManager
    private val icons = LruCache<String, ImageBitmap>(ICONS_KEPT)

    /** Loaded once per screen visit: the list rarely changes while Amber is open. */
    @Volatile
    private var loaded: List<InstalledApp>? = null

    override suspend fun all(): List<InstalledApp> = loaded ?: withContext(Dispatchers.IO) {
        Launcher.apps(context)
    }.also { loaded = it }

    override fun label(packageName: String): String = runCatching {
        packages.getApplicationInfo(packageName, 0).loadLabel(packages).toString()
    }.getOrDefault(packageName)

    override fun cachedIcon(packageName: String): ImageBitmap? = icons.get(packageName)

    override suspend fun icon(packageName: String, sizePx: Int): ImageBitmap? =
        icons.get(packageName) ?: withContext(Dispatchers.IO) {
            runCatching { packages.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).asImageBitmap() }.getOrNull()
        }?.also { icons.put(packageName, it) }

    private companion object {
        /** About 40 KB each at 36 dp: a full list's worth stays in memory. */
        const val ICONS_KEPT = 200
    }
}

/** An app's icon, loaded off the main thread; an empty square of the same size until then. */
@Composable
fun AppIcon(packageName: String, apps: AppSource, size: Dp = 36.dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState(apps.cachedIcon(packageName), packageName, px) {
        value = apps.icon(packageName, px)
    }
    Box(Modifier.size(size)) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}
