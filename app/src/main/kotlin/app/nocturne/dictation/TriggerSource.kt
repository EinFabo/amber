package app.nocturne.dictation

/**
 * Who asked for this dictation. It decides how the microphone is obtained.
 *
 * The distinction is not cosmetic: a tap on the bubble happens while a foreign app is in
 * front, which Android 14+ treats as a background microphone start, so it may have to be
 * routed through a foreground anchor. A press inside Nocturne is already foreground and must
 * never open that extra window.
 */
enum class TriggerSource {
    /** Tap on the floating bubble - the app itself is in the background. */
    BUBBLE,

    /** Re-entry from the invisible proxy activity, which is now the foreground anchor. */
    PROXY_ACTIVITY,

    /** Button inside Nocturne. The app is visible, so recording is always permitted. */
    IN_APP,
    ;

    /** Only the bubble path may fall back to the proxy activity; the others already are it. */
    val mayUseProxyFallback: Boolean get() = this == BUBBLE
}
