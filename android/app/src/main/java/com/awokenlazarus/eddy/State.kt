package com.awokenlazarus.eddy

import android.app.Application
import android.content.Context

enum class Phase { Stopped, Starting, Syncing, Synced, Paused, Stopping, Error }

data class NodeState(
    val phase: Phase = Phase.Stopped,
    val detail: String = "",
    val height: Long = 0,
    val headers: Long = 0,
    val progress: Double = 0.0,
    val tipTime: Long = 0,
    val ibd: Boolean = true,
    val peers: Int = 0,
    val netActive: Boolean = false,
    val pauseReason: String? = null,
    val blocksPerHour: Double = 0.0,
    val etaSeconds: Long? = null,
    val diskBytes: Long = 0,
    val recvBytes: Long = 0,
    val nodeRssBytes: Long = 0,
    val nodeCpuPct: Double = 0.0,
    val cacheMb: Double = 0.0,
    val dbcacheMb: Int = 0,
    val batteryPct: Int = -1,
    val charging: Boolean = false,
    val batteryTempC: Double = 0.0,
    val thermal: Int = 0,
    val wifi: Boolean = false,
    val restarts: Int = 0,
    val activeSyncSec: Long = 0,
    val lagP95Ms: Long = 0,
)

fun pauseText(reason: String?): String = when (reason) {
    "no_wifi" -> "Waiting for Wi-Fi"
    "not_charging" -> "Waiting for a charger"
    "hot" -> "Cooling down"
    "in_use" -> "Paused while you use the phone"
    "low_battery" -> "Battery low"
    "stopped" -> "Stopped"
    null -> ""
    else -> reason
}

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("eddy", Context.MODE_PRIVATE)
    var wantRunning: Boolean
        get() = p.getBoolean("want_running", false)
        set(v) = p.edit().putBoolean("want_running", v).apply()
    var chargingOnly: Boolean
        get() = p.getBoolean("charging_only", false)
        set(v) = p.edit().putBoolean("charging_only", v).apply()
    var pauseHot: Boolean
        get() = p.getBoolean("pause_hot", true)
        set(v) = p.edit().putBoolean("pause_hot", v).apply()
    var pauseInUse: Boolean
        get() = p.getBoolean("pause_in_use", false)
        set(v) = p.edit().putBoolean("pause_in_use", v).apply()
    var benchLogs: Boolean
        get() = p.getBoolean("bench_logs", true)
        set(v) = p.edit().putBoolean("bench_logs", v).apply()
    var synced: Boolean
        get() = p.getBoolean("synced", false)
        set(v) = p.edit().putBoolean("synced", v).commit().let { }
}

class EddyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                Diagnostics(this).event("app_crash", "thread" to t.name, "error" to e.stackTraceToString().take(4000))
            }
            previous?.uncaughtException(t, e)
        }
    }
}
