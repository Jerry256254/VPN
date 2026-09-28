package dev.freeproxyvpn.app.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

private val IP_PORT_LINE = Regex("""^\s*(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})\s*$""")

/** Downloads and parses every configured public proxy list, in parallel. */
class ProxyListFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun fetchAll(sources: List<ProxySource> = ProxySources.all): List<ProxyEndpoint> =
        coroutineScope {
            sources
                .map { src -> async(Dispatchers.IO) { fetchOne(src) } }
                .awaitAll()
                .flatten()
                .distinctBy { it.key }
        }

    private suspend fun fetchOne(source: ProxySource): List<ProxyEndpoint> {
        val body = withTimeoutOrNull(10_000) { downloadBody(source.url) } ?: return emptyList()
        return try {
            when (source.format) {
                ListFormat.PLAIN_IP_PORT -> parsePlain(body, source)
                ListFormat.GEONODE_JSON -> parseGeonode(body, source)
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private suspend fun downloadBody(url: String): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun parsePlain(body: String, source: ProxySource): List<ProxyEndpoint> =
        body.lineSequence()
            .mapNotNull { line -> IP_PORT_LINE.matchEntire(line) }
            .map { m ->
                ProxyEndpoint(
                    host = m.groupValues[1],
                    port = m.groupValues[2].toInt(),
                    protocol = source.protocol,
                    source = source.name,
                )
            }
            .toList()

    private fun parseGeonode(body: String, source: ProxySource): List<ProxyEndpoint> {
        val root = org.json.JSONObject(body)
        val data: JSONArray = root.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<ProxyEndpoint>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val host = obj.optString("ip")
            if (host.isBlank()) continue
            val port = obj.optString("port").toIntOrNull() ?: continue
            val protocols = obj.optJSONArray("protocols")
            val proto = protocolFromGeonode(protocols)
            out += ProxyEndpoint(host, port, proto, source.name)
        }
        return out
    }

    private fun protocolFromGeonode(protocols: JSONArray?): ProxyProtocol {
        if (protocols == null) return ProxyProtocol.HTTP
        for (i in 0 until protocols.length()) {
            when (protocols.optString(i).lowercase()) {
                "socks5" -> return ProxyProtocol.SOCKS5
                "socks4" -> return ProxyProtocol.SOCKS4
            }
        }
        return ProxyProtocol.HTTP
    }
}
