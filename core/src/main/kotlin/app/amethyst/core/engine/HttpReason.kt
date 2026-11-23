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

        /**
         * What [code] means for a model or file download: it needs no key, costs nothing and
         * sends nothing that could be too large, so a key, credit or size refusal is only a
         * refused request there - "API key rejected" would send the user looking for a key.
         */
        fun forDownload(code: Int): HttpReason = when (val reason = of(code)) {
            KEY_REFUSED, NO_CREDIT, TOO_LARGE -> REFUSED
            else -> reason
        }
    }
}
