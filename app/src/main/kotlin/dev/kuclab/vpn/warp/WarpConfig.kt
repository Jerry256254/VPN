package dev.kuclab.vpn.warp

import org.json.JSONObject
import java.io.File

/**
 * WireGuard credentials for one WARP identity, minted by
 * `libwgtunnel.so register`. Field names mirror the Go WgConfig JSON -
 * keep them in sync.
 */
data class WarpConfig(
    val privateKey: String,
    val publicKey: String,
    val accountId: String,
    val accountToken: String,
    val peerPublicKey: String,
    val endpointHost: String,
    val endpointPort: Int,
    val interfaceV4: String,
    val interfaceV6: String,
    val mtu: Int,
) {
    val endpoint: String get() = "$endpointHost:$endpointPort"
    val shortAccount: String get() = accountId.take(8).ifEmpty { "—" }

    fun toJson(): JSONObject = JSONObject()
        .put("private_key", privateKey)
        .put("public_key", publicKey)
        .put("account_id", accountId)
        .put("account_token", accountToken)
        .put("peer_public_key", peerPublicKey)
        .put("endpoint_host", endpointHost)
        .put("endpoint_port", endpointPort)
        .put("interface_v4", interfaceV4)
        .put("interface_v6", interfaceV6)
        .put("mtu", mtu)

    companion object {
        fun fromJson(o: JSONObject): WarpConfig = WarpConfig(
            privateKey = o.getString("private_key"),
            publicKey = o.optString("public_key", ""),
            accountId = o.optString("account_id", ""),
            accountToken = o.optString("account_token", ""),
            peerPublicKey = o.getString("peer_public_key"),
            endpointHost = o.optString("endpoint_host", "engage.cloudflareclient.com"),
            endpointPort = o.optInt("endpoint_port", 2408),
            interfaceV4 = o.optString("interface_v4", ""),
            interfaceV6 = o.optString("interface_v6", ""),
            mtu = o.optInt("mtu", 1280),
        )

        fun load(f: File): WarpConfig? = try {
            if (!f.exists()) null else fromJson(JSONObject(f.readText()))
        } catch (_: Throwable) {
            null
        }

        /** Finds the JSON object in `register` stdout (first {...} line that parses). */
        fun parseStdout(stdout: String): WarpConfig? {
            for (raw in stdout.lineSequence()) {
                val line = raw.trim()
                if (!line.startsWith("{")) continue
                try {
                    val cfg = fromJson(JSONObject(line))
                    if (cfg.privateKey.isNotEmpty() && cfg.peerPublicKey.isNotEmpty()) return cfg
                } catch (_: Throwable) {
                    // not the config line, keep scanning
                }
            }
            return null
        }
    }
}
