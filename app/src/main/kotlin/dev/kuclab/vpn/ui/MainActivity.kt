package dev.kuclab.vpn.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import dev.kuclab.vpn.R
import dev.kuclab.vpn.tunnel.VpnCoreService
import dev.kuclab.vpn.tunnel.VpnState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var statusPillText: TextView
    private lateinit var statusHeadline: TextView
    private lateinit var statusSubline: TextView
    private lateinit var powerButton: AppCompatImageButton
    private lateinit var nodeHost: TextView
    private lateinit var nodeMeta: TextView
    private lateinit var poolCount: TextView
    private lateinit var poolSub: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var connectButton: MaterialButton

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpnService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        statusPillText = findViewById(R.id.statusPillText)
        statusHeadline = findViewById(R.id.statusHeadline)
        statusSubline = findViewById(R.id.statusSubline)
        powerButton = findViewById(R.id.powerButton)
        nodeHost = findViewById(R.id.nodeHost)
        nodeMeta = findViewById(R.id.nodeMeta)
        poolCount = findViewById(R.id.poolCount)
        poolSub = findViewById(R.id.poolSub)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        connectButton = findViewById(R.id.connectButton)

        connectButton.setOnClickListener { onConnectClicked() }
        powerButton.setOnClickListener { onConnectClicked() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001,
                )
            }
        }

        playEntryAnimation()
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
                    VpnCoreService.state.collect { renderState(it) }
                }
                launch {
                    VpnCoreService.poolSnapshot.collect { snap ->
                        val live = snap?.liveCount ?: 0
                        val known = snap?.totalKnown ?: 0
                        poolCount.text = live.toString()
                        poolSub.text = if (snap == null || (live == 0 && known == 0)) {
                            getString(R.string.pool_idle_hint)
                        } else {
                            getString(R.string.pool_known, known)
                        }
                    }
                }
                launch {
                    VpnCoreService.log.collect { log ->
                        logText.text = log
                        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        }
    }

    private fun renderState(state: VpnState) {
        when (state) {
            is VpnState.Idle -> {
                setStatus(R.string.pill_idle, R.color.status_idle)
                statusHeadline.setText(R.string.hero_idle_title)
                statusSubline.setText(R.string.hero_idle_sub)
                powerButton.isSelected = false
                powerButton.imageTintList = tint(R.color.power_icon_off)
                nodeHost.setText(R.string.node_empty)
                nodeMeta.setText(R.string.node_empty)
                setButtonPrimary(getString(R.string.connect))
            }
            is VpnState.Preparing -> {
                setStatus(R.string.pill_preparing, R.color.status_warn)
                statusHeadline.setText(R.string.hero_preparing_title)
                statusSubline.setText(R.string.hero_preparing_sub)
                powerButton.isSelected = false
                powerButton.imageTintList = tint(R.color.status_warn)
                setButtonGhost(getString(R.string.disconnect))
            }
            is VpnState.Connected -> {
                val proxy = state.proxy
                setStatus(R.string.pill_connected, R.color.status_ok)
                statusHeadline.setText(R.string.hero_connected_title)
                statusSubline.setText(R.string.hero_connected_sub)
                powerButton.isSelected = true
                powerButton.imageTintList = tint(R.color.accent)
                nodeHost.text = "${proxy.host}:${proxy.port}"
                val latency = if (proxy.latencyMs == Long.MAX_VALUE) {
                    getString(R.string.node_empty)
                } else {
                    getString(R.string.latency_ms, proxy.latencyMs)
                }
                nodeMeta.text = getString(R.string.node_meta, proxy.protocol.name, latency)
                setButtonGhost(getString(R.string.disconnect))
            }
            is VpnState.Failed -> {
                setStatus(R.string.pill_failed, R.color.status_err)
                statusHeadline.setText(R.string.hero_failed_title)
                statusSubline.text = state.reason
                powerButton.isSelected = false
                powerButton.imageTintList = tint(R.color.power_icon_off)
                nodeHost.setText(R.string.node_empty)
                nodeMeta.setText(R.string.node_empty)
                setButtonPrimary(getString(R.string.retry))
            }
        }
    }

    private fun setStatus(labelRes: Int, colorRes: Int) {
        statusPillText.setText(labelRes)
        statusDot.backgroundTintList = tint(colorRes)
    }

    private fun setButtonPrimary(label: String) {
        connectButton.text = label
        connectButton.backgroundTintList = tint(R.color.accent)
        connectButton.setTextColor(ContextCompat.getColor(this, R.color.accent_ink))
        connectButton.strokeWidth = 0
    }

    private fun setButtonGhost(label: String) {
        connectButton.text = label
        connectButton.backgroundTintList = tint(android.R.color.transparent)
        connectButton.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        connectButton.strokeColor = tint(R.color.btn_ghost_stroke)
        connectButton.strokeWidth = resources.getDimensionPixelSize(R.dimen.btn_ghost_stroke)
    }

    private fun tint(colorRes: Int): ColorStateList =
        ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))

    private fun playEntryAnimation() {
        val views = listOf(
            R.id.headerRow, R.id.heroCard, R.id.statsRow, R.id.logCard, R.id.connectButton,
        ).map { findViewById<View>(it) }
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 28f
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(70L * i)
                .setDuration(420L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}
