package com.awokenlazarus.eddy

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.awokenlazarus.eddy.ui.EddyScreen
import com.awokenlazarus.eddy.ui.Settings as UiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val prefs = Prefs(this)
        // Resume a sync Android stopped while the app was away.
        if (prefs.wantRunning) NodeService.start(this)

        setContent {
            EddyScreen(
                state = NodeService.state,
                prefs = prefs,
                ignoringBatteryOpt = { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) },
                onStart = { NodeService.start(this) },
                onStop = { NodeService.stop(this) },
                onSettingsChanged = { s: UiSettings ->
                    val benchChanged = s.benchLogs != prefs.benchLogs
                    prefs.chargingOnly = s.chargingOnly
                    prefs.pauseHot = s.pauseHot
                    prefs.pauseInUse = s.pauseInUse
                    prefs.benchLogs = s.benchLogs
                    Diagnostics(this).event("settings", "charging_only" to s.chargingOnly, "pause_hot" to s.pauseHot,
                        "pause_in_use" to s.pauseInUse, "bench_logs" to s.benchLogs)
                    NodeService.kicks.trySend(Unit)
                    if (benchChanged) toast("Timing logs change at the next node start")
                },
                onAllowBackground = { askBatteryExemption() },
                onShare = { shareDiagnostics() },
                diagPath = Diagnostics(this).root.path.substringAfter("/0/"),
            )
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun shareDiagnostics() {
        lifecycleScope.launch {
            val zip = withContext(Dispatchers.IO) {
                runCatching { Diagnostics(this@MainActivity).export(NodeService.dataDir(this@MainActivity)) }.getOrNull()
            }
            if (zip == null) { toast("Could not collect the diagnostics"); return@launch }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", zip)
            val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Share Eddy diagnostics"))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
