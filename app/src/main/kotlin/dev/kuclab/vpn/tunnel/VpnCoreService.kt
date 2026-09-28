package dev.kuclab.vpn.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import dev.kuclab.vpn.R
import dev.kuclab.vpn.ui.MainActivity
import dev.kuclab.vpn.warp.WarpStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class VpnState {
    data object Idle : VpnState()
    data object Preparing : VpnState()
    data class Connected(
        val endpoint: String,
        val resolvedIp: String,
        val accountShort: String,
    ) : VpnState()
    data class Failed(val reason: String) : VpnState()
}

class VpnCoreService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "dev.kuclab.vpn.action.CONNECT"
        const val ACTION_DISCONNECT = "dev.kuclab.vpn.action.DISCONNECT"
        private const val CHANNEL_ID = "vpn_tunnel"
        private const val NOTIF_ID = 1

        private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
        val state = _state.asStateFlow()

        private val _stats = MutableStateFlow<WarpStats?>(null)
        val stats = _stats.asStateFlow()

        private val _log = MutableStateFlow("[BOOT] kuclab vpn-01 // wireguard core ready")
        val log = _log.asStateFlow()

        private const val MAX_ATTEMPTS = 6
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnel: TunnelController? = null
    private var connectJob: Job? = null
    private var consecutiveFailures = 0
    private var reEnrolled = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        disconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun connect() {
        if (_state.value is VpnState.Connected || _state.value is VpnState.Preparing) return
        _state.value = VpnState.Preparing
        _stats.value = null
        consecutiveFailures = 0
        reEnrolled = false
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_connecting)))
        connectJob?.cancel()
        connectJob = scope.launch { connectLoop() }
    }

    private suspend fun connectLoop() {
        if (vpnInterface == null) {
            vpnInterface = establishTun()
            if (vpnInterface == null) {
                _state.value = VpnState.Failed("Nepodařilo se vytvořit TUN rozhraní")
                stopForegroundAndSelf()
                return
            }
        }
        val fd = vpnInterface ?: return

        while (consecutiveFailures < MAX_ATTEMPTS) {
            if (!reEnrolled && consecutiveFailures == 3) {
                reEnrolled = true
                appendLog("3 pokusy selhaly, rotuji warp identitu…")
                try {
                    File(filesDir, "warp/warp.json").delete()
                } catch (_: Throwable) {
                }
            }
            val controller = TunnelController(this, ::handleTunnelEvent)
            tunnel = controller
            if (controller.start(fd)) {
                consecutiveFailures = 0
                val cfg = controller.config
                _state.value = VpnState.Connected(
                    endpoint = cfg?.endpoint ?: "—",
                    resolvedIp = controller.resolvedEndpoint.ifEmpty { "—" },
                    accountShort = cfg?.shortAccount ?: "—",
                )
                updateNotification(getString(R.string.notif_connected, cfg?.endpoint ?: ""))
                return
            }
            consecutiveFailures++
            tunnel = null
            delay(2000L * consecutiveFailures)
        }

        _state.value = VpnState.Failed("Tunel se nedaří navázat ani udržet")
        stopForegroundAndSelf()
    }

    private fun handleTunnelEvent(event: TunnelEvent) {
        when (event) {
            is TunnelEvent.Log -> appendLog(event.line)
            is TunnelEvent.Stats -> _stats.value = event.stats
            is TunnelEvent.Exited -> {
                appendLog("tunnel exited (code ${event.exitCode})")
                _stats.value = null
                if (_state.value is VpnState.Connected) {
                    // Dropped mid-session: rotate back through the loop honestly
                    // showing ESTABLISHING, not a stale CONNECTED.
                    _state.value = VpnState.Preparing
                    consecutiveFailures++
                    connectJob?.cancel()
                    connectJob = scope.launch { connectLoop() }
                }
            }
        }
    }

    private fun establishTun(): ParcelFileDescriptor? = try {
        Builder()
            .setSession("KucLab VPN")
            .addAddress("10.111.222.1", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("9.9.9.9")
            .setMtu(1280)
            .establish()
    } catch (t: Throwable) {
        appendLog("failed to establish TUN: ${t.message}")
        null
    }

    private fun disconnect() {
        connectJob?.cancel()
        tunnel?.stop()
        tunnel = null
        vpnInterface?.let { try { it.close() } catch (_: Throwable) {} }
        vpnInterface = null
        _stats.value = null
        _state.value = VpnState.Idle
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun appendLog(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _log.value = (_log.value + "\n[$stamp] $line").takeLast(8000)
    }

    private fun ensureNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
    }
}
