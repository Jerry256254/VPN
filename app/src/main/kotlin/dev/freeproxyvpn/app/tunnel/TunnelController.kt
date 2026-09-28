package dev.freeproxyvpn.app.tunnel

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import dev.freeproxyvpn.app.proxy.ProxyEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.random.Random

sealed class TunnelEvent {
    data class Log(val line: String) : TunnelEvent()
    data class Exited(val proxy: ProxyEndpoint, val exitCode: Int) : TunnelEvent()
}

/**
 * Owns exactly one running tun2socks process, wired to the VpnService's TUN
 * file descriptor via fdbridge (see fdbridge/main.go). Neither native binary
 * requires the NDK: both are pure-Go static Linux binaries, launched the
 * same way shadowsocks-android/v2rayNG launch their bundled cores, packaged
 * as jniLibs so PackageManager extracts them as executable files.
 *
 * The VPN's ParcelFileDescriptor is NOT owned/closed by this class - closing
 * it tears down the whole TUN interface, so it must outlive individual
 * tun2socks process restarts (proxy rotation, crashes) and is only closed by
 * VpnCoreService when the user disconnects.
 */
class TunnelController(
    private val context: Context,
    private val onEvent: (TunnelEvent) -> Unit,
) {
    private var process: Process? = null
    private var currentProxy: ProxyEndpoint? = null

    private val nativeLibDir: String
        get() = context.applicationInfo.nativeLibraryDir

    suspend fun start(tunFd: ParcelFileDescriptor, proxy: ProxyEndpoint): Boolean = coroutineScope {
        val sockName = "fpvpn_" + System.currentTimeMillis() + "_" + Random.nextInt(10000)
        val fdbridgeBin = File(nativeLibDir, "libfdbridge.so")
        val tun2socksBin = File(nativeLibDir, "libtun2socks.so")
        if (!fdbridgeBin.exists() || !tun2socksBin.exists()) {
            onEvent(TunnelEvent.Log("native binaries missing under $nativeLibDir"))
            return@coroutineScope false
        }

        val serverSocket = try {
            LocalServerSocket(sockName)
        } catch (t: Throwable) {
            onEvent(TunnelEvent.Log("failed to open local socket: ${t.message}"))
            return@coroutineScope false
        }

        val args = mutableListOf(
            fdbridgeBin.absolutePath,
            sockName,
            tun2socksBin.absolutePath,
            "--proxy", proxy.toTun2socksProxyUrl(),
            "--mtu", "1500",
            "--loglevel", "warning",
            "--udp-timeout", "30s",
        )

        val proc = try {
            ProcessBuilder(args)
                .redirectErrorStream(true)
                .directory(context.filesDir)
                .start()
        } catch (t: Throwable) {
            serverSocket.close()
            onEvent(TunnelEvent.Log("failed to launch tunnel process: ${t.message}"))
            return@coroutineScope false
        }
        process = proc
        currentProxy = proxy
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

        watchExit(proc, proxy)
        onEvent(TunnelEvent.Log("tunnel up via ${proxy.protocol} ${proxy.host}:${proxy.port}"))
        true
    }

    fun stop() {
        process?.destroy()
        process = null
        currentProxy = null
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
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                    lines.forEach { onEvent(TunnelEvent.Log("tun2socks: $it")) }
                }
            } catch (_: Throwable) {
                // process pipe closed on exit, nothing to do
            }
        }.apply { isDaemon = true }.start()
    }

    private fun watchExit(proc: Process, proxy: ProxyEndpoint) {
        Thread {
            val code = try {
                proc.waitFor()
            } catch (t: Throwable) {
                -1
            }
            if (process === proc) process = null
            onEvent(TunnelEvent.Exited(proxy, code))
        }.apply { isDaemon = true }.start()
    }
}
