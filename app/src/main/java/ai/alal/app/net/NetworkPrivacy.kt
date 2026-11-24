package ai.alal.app.net

import android.content.Context
import ai.alal.app.data.SecurityUtils
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Properties

/**
 * Optional anonymising route for every connection Alal makes (web search, page
 * fetches, model downloads, mail, sign-in, donations).
 *
 *  - OFF:   direct connections (sites see the phone's IP).
 *  - TOR:   SOCKS5 to Orbot on 127.0.0.1:9050. Hostnames are resolved by Tor, so
 *           DNS doesn't leak either.
 *  - SOCKS: a user-provided SOCKS5 proxy.
 *
 * Fail-closed: when a proxy is selected and unreachable, requests fail instead
 * of silently going direct.
 */
object NetworkPrivacy {
    const val OFF = "OFF"
    const val TOR = "TOR"
    const val SOCKS = "SOCKS"

    data class Route(val mode: String = OFF, val host: String = "127.0.0.1", val port: Int = 9050)

    @Volatile var route: Route = Route()
        private set

    private const val KEY_MODE = "net_proxy_mode"
    private const val KEY_HOST = "net_proxy_host"
    private const val KEY_PORT = "net_proxy_port"

    fun init(context: Context) {
        val p = SecurityUtils.getEncryptedPrefs(context)
        route = Route(
            mode = p.getString(KEY_MODE, OFF) ?: OFF,
            host = p.getString(KEY_HOST, "127.0.0.1") ?: "127.0.0.1",
            port = p.getInt(KEY_PORT, 9050)
        )
    }

    fun save(context: Context, newRoute: Route) {
        val r = if (newRoute.mode == TOR) newRoute.copy(host = "127.0.0.1", port = 9050) else newRoute
        SecurityUtils.getEncryptedPrefs(context).edit()
            .putString(KEY_MODE, r.mode).putString(KEY_HOST, r.host).putInt(KEY_PORT, r.port).apply()
        route = r
    }

    /** Pure mapping from a Route to its Proxy: OFF is DIRECT, TOR/SOCKS are unresolved SOCKS, any other mode throws. */
    internal fun proxyFor(route: Route): Proxy = when (route.mode) {
        OFF -> Proxy.NO_PROXY
        // Unresolved: the proxy (Tor) does the DNS lookup, not the phone.
        TOR, SOCKS -> Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(route.host, route.port))
        else -> throw IllegalStateException("Unknown proxy mode '${route.mode}': refusing to connect directly")
    }

    /** An OkHttp client that honours the current route. Build per use so changes apply at once. */
    fun client(configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().apply {
            // DIRECT leaves OkHttp's default proxy selection untouched, as before.
            proxyFor(route).takeIf { it.type() != Proxy.Type.DIRECT }?.let { proxy(it) }
            configure()
        }.build()

    /** Route a JavaMail session (IMAP) through the same proxy. */
    fun applyToMail(props: Properties, protocol: String = "imaps") {
        val r = route
        if (r.mode == OFF) return
        props["mail.$protocol.socks.host"] = r.host
        props["mail.$protocol.socks.port"] = r.port.toString()
    }
}
