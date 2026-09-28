package dev.kuclab.vpn.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Confirms a scraped proxy is actually alive right now (public lists are
 * full of stale/dead/honeypot entries) and measures how it behaves, instead
 * of trusting the source list's own claims.
 */
class ProxyValidator(
    private val concurrency: Int = 24,
    private val probeUrl: String = "https://www.gstatic.com/generate_204",
) {
    suspend fun validateAll(candidates: List<ProxyEndpoint>): List<ProxyEndpoint> = coroutineScope {
        val gate = Semaphore(concurrency)
        candidates
            .map { ep -> async(Dispatchers.IO) { gate.withPermit { validateOne(ep) }; ep } }
            .awaitAll()
    }

    private fun validateOne(ep: ProxyEndpoint) {
        val start = System.currentTimeMillis()
        val ok = try {
            checkTcpReachable(ep)
        } catch (t: Throwable) {
            false
        }
        ep.lastCheckedAtMs = System.currentTimeMillis()
        if (!ok) {
            ep.alive = false
            ep.dnsCapable = false
            ep.consecutiveFailures++
            return
        }
        ep.alive = true
        ep.latencyMs = System.currentTimeMillis() - start
        ep.consecutiveFailures = 0

        if (ep.protocol == ProxyProtocol.SOCKS5) {
            ep.dnsCapable = try {
                Socks5UdpDnsProbe.probe(ep.host, ep.port, timeoutMs = 3000)
            } catch (t: Throwable) {
                false
            }
        } else {
            // Plain HTTP proxies are TCP-only (CONNECT method): they cannot
            // relay UDP, so DNS-over-this-proxy is structurally impossible.
            ep.dnsCapable = false
        }
    }

    private fun checkTcpReachable(ep: ProxyEndpoint): Boolean {
        val javaProxyType = when (ep.protocol) {
            ProxyProtocol.HTTP -> Proxy.Type.HTTP
            ProxyProtocol.SOCKS4, ProxyProtocol.SOCKS5 -> Proxy.Type.SOCKS
        }
        val proxy = Proxy(javaProxyType, InetSocketAddress.createUnresolved(ep.host, ep.port))
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
        return try {
            client.newCall(Request.Builder().url(probeUrl).build()).execute().use { resp ->
                resp.code in 200..299 || resp.code == 204
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
