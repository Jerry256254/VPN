package dev.freeproxyvpn.app.ui

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.freeproxyvpn.app.R
import dev.freeproxyvpn.app.tunnel.VpnCoreService
import dev.freeproxyvpn.app.tunnel.VpnState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var poolText: TextView
    private lateinit var logText: TextView
    private lateinit var connectButton: Button

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpnService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        poolText = findViewById(R.id.poolText)
        logText = findViewById(R.id.logText)
        connectButton = findViewById(R.id.connectButton)

        connectButton.setOnClickListener { onConnectClicked() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001,
                )
            }
        }

        observeState()
    }

    private fun onConnectClicked() {
        val current = VpnCoreService.state.value
        if (current is VpnState.Connected || current is VpnState.Preparing) {
            stopVpnService()
        } else {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                startVpnService()
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, VpnCoreService::class.java).setAction(VpnCoreService.ACTION_CONNECT)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, VpnCoreService::class.java).setAction(VpnCoreService.ACTION_DISCONNECT)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    VpnCoreService.state.collect { state ->
                        statusText.text = when (state) {
                            is VpnState.Idle -> getString(R.string.status_idle)
                            is VpnState.Preparing -> getString(R.string.status_scanning)
                            is VpnState.Connected -> getString(
                                R.string.status_connected,
                                "${state.proxy.host}:${state.proxy.port}",
                                state.proxy.protocol.name,
                            )
                            is VpnState.Failed -> state.reason
                        }
                        connectButton.text = if (state is VpnState.Connected || state is VpnState.Preparing) {
                            getString(R.string.disconnect)
                        } else {
                            getString(R.string.connect)
                        }
                    }
                }
                launch {
                    VpnCoreService.poolSnapshot.collect { snap ->
                        poolText.text = getString(R.string.pool_status, snap?.liveCount ?: 0)
                    }
                }
                launch {
                    VpnCoreService.log.collect { log ->
                        logText.text = log
                    }
                }
            }
        }
    }
}
