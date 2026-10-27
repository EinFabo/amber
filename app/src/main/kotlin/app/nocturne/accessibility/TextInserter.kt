package app.nocturne.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import app.nocturne.R
import app.nocturne.core.insert.InsertionStrategy
import app.nocturne.core.text.FieldState
import app.nocturne.core.text.SpliceOptions
import app.nocturne.core.text.TextSplicer
import app.nocturne.data.StrategyStore
import app.nocturne.dictation.InsertionOutcome

/**
 * Writes dictated text into whatever field has focus, in another app.
 *
 * There is no single method that works everywhere: ACTION_SET_TEXT gives the best result but
 * is ignored by many WebViews, games and cross-platform toolkits - sometimes while still
 * reporting success. So each attempt is verified by reading the field back, the working
 * method is remembered per app, and the clipboard is the floor nobody falls through.
 */
class TextInserter(
    private val service: AccessibilityService,
    private val strategyStore: StrategyStore,
) {

    private val memory = strategyStore.load()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun insert(text: String, options: SpliceOptions): InsertionOutcome {
        val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val targetPackage = focused?.packageName?.toString()
            ?: service.rootInActiveWindow?.packageName?.toString()
            ?: UNKNOWN_PACKAGE

        for (strategy in memory.order(targetPackage)) {
            val success = when (strategy) {
                InsertionStrategy.SET_TEXT -> focused != null && trySetText(focused, text, options)
                InsertionStrategy.PASTE -> focused != null && tryPaste(focused, text)
                InsertionStrategy.CLIPBOARD_ONLY -> copyToClipboard(text, notifyUser = true)
            }

            if (success) {
                memory.recordSuccess(targetPackage, strategy)
                strategyStore.save(memory)
                return InsertionOutcome(strategy, success = true, targetPackage = targetPackage)
            }

            Log.i(TAG, "$strategy did not take in $targetPackage, trying next")
            memory.recordFailure(targetPackage, strategy)
            strategyStore.save(memory)
        }

        return InsertionOutcome(
            strategy = InsertionStrategy.CLIPBOARD_ONLY,
            success = false,
            targetPackage = targetPackage,
        )
    }

    private fun trySetText(
        node: AccessibilityNodeInfo,
        text: String,
        options: SpliceOptions,
    ): Boolean {
        if (!node.isEditable) return false
        node.refresh()

        val existing = node.text?.toString().orEmpty()
        val selectionStart = node.textSelectionStart
        val selectionEnd = node.textSelectionEnd
        val field = if (selectionStart in 0..existing.length && selectionEnd in 0..existing.length) {
            FieldState(existing, selectionStart, selectionEnd)
        } else {
            // No usable selection reported: append at the end rather than overwrite.
            FieldState(existing, existing.length, existing.length)
        }

        val spliced = TextSplicer.splice(field, text, options)

        val setTextArgs = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                spliced.text,
            )
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)) return false

        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, spliced.cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, spliced.cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)

        // performAction returning true is not proof: read the field back. Compared loosely,
        // because fields legitimately reformat what they are given (phone numbers, amounts).
        node.refresh()
        val after = node.text?.toString().orEmpty()
        return after.contains(text.trim(), ignoreCase = true)
    }

    private fun tryPaste(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) return false
        if (!copyToClipboard(text, notifyUser = false)) return false
        node.refresh()
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return false
        node.refresh()
        return node.text?.toString()?.contains(text.trim(), ignoreCase = true) == true
    }

    /**
     * The previous clipboard content is deliberately not restored: since Android 10 an app
     * that is not in the foreground cannot read the clipboard, so a "restore" would just
     * overwrite it with null. Better to leave the dictation there than to destroy whatever
     * the user had copied.
     */
    private fun copyToClipboard(text: String, notifyUser: Boolean): Boolean {
        val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return false
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("Nocturne", text))
            if (notifyUser) {
                mainHandler.post {
                    Toast.makeText(service, R.string.clipboard_fallback, Toast.LENGTH_SHORT).show()
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "clipboard write failed", e)
            false
        }
    }

    private companion object {
        const val TAG = "Nocturne"
        const val UNKNOWN_PACKAGE = "unknown"
    }
}
