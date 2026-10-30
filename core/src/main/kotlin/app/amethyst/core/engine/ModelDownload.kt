package app.amethyst.core.engine

/**
 * A download in progress for the active engine. A failed or absent download is not one: those
 * keep the "Download model" remedy, because nothing will fix them by waiting.
 */
sealed interface ModelDownload {
    data class Running(val received: Long, val total: Long) : ModelDownload
    data object WaitingForWifi : ModelDownload
}

/** What a dictation started during the download is told, instead of failing. */
object DownloadNotice {

    fun short(download: ModelDownload): String = when (download) {
        // Floored and held below 100: "100 %" while the last bytes are still checked would lie.
        is ModelDownload.Running -> {
            val percent = if (download.total <= 0) 0 else (download.received * 100 / download.total).coerceIn(0, 99)
            "Model downloading - $percent %"
        }
        ModelDownload.WaitingForWifi -> "Waiting for Wi-Fi"
    }

    fun atStart(download: ModelDownload): String = "${short(download)}. Amber is ready when it is done."
}

enum class GateDecision {
    START, ASK, WAIT,

    /** Nothing to fetch: the model is already on the phone, or the engine needs none. */
    NONE,
}

/**
 * Hundreds of megabytes go over mobile data only when the user said so - and never twice: a
 * model already on the phone is not fetched again (a second download would refuse dictation
 * meanwhile, and a failed one would mark the good file as failed).
 */
object DownloadGate {

    fun decide(metered: Boolean, mobileDataAllowed: Boolean, waitingForWifi: Boolean, needed: Boolean): GateDecision = when {
        !needed -> GateDecision.NONE
        !metered || mobileDataAllowed -> GateDecision.START
        waitingForWifi -> GateDecision.WAIT
        else -> GateDecision.ASK
    }
}
