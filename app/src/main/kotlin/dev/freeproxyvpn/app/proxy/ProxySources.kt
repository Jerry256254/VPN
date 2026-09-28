package dev.freeproxyvpn.app.proxy

enum class ListFormat { PLAIN_IP_PORT, GEONODE_JSON }

data class ProxySource(
    val name: String,
    val url: String,
    val protocol: ProxyProtocol,
    val format: ListFormat,
)

/**
 * Public, no-auth-required free proxy list mirrors. These are third-party
 * aggregators scraping the open web; availability, freshness and honesty of
 * any single source varies constantly, which is why we pull from several and
 * let ProxyValidator be the actual source of truth about what currently
 * works (see ProxyPool).
 */
object ProxySources {
    val all: List<ProxySource> = listOf(
        ProxySource(
            "TheSpeedX-http",
            "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
            ProxyProtocol.HTTP, ListFormat.PLAIN_IP_PORT,
        ),
        ProxySource(
            "TheSpeedX-socks5",
            "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
            ProxyProtocol.SOCKS5, ListFormat.PLAIN_IP_PORT,
        ),
        ProxySource(
            "TheSpeedX-socks4",
            "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks4.txt",
            ProxyProtocol.SOCKS4, ListFormat.PLAIN_IP_PORT,
        ),
        ProxySource(
            "ShiftyTR-http",
            "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/http.txt",
            ProxyProtocol.HTTP, ListFormat.PLAIN_IP_PORT,
        ),
        ProxySource(
            "ShiftyTR-socks5",
            "https://raw.githubusercontent.com/ShiftyTR/Proxy-List/master/socks5.txt",
            ProxyProtocol.SOCKS5, ListFormat.PLAIN_IP_PORT,
        ),
        ProxySource(
            "clarketm-http",
            "https://raw.githubusercontent.com/clarketm/proxy-list/master/proxy-list-raw.txt",
            ProxyProtocol.HTTP, ListFormat.PLAIN_IP_PORT,
        ),
        ProxySource(
            "proxyscrape-http",
            "https://api.proxyscrape.com/v2/?request=getproxies&protocol=http&timeout=8000",
            ProxyProtocol.HTTP, ListFormat.PLAIN_IP_PORT,
        ),
        ProxySource(
            "proxyscrape-socks5",
            "https://api.proxyscrape.com/v2/?request=getproxies&protocol=socks5&timeout=8000",
            ProxyProtocol.SOCKS5, ListFormat.PLAIN_IP_PORT,
        ),
        ProxySource(
            "geonode",
            "https://proxylist.geonode.com/api/proxy-list?limit=500&page=1&sort_by=lastChecked&sort_type=desc",
            ProxyProtocol.HTTP, ListFormat.GEONODE_JSON,
        ),
    )
}
