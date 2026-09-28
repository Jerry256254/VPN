package dev.kuclab.vpn.warp

/** One peer's counters, parsed from a `STATS rx=.. tx=.. hs=..` line. */
data class WarpStats(
    val rxBytes: Long,
    val txBytes: Long,
    val lastHandshakeSec: Long,
) {
    val hasHandshake: Boolean get() = lastHandshakeSec > 0

    companion object {
        fun parse(line: String): WarpStats? {
            if (!line.startsWith("STATS ")) return null
            var rx = -1L
            var tx = -1L
            var hs = 0L
            for (part in line.removePrefix("STATS ").split(' ')) {
                val kv = part.split('=', limit = 2)
                if (kv.size != 2) continue
                val n = kv[1].toLongOrNull() ?: continue
                when (kv[0]) {
                    "rx" -> rx = n
                    "tx" -> tx = n
                    "hs" -> hs = n
                }
            }
            if (rx < 0 || tx < 0) return null
            return WarpStats(rx, tx, hs)
        }
    }
}
