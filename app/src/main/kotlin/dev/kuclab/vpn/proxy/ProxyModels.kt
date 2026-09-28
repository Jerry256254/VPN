package dev.kuclab.vpn.proxy

enum class ProxyProtocol { HTTP, SOCKS4, SOCKS5 }

/**
 * One candidate exit node scraped from a public free-proxy list.
 * These typically die within minutes to a few hours, so every field
 * below is expected to go stale fast - see ProxyPool's rescan loop.
 */
data class ProxyEndpoint(
    val host: String,
    val port: Int,
    val protocol: ProxyProtocol,
    val source: String,
) {
    val key: String get() = "$protocol://$host:$port"

    // Mutable health-tracking fields, updated in place by ProxyValidator.
    var alive: Boolean = false
    var latencyMs: Long = Long.MAX_VALUE
    var dnsCapable: Boolean = false
    var lastCheckedAtMs: Long = 0L
    var consecutiveFailures: Int = 0

    /** tun2socks --proxy URL for this node. */
    fun toTun2socksProxyUrl(): String {
        val scheme = when (protocol) {
            ProxyProtocol.HTTP -> "http"
            ProxyProtocol.SOCKS4 -> "socks4"
            ProxyProtocol.SOCKS5 -> "socks5"
        }
        return "$scheme://$host:$port"
    }
}
