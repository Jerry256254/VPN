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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import dev.kuclab.vpn.R
import dev.kuclab.vpn.tunnel.VpnCoreService
import dev.kuclab.vpn.tunnel.VpnState
import dev.kuclab.vpn.warp.WarpStats
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var revText: TextView
    private lateinit var lamp: View
    private lateinit var linkState: TextView
    private lateinit var linkSub: TextView
    private lateinit var endpointVal: TextView
    private lateinit var relayVal: TextView
    private lateinit var handshakeVal: TextView
    private lateinit var rxVal: TextView
    private lateinit var txVal: TextView
    private lateinit var accountVal: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var engageButton: MaterialButton

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpnService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        revText = findViewById(R.id.revText)
        lamp = findViewById(R.id.lamp)
        linkState = findViewById(R.id.linkState)
        linkSub = findViewById(R.id.linkSub)
        endpointVal = findViewById(R.id.endpointVal)
        relayVal = findViewById(R.id.relayVal)
        handshakeVal = findViewById(R.id.handshakeVal)
        rxVal = findViewById(R.id.rxVal)
        txVal = findViewById(R.id.txVal)
        accountVal = findViewById(R.id.accountVal)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        engageButton = findViewById(R.id.engageButton)

        revText.text = "REV ${appVersion()}"
        engageButton.setOnClickListener { onEngageClicked() }

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

    private fun onEngageClicked() {
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
                    VpnCoreService.stats.collect { renderStats(it) }
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
                setLink(R.string.link_down, R.color.faint)
                linkSub.setText(R.string.sub_idle)
                endpointVal.setText(R.string.dash)
                relayVal.setText(R.string.dash)
                accountVal.setText(R.string.dash)
                setButtonEngage(getString(R.string.engage))
            }
            is VpnState.Preparing -> {
                setLink(R.string.link_est, R.color.phosphor)
                linkSub.setText(R.string.sub_est)
                setButtonCut(getString(R.string.cut))
            }
            is VpnState.Connected -> {
                setLink(R.string.link_up, R.color.green)
                linkSub.setText(R.string.sub_up)
                endpointVal.text = state.endpoint
                relayVal.text = state.resolvedIp
                accountVal.text = state.accountShort
                setButtonCut(getString(R.string.cut))
            }
            is VpnState.Failed -> {
                setLink(R.string.link_fault, R.color.red)
                linkSub.text = state.reason
                endpointVal.setText(R.string.dash)
                relayVal.setText(R.string.dash)
                accountVal.setText(R.string.dash)
                setButtonEngage(getString(R.string.retry))
            }
        }
    }

    private fun renderStats(stats: WarpStats?) {
        if (stats == null) {
            handshakeVal.setText(R.string.dash)
            rxVal.setText(R.string.dash)
            txVal.setText(R.string.dash)
            return
        }
        handshakeVal.text = if (stats.hasHandshake) {
            formatAge(System.currentTimeMillis() / 1000 - stats.lastHandshakeSec)
        } else {
            getString(R.string.dash)
        }
        rxVal.text = formatBytes(stats.rxBytes)
        txVal.text = formatBytes(stats.txBytes)
    }

    private fun setLink(labelRes: Int, colorRes: Int) {
        linkState.setText(labelRes)
        val color = ContextCompat.getColor(this, colorRes)
        linkState.setTextColor(color)
        lamp.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun setButtonEngage(label: String) {
        engageButton.text = label
        engageButton.backgroundTintList = tint(R.color.red)
        engageButton.setTextColor(ContextCompat.getColor(this, R.color.red_ink))
        engageButton.strokeWidth = 0
    }

    private fun setButtonCut(label: String) {
        engageButton.text = label
        engageButton.backgroundTintList = tint(android.R.color.transparent)
        engageButton.setTextColor(ContextCompat.getColor(this, R.color.red))
        engageButton.strokeColor = tint(R.color.red)
        engageButton.strokeWidth = (2 * resources.displayMetrics.density).toInt()
    }

    private fun tint(colorRes: Int): ColorStateList =
        ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))

    private fun appVersion(): String = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    private fun playEntryAnimation() {
        val views = listOf(
            R.id.unitRow, R.id.linkPanel, R.id.readoutPanel, R.id.logPanel, R.id.engageButton,
        ).map { findViewById<View>(it) }
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 18f
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(60L * i)
                .setDuration(200L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    companion object {
        fun formatBytes(n: Long): String {
            if (n < 1024) return "$n B"
            val kb = n / 1024.0
            if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
            return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
        }

        fun formatAge(ageSec: Long): String {
            if (ageSec < 0) return "—"
            if (ageSec < 90) return "${ageSec}S"
            val m = ageSec / 60
            if (m < 90) return "${m}M"
            return "${m / 60}H"
        }
    }
}
