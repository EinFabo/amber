package app.amethyst.accessibility

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings
import android.util.Log

/**
 * The package of the keyboard in use (Settings.Secure.DEFAULT_INPUT_METHOD), read once and then
 * kept current by a ContentObserver - instead of a settings read on every window change on the
 * phone. The observer has no handler, so it runs on the binder thread the moment the setting
 * changes rather than waiting its turn on the main thread: a keyboard just switched to is known
 * before its first window event is handled.
 */
class DefaultKeyboard(private val resolver: ContentResolver) {

    @Volatile
    private var cached: String? = null

    private val observer = object : ContentObserver(null as Handler?) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    /** Without the observer (it could not be registered) every question reads the setting, as before. */
    @Volatile
    private var registered = false

    /** Main thread, from the service's connect. Registers before the first read, so no switch slips between. */
    fun start() {
        if (!registered) {
            registered = runCatching {
                resolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, observer)
            }.onFailure { Log.w(TAG, "keyboard setting not observable, read each time", it) }.isSuccess
        }
        refresh()
    }

    /** Main thread, from the service's teardown. */
    fun stop() {
        if (!registered) return
        runCatching { resolver.unregisterContentObserver(observer) }
        registered = false
    }

    /** E.g. "com.google.android.inputmethod.latin"; null when unknown. */
    fun packageName(): String? {
        if (!registered) refresh()
        return cached
    }

    /** Whether [pkg] is the keyboard in use. */
    fun isKeyboard(pkg: String): Boolean = pkg == packageName()

    private fun refresh() {
        cached = runCatching { Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD) }
            .getOrNull()?.substringBefore('/')?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "Amber"
    }
}
