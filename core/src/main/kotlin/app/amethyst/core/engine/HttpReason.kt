package app.amethyst.core.engine

/**
 * What an HTTP status means in plain words, for a message that would otherwise say only
 * "error 401": the app words the reason first and puts the code after it in brackets. Only
 * where nothing more specific is known - a [Problem] that already names the cause (a refused
 * key, no credit, a model gone) says that instead.
 */
enum class HttpReason {
    KEY_REFUSED,
    NO_CREDIT,
    NOT_FOUND,
    TOO_LARGE,
    TOO_MANY,
    TOO_SLOW,
    SERVER_DOWN,

    /** Anything else: a bad request, a method or format the server does not take, a redirect. */
    REFUSED,
    ;

    companion object {
        fun of(code: Int): HttpReason = when (code) {
            401, 403 -> KEY_REFUSED
            402 -> NO_CREDIT
            404, 410 -> NOT_FOUND
            413 -> TOO_LARGE
            429 -> TOO_MANY
            // A gateway's or proxy's timeout (504, Cloudflare's 524) is a slow server, not one that is down.
            408, 504, 524 -> TOO_SLOW
            in 500..599 -> SERVER_DOWN
            else -> REFUSED
        }
    }
}
