package dev.kuclab.vpn.tunnel

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import dev.kuclab.vpn.warp.WarpConfig
import dev.kuclab.vpn.warp.WarpStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.random.Random

sealed class TunnelEvent {
    data class Log(val line: String) : TunnelEvent()
    data class Stats(val stats: WarpStats) : TunnelEvent()
    data class Exited(val exitCode: Int) : TunnelEvent()
}

/**
 * Owns exactly one running `libwgtunnel.so run` process (wireguard-go
 * userspace device), wired to the VpnService's TUN file descriptor: the fd
 * is handed over an abstract Unix socket via SCM_RIGHTS, the same technique
 * as Clash for Android / Matsuri. The binary is a pure-Go static executable,
 * no NDK involved, packaged as jniLibs so PackageManager extracts it as an
 * executable file.
 *
 * Also owns WARP enrollment: on first run (or after identity rotation) it
 * executes `libwgtunnel.so register` and stores the resulting config in the
 * app-private files dir. One identity per install, reused across connects.
 *
 * The VPN's ParcelFileDescriptor is NOT owned/closed by this class - closing
 * it tears down the whole TUN interface, so it must outlive individual
 * process restarts and is only closed by VpnCoreService on disconnect.
 */
class TunnelController(
    private val context: Context,
    private val onEvent: (TunnelEvent) -> Unit,
) {
    private var process: Process? = null

    @Volatile
    private var tunnelUp = false

    /** Resolved relay address from the TUNNEL-UP line, valid after start(). */
    @Volatile
    var resolvedEndpoint: String = ""
        private set

    /** Config the running tunnel was started with, valid after start(). */
    @Volatile
    var config: WarpConfig? = null
        private set

    private val nativeLibDir: String
        get() = context.applicationInfo.nativeLibraryDir

    private val configFile: File
        get() = File(context.filesDir, "warp/warp.json")

    suspend fun start(tunFd: ParcelFileDescriptor): Boolean = coroutineScope {
        tunnelUp = false
        resolvedEndpoint = ""

        val bin = File(nativeLibDir, "libwgtunnel.so")
        if (!bin.exists()) {
            onEvent(TunnelEvent.Log("native core missing under $nativeLibDir"))
            return@coroutineScope false
        }

        val cfg = WarpConfig.load(configFile) ?: run {
            onEvent(TunnelEvent.Log("no warp identity, enrolling…"))
            enroll(bin) ?: return@coroutineScope false
        }
        config = cfg

        val sockName = "kuclab_" + System.currentTimeMillis() + "_" + Random.nextInt(10000)
        val serverSocket = try {
            LocalServerSocket(sockName)
        } catch (t: Throwable) {
            onEvent(TunnelEvent.Log("failed to open local socket: ${t.message}"))
            return@coroutineScope false
        }

        val proc = try {
            ProcessBuilder(
                listOf(bin.absolutePath, "run", sockName, "--config", configFile.absolutePath),
            )
                .redirectErrorStream(true)
                .directory(context.filesDir)
                .start()
        } catch (t: Throwable) {
            serverSocket.close()
            onEvent(TunnelEvent.Log("failed to launch tunnel process: ${t.message}"))
            return@coroutineScope false
        }
        process = proc
        pumpOutput(proc)

        val handedOff = withTimeoutOrNull(5000) {
            withContext(Dispatchers.IO) {
                handOffFd(serverSocket, tunFd)
            }
        } ?: false
        serverSocket.close()

        if (!handedOff) {
            onEvent(TunnelEvent.Log("fd handoff to native tunnel process failed/timed out"))
            proc.destroy()
            return@coroutineScope false
        }

        // The handoff only proves the fd was sent; TUNNEL-UP proves the
        // WireGuard device is actually configured and running.
        val deadlineMs = System.currentTimeMillis() + 12_000
        while (!tunnelUp && System.currentTimeMillis() < deadlineMs) {
            if (!proc.isAlive) break
            delay(100)
        }
        if (!tunnelUp) {
            onEvent(TunnelEvent.Log("tunnel process never came up (exit=${proc.exitValueSafe()})"))
            proc.destroy()
            return@coroutineScope false
        }

        watchExit(proc)
        onEvent(TunnelEvent.Log("tunnel up via wireguard ${cfg.endpoint} ($resolvedEndpoint)"))
        true
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    private suspend fun enroll(bin: File): WarpConfig? {
        val proc = try {
            ProcessBuilder(listOf(bin.absolutePath, "register"))
                .redirectErrorStream(true)
                .directory(context.filesDir)
                .start()
        } catch (t: Throwable) {
            onEvent(TunnelEvent.Log("enroll launch failed: ${t.message}"))
            return null
        }
        val outDeferred = coroutineScope {
            async(Dispatchers.IO) {
                try {
                    proc.inputStream.bufferedReader().readText()
                } catch (_: Throwable) {
                    ""
                }
            }
        }
        val finished = withTimeoutOrNull(45_000) {
            withContext(Dispatchers.IO) { proc.waitFor() }
            true
        } ?: false
        if (!finished) {
            proc.destroy()
            onEvent(TunnelEvent.Log("enroll timed out"))
            return null
        }
        val out = outDeferred.await()
        if (proc.exitValue() != 0) {
            val tail = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.lastOrNull()
            onEvent(TunnelEvent.Log("enroll failed: ${tail ?: "exit ${proc.exitValue()}"}"))
            return null
        }
        val cfg = WarpConfig.parseStdout(out)
        if (cfg == null) {
            onEvent(TunnelEvent.Log("enroll failed: no config in output"))
            return null
        }
        try {
            configFile.parentFile?.mkdirs()
            configFile.writeText(cfg.toJson().toString())
        } catch (t: Throwable) {
            onEvent(TunnelEvent.Log("enroll failed: cannot store identity (${t.message})"))
            return null
        }
        onEvent(TunnelEvent.Log("warp identity enrolled (${cfg.shortAccount})"))
        return cfg
    }

    private fun handOffFd(serverSocket: LocalServerSocket, tunFd: ParcelFileDescriptor): Boolean {
        return try {
            val client: LocalSocket = serverSocket.accept()
            client.setFileDescriptorsForSend(arrayOf(tunFd.fileDescriptor))
            client.outputStream.write(1)
            client.outputStream.flush()
            client.close()
            true
        } catch (t: Throwable) {
            onEvent(TunnelEvent.Log("handoff error: ${t.message}"))
            false
        }
    }

    private fun pumpOutput(proc: Process) {
        Thread {
            var handshakeLogged = false
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        when {
                            line.startsWith("TUNNEL-UP") -> {
                                resolvedEndpoint = line.substringAfter("endpoint=", "").trim()
                                tunnelUp = true
                            }
                            line.startsWith("STATS ") -> {
                                WarpStats.parse(line)?.let { st ->
                                    onEvent(TunnelEvent.Stats(st))
                                    if (!handshakeLogged && st.hasHandshake) {
                                        handshakeLogged = true
                                        onEvent(TunnelEvent.Log("wireguard handshake ok"))
                                    }
                                }
                            }
                            line.isNotBlank() -> onEvent(TunnelEvent.Log("wg: $line"))
                        }
                    }
                }
            } catch (_: Throwable) {
                // process pipe closed on exit, nothing to do
            }
        }.apply { isDaemon = true }.start()
    }

    private fun watchExit(proc: Process) {
        Thread {
            val code = try {
                proc.waitFor()
            } catch (t: Throwable) {
                -1
            }
            if (process === proc) process = null
            onEvent(TunnelEvent.Exited(code))
        }.apply { isDaemon = true }.start()
    }

    private fun Process.exitValueSafe(): Int = try {
        exitValue()
    } catch (_: Throwable) {
        -999
    }
}
