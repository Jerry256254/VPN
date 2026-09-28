package dev.freeproxyvpn.app.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class PoolSnapshot(
    val liveCount: Int,
    val totalKnown: Int,
    val lastScanAtMs: Long,
    val currentBest: ProxyEndpoint?,
)

/**
 * Keeps a live, ranked pool of free-proxy endpoints. Free proxies typically
 * die within minutes to a few hours, so this rescans on a short cycle,
 * evicts anything that stays dead too long, and exposes the current best
 * live node for TunnelController to fail over onto.
 */
class ProxyPool(
    private val scope: CoroutineScope,
    private val fetcher: ProxyListFetcher = ProxyListFetcher(),
    private val validator: ProxyValidator = ProxyValidator(),
    private val rescanInterval: kotlin.time.Duration = 3.minutes,
    private val fullRefetchEvery: Int = 5, // re-pull the source lists every Nth cycle
    private val maxConsecutiveFailuresBeforeEviction: Int = 3,
    private val requireDnsCapable: Boolean = false,
) {
    private val known = ConcurrentHashMap<String, ProxyEndpoint>()
    private var cycleCount = 0
    private var loopJob: Job? = null

    private val _snapshot = MutableStateFlow(PoolSnapshot(0, 0, 0L, null))
    val snapshot: StateFlow<PoolSnapshot> = _snapshot.asStateFlow()

    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (true) {
                runCycle()
                delay(rescanInterval)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** Blocks (suspends) until at least one live proxy is available, or timeout elapses. */
    suspend fun awaitFirstLive(timeoutMs: Long = 45_000): ProxyEndpoint? {
        val deadline = System.currentTimeMillis() + timeoutMs
        best()?.let { return it }
        if (loopJob?.isActive != true) start()
        while (System.currentTimeMillis() < deadline) {
            best()?.let { return it }
            delay(500)
        }
        return null
    }

    fun best(excluding: Set<String> = emptySet()): ProxyEndpoint? =
        known.values
            .filter { it.alive && it.key !in excluding }
            .filter { !requireDnsCapable || it.dnsCapable }
            .minByOrNull { it.latencyMs }
            ?: known.values.filter { it.alive && it.key !in excluding }.minByOrNull { it.latencyMs }

    private suspend fun runCycle() {
        cycleCount++
        appendLog("scan #$cycleCount starting (${known.size} known)")

        if (cycleCount == 1 || cycleCount % fullRefetchEvery == 0) {
            val fetched = try {
                fetcher.fetchAll()
            } catch (t: Throwable) {
                appendLog("fetch failed: ${t.message}")
                emptyList()
            }
            for (ep in fetched) known.putIfAbsent(ep.key, ep)
            appendLog("fetched ${fetched.size} candidates, pool now ${known.size}")
        }

        val toCheck = known.values.toList()
        val checked = try {
            validator.validateAll(toCheck)
        } catch (t: Throwable) {
            appendLog("validation pass failed: ${t.message}")
            toCheck
        }

        var evicted = 0
        for (ep in checked) {
            if (!ep.alive && ep.consecutiveFailures >= maxConsecutiveFailuresBeforeEviction) {
                known.remove(ep.key)
                evicted++
            }
        }

        val liveCount = known.values.count { it.alive }
        _snapshot.value = PoolSnapshot(
            liveCount = liveCount,
            totalKnown = known.size,
            lastScanAtMs = System.currentTimeMillis(),
            currentBest = best(),
        )
        appendLog("scan #$cycleCount done: $liveCount alive / ${known.size} known, evicted $evicted")
    }

    private fun appendLog(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())
        _log.value = (_log.value + "[$stamp] $line\n").takeLast(8000)
    }
}
