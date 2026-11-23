package app.amethyst.core.engine

import app.amethyst.core.privacy.TrustLevel

/**
 * What the user is told when another engine recognized a recording the chosen server did not
 * ([Fallback], [ReadyFallback]). A value; the app words it in the phone's language, with the
 * names of the engines involved.
 *
 * The order of the rules matters: a server that cannot serve is always named with what it said,
 * since only the user can put it right and the fallback would otherwise hide it for good; and
 * "no connection" is only said when the recording provably stayed on the phone - after a read
 * timeout or a 5xx the server may have it, and the notice must not claim otherwise.
 */
sealed interface FallbackNotice {

    /** The text prepared on the phone came first: the server took too long, it was not gone. */
    data object ServerTooSlow : FallbackNotice

    /** The server answered but cannot serve ([SttFailure.serverCannotServe]): [problem] is what it said. */
    data class ServerRefused(val problem: Problem) : FallbackNotice

    /** The server could not be reached; the phone recognized, the recording never left it. */
    data object NoConnection : FallbackNotice

    /** No answer after the upload; the phone recognized, but the server may have the recording. */
    data object NoAnswerOnPhone : FallbackNotice

    /** Another server recognized instead of the chosen one. */
    data object OtherServer : FallbackNotice

    companion object {

        /** After the text prepared on the phone won the race ([ReadyFallback.race]); [trust] is the text's label. */
        fun afterRace(local: ReadyFallback.Outcome.Local, trust: TrustLevel): FallbackNotice {
            val refused = local.serverFailure?.takeIf { it.serverCannotServe }
            return when {
                local.serverTooSlow -> ServerTooSlow
                refused != null -> ServerRefused(refused.problem)
                trust == TrustLevel.ON_DEVICE -> NoConnection
                else -> NoAnswerOnPhone
            }
        }

        /**
         * After the server failed with [cause] and a fallback engine recognized the recording
         * ([Fallback.candidates]); [trust] is the text's label, [recognizedOnPhone] whether that
         * engine runs on the phone.
         */
        fun afterFailure(cause: SttFailure, trust: TrustLevel, recognizedOnPhone: Boolean): FallbackNotice = when {
            cause.serverCannotServe -> ServerRefused(cause.problem)
            trust == TrustLevel.ON_DEVICE -> NoConnection
            recognizedOnPhone -> NoAnswerOnPhone
            else -> OtherServer
        }
    }
}
