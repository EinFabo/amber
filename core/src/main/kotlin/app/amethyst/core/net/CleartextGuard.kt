package app.amethyst.core.net

import app.amethyst.core.engine.NotSentException
import app.amethyst.core.engine.ServerProviders
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.InetAddress

/**
 * Refuses plain http to any address outside the user's own network, judged on the address
 * the connection actually went to: "http://nas:8000" works while the name resolves into the
 * home network, and fails the day it resolves to the internet.
 *
 * The second guard. Android's network security config allows cleartext app-wide - it cannot
 * name address ranges, and an own server at home speaks http - and EngineConfigValidator
 * refuses public http addresses when they are typed. This one holds at request time, for
 * every client the app builds ([guardCleartext]). A network interceptor, because only there
 * the connection - and with it the real address - is known; nothing has been sent yet.
 */
class CleartextGuard internal constructor(
    private val isLocal: (InetAddress) -> Boolean,
) : Interceptor {

    constructor() : this(LocalNetwork::isLocal)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val connection = chain.connection()
        val connected = connection?.socket()?.inetAddress
        val proxied = connection?.route()?.proxy?.type()?.let { it != java.net.Proxy.Type.DIRECT } ?: false
        if (!allowed(request.url.isHttps, request.url.host, connected, proxied, isLocal)) {
            throw CleartextRefusedException("http://${request.url.host} via ${connected?.hostAddress ?: "unknown address"}")
        }
        return chain.proceed(request)
    }

    companion object {
        /**
         * Whether a request may go out. https always; http only when the address connected
         * to is local. Through a proxy that address is the proxy's, and the proxy resolves the
         * name itself - then the name has to be a local one too.
         */
        fun allowed(
            https: Boolean,
            host: String,
            connected: InetAddress?,
            proxied: Boolean,
            isLocal: (InetAddress) -> Boolean = LocalNetwork::isLocal,
        ): Boolean {
            if (https) return true
            if (connected == null || !isLocal(connected)) return false
            return !proxied || ServerProviders.isPrivateHost(host)
        }
    }
}

/** Plain http to a public address, refused before a single byte of the request went out. */
class CleartextRefusedException(
    /** Where it would have gone, for the log; the message is the user's. */
    val detail: String,
) : NotSentException(MESSAGE) {
    companion object {
        const val MESSAGE = "Refused unencrypted connection to a public address - use https."
    }
}

/** Adds the [CleartextGuard] unless the builder has one already. Every client gets it. */
fun OkHttpClient.Builder.guardCleartext(): OkHttpClient.Builder = apply {
    if (networkInterceptors().none { it is CleartextGuard }) addNetworkInterceptor(CleartextGuard())
}
