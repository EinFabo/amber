package app.amethyst.bubble

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.Button
import app.amethyst.R
import app.amethyst.core.bubble.BubbleAccess

/**
 * The bubble for TalkBack and other screen readers: a button named "Amber dictation" whose
 * state follows the run, whose double tap does what a tap does, with the rest in TalkBack's
 * actions menu - and a short word when a run starts, ends or fails. What is said when, is
 * [BubbleAccess]; this only hands it to Android.
 *
 * Touches are not involved: with explore-by-touch on, TalkBack takes the finger and calls
 * these actions instead, so the tap, hold and drag of a sighted user stay exactly as they are.
 */
internal class BubbleAccessibility(
    private val view: View,
    /** The bubble's tap: TalkBack's double tap and the actions that mean the same. */
    private val onTap: () -> Unit,
    /** The actions a tap does not cover: the voice command and hiding in this app. */
    private val onAction: (BubbleAccess.Action) -> Unit,
    /** The bubble shows: no word from a bubble that is hidden or out of a screenshot. */
    private val canSpeak: () -> Boolean,
) : View.AccessibilityDelegate() {

    private var phase: BubbleAccess.Phase? = null
    private var node: BubbleAccess.Node? = null

    /** TalkBack's focus is on the bubble: it says a changed state there itself. */
    private var focused = false

    private var announcing: BubbleAccess.Phase? = null
    private val announce = Runnable {
        val due = announcing
        announcing = null
        // Moved on meanwhile (a start that failed at once says only the failure), or hidden.
        if (due == null || due != phase || focused || !canSpeak()) return@Runnable
        val node = node ?: return@Runnable
        // A failure's reason comes with its toast, which TalkBack reads too.
        view.announceForAccessibility(view.context.getString(stateText(node.status)))
    }

    init {
        view.contentDescription = view.context.getString(R.string.bubble_name)
        view.accessibilityDelegate = this
    }

    /**
     * Called on every render - fifty times a second while listening - so it does nothing
     * unless what TalkBack gets changed.
     */
    fun update(phase: BubbleAccess.Phase, node: BubbleAccess.Node) {
        val from = this.phase
        this.phase = phase
        if (from != null && BubbleAccess.announces(from, phase)) {
            // A moment later, so a start's command flag has settled and a start that fails at
            // once says only that it failed.
            announcing = phase
            view.removeCallbacks(announce)
            view.postDelayed(announce, ANNOUNCE_DELAY_MS)
        }
        if (node == this.node) return
        this.node = node
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) view.stateDescription = state(node)
        // The actions changed too: a screen reader holding the old node must fetch it again.
        view.parent?.notifySubtreeAccessibilityStateChanged(view, view, AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED)
    }

    /**
     * Hidden - waiting for a text field, in a hidden app, on the lock screen, fading out:
     * left out for screen readers altogether, and nothing more is said.
     */
    fun setHidden(hidden: Boolean) {
        view.importantForAccessibility =
            if (hidden) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_YES
        if (hidden) {
            announcing = null
            view.removeCallbacks(announce)
            focused = false
        }
    }

    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.className = Button::class.java.name
        val node = node ?: return
        info.isClickable = true
        info.addAction(AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, node.click?.let { host.context.getString(label(it)) }))
        node.actions.forEach { info.addAction(AccessibilityAction(label(it), host.context.getString(label(it)))) }
        // Before Android 11 there is no separate state: it follows the name.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) info.contentDescription = "${host.contentDescription}, ${state(node)}"
    }

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
        val node = node ?: return super.performAccessibilityAction(host, action, args)
        if (action == AccessibilityNodeInfo.ACTION_CLICK) {
            onTap()
            return true
        }
        // Each custom action's id is its label's resource id. One picked from a menu opened
        // before the state moved on does nothing rather than the opposite.
        val chosen = BubbleAccess.Action.entries.firstOrNull { label(it) == action }
            ?: return super.performAccessibilityAction(host, action, args)
        if (chosen !in node.actions) return false
        when (chosen) {
            BubbleAccess.Action.VOICE_COMMAND, BubbleAccess.Action.HIDE_HERE -> onAction(chosen)
            else -> onTap()
        }
        return true
    }

    override fun sendAccessibilityEvent(host: View, eventType: Int) {
        when (eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> focused = true
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> focused = false
        }
        super.sendAccessibilityEvent(host, eventType)
    }

    private fun state(node: BubbleAccess.Node): String {
        val reason = node.reason
        return if (node.status == BubbleAccess.Status.FAILED && reason != null) {
            view.context.getString(R.string.bubble_state_failed_reason, reason)
        } else {
            view.context.getString(stateText(node.status))
        }
    }

    private fun stateText(status: BubbleAccess.Status): Int = when (status) {
        BubbleAccess.Status.READY -> R.string.bubble_state_ready
        BubbleAccess.Status.LISTENING -> R.string.bubble_state_listening
        BubbleAccess.Status.LISTENING_FOR_COMMAND -> R.string.bubble_state_listening_command
        BubbleAccess.Status.PROCESSING -> R.string.bubble_state_processing
        BubbleAccess.Status.DONE -> R.string.bubble_state_done
        BubbleAccess.Status.FAILED -> R.string.bubble_state_failed
    }

    /** The label, whose resource id doubles as the action's id: unique, and never a standard action's. */
    private fun label(action: BubbleAccess.Action): Int = when (action) {
        BubbleAccess.Action.START_DICTATION -> R.string.bubble_action_start_dictation
        BubbleAccess.Action.START_COMMAND -> R.string.bubble_action_start_command
        BubbleAccess.Action.STOP_DICTATION -> R.string.bubble_action_stop_dictation
        BubbleAccess.Action.STOP_LISTENING -> R.string.bubble_action_stop_listening
        BubbleAccess.Action.CANCEL_COMMAND -> R.string.bubble_action_cancel_command
        BubbleAccess.Action.VOICE_COMMAND -> R.string.bubble_action_voice_command
        BubbleAccess.Action.HIDE_HERE -> R.string.bubble_action_hide_here
    }

    private companion object {
        const val ANNOUNCE_DELAY_MS = 150L
    }
}
