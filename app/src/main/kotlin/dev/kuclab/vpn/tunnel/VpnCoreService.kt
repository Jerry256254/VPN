package dev.kuclab.vpn.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import dev.kuclab.vpn.R
import dev.kuclab.vpn.proxy.PoolSnapshot
import dev.kuclab.vpn.proxy.ProxyEndpoint
import dev.kuclab.vpn.proxy.ProxyPool
import dev.kuclab.vpn.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class VpnState {
    data object Idle : VpnState()
    data object Preparing : VpnState()
    data class Connected(val proxy: ProxyEndpoint) : VpnState()
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

        private val _pool = MutableStateFlow<PoolSnapshot?>(null)
        val poolSnapshot = _pool.asStateFlow()

        private val _log = MutableStateFlow("")
        val log = _log.asStateFlow()

        private const val MAX_ATTEMPTS_PER_CONNECT = 6
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var pool: ProxyPool? = null
    private var tunnel: TunnelController? = null
    private val excluded = mutableSetOf<String>()
    private var poolLogJob: Job? = null

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
        startForeground(NOTIF_ID, buildNotification("Skenuji proxy listy…"))
        excluded.clear()

        val p = ProxyPool(scope).also { pool = it }
        p.start()
        poolLogJob?.cancel()
        poolLogJob = scope.launch {
            p.snapshot.collect { _pool.value = it }
        }
        scope.launch { p.log.collect { _log.value = it } }

        scope.launch {
            val ok = connectLoop(p)
            if (!ok) {
                _state.value = VpnState.Failed("Nepodařilo se najít žádnou funkční proxy")
                stopForegroundAndSelf()
            }
        }
    }

    private suspend fun connectLoop(p: ProxyPool): Boolean {
        var attempt = 0
        while (attempt < MAX_ATTEMPTS_PER_CONNECT) {
            attempt++
            val proxy = p.awaitFirstLive(timeoutMs = 60_000) ?: return false
            if (proxy.key in excluded) {
                delay(1000)
                continue
            }
            val fd = vpnInterface ?: establishTun().also { vpnInterface = it } ?: return false

            val controller = TunnelController(this) { event -> handleTunnelEvent(event) }
            tunnel = controller
            val started = controller.start(fd, proxy)
            if (started) {
                _state.value = VpnState.Connected(proxy)
                updateNotification("Připojeno přes ${proxy.protocol} ${proxy.host}:${proxy.port}")
                return true
            } else {
                excluded += proxy.key
            }
        }
        return false
    }

    private fun handleTunnelEvent(event: TunnelEvent) {
        when (event) {
            is TunnelEvent.Log -> _log.value = (_log.value + "\n" + event.line).takeLast(8000)
            is TunnelEvent.Exited -> {
                _log.value = (_log.value + "\ntunnel exited (code ${event.exitCode}), rotating proxy").takeLast(8000)
                excluded += event.proxy.key
                if (_state.value is VpnState.Connected) {
                    val p = pool ?: return
                    scope.launch {
                        val ok = connectLoop(p)
                        if (!ok) {
                            _state.value = VpnState.Failed("Všechny dostupné proxy selhaly")
                            stopForegroundAndSelf()
                        }
                    }
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
            .setMtu(1500)
            .establish()
    } catch (t: Throwable) {
        _log.value = (_log.value + "\nfailed to establish TUN: ${t.message}").takeLast(8000)
        null
    }

    private fun disconnect() {
        tunnel?.stop()
        tunnel = null
        pool?.stop()
        pool = null
        poolLogJob?.cancel()
        vpnInterface?.let { try { it.close() } catch (_: Throwable) {} }
        vpnInterface = null
        _state.value = VpnState.Idle
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
