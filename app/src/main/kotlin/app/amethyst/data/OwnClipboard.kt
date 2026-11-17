package app.amethyst.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import app.amethyst.AmethystApp
import app.amethyst.core.insert.OwnClip
import app.amethyst.core.privacy.SecurityMode

/**
 * Every clip Amber writes, and taking it off the clipboard again. A clip is marked sensitive -
 * kept out of clipboard previews, history and keyboard sync - and marked as Amber's
 * ([OwnClip.MARK]), so [forgetIfOurs] clears it only while it is still there.
 */
object OwnClipboard {

    /**
     * [text] on the clipboard under [label]. False when Android refused - or when the security
     * mode is on now, or the settings are not read yet ([SecurityMode.mayCopy]): asked here, at
     * the one place every clip goes through, so a dictation that began before the mode went on
     * cannot write it either. The previous clip is not restored later: since Android 10 an app
     * that is not in front cannot read the clipboard, so a "restore" would overwrite it with
     * nothing.
     */
    fun copy(context: Context, text: String, label: String = OwnClip.LABEL): Boolean {
        val settings = (context.applicationContext as? AmethystApp)?.graph?.loadedSettings?.value
        if (!SecurityMode.mayCopy(settings)) {
            Log.i(TAG, "clipboard not written: security mode, or the settings not read yet")
            return false
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        return try {
            val clip = ClipData.newPlainText(label, text)
            clip.description.extras = PersistableBundle().apply {
                putBoolean(SENSITIVE_EXTRA, true)
                putBoolean(OwnClip.MARK, true)
            }
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Log.w(TAG, "clipboard write failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Clears the clipboard while the clip on it is Amber's ([OwnClip.isOurs]); anything the user
     * copied since stays. Android lets only the app in front read the clipboard's description:
     * from behind, nothing can be told and nothing is cleared. True when a clip was cleared.
     */
    fun forgetIfOurs(context: Context): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        return try {
            val description = clipboard.primaryClipDescription ?: return false
            if (!OwnClip.isOurs(description.label, description.extras?.getBoolean(OwnClip.MARK, false) == true)) return false
            clipboard.clearPrimaryClip()
            Log.i(TAG, "Embersay's clip cleared from the clipboard")
            true
        } catch (e: Exception) {
            Log.w(TAG, "clipboard not cleared: ${e.javaClass.simpleName}")
            false
        }
    }

    /** ClipDescription.EXTRA_IS_SENSITIVE (API 33); honoured by name on earlier versions too. */
    private const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"
    private const val TAG = "Embersay"
}
