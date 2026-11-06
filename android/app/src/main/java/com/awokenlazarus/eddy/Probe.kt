package com.awokenlazarus.eddy

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Readings of the phone and of the node process, all best effort. */
class Probe(private val ctx: Context) {
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private val bm = ctx.getSystemService(BatteryManager::class.java)
    private val am = ctx.getSystemService(ActivityManager::class.java)

    data class Battery(val pct: Int, val charging: Boolean, val plugged: Int, val tempC: Double, val mv: Int, val currentUa: Int)

    fun battery(): Battery {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val mv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val cur = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(0)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return Battery(pct, plugged != 0, plugged, temp, mv, cur)
    }

    fun thermalStatus(): Int = pm.currentThermalStatus

    /** 1.0 means the phone is at its severe throttling point; NaN when unsupported. */
    fun thermalHeadroom(): Float = runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN)

    fun interactive(): Boolean = pm.isInteractive

    fun memory(): JSONObject {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return JSONObject()
            .put("total_mb", mi.totalMem shr 20)
            .put("avail_mb", mi.availMem shr 20)
            .put("threshold_mb", mi.threshold shr 20)
            .put("low", mi.lowMemory)
    }

    fun power(): JSONObject = JSONObject()
        .put("interactive", pm.isInteractive)
        .put("idle", pm.isDeviceIdleMode)
        .put("power_save", pm.isPowerSaveMode)
        .put("ignoring_battery_opt", pm.isIgnoringBatteryOptimizations(ctx.packageName))

    data class Proc(val cpuTicks: Long, val rssKb: Long, val hwmKb: Long, val threads: Int, val readBytes: Long, val writeBytes: Long)

    fun proc(pid: Int): Proc? = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        val f = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
        // after "pid (comm) ": state is field 3, so utime (14) and stime (15) are indexes 11 and 12
        val ticks = f[11].toLong() + f[12].toLong()
        val threads = f[17].toInt()
        var rss = 0L
        var hwm = 0L
        File("/proc/$pid/status").forEachLine { l ->
            if (l.startsWith("VmRSS:")) rss = kb(l)
            if (l.startsWith("VmHWM:")) hwm = kb(l)
        }
        var rb = -1L
        var wb = -1L
        runCatching {
            File("/proc/$pid/io").forEachLine { l ->
                if (l.startsWith("read_bytes:")) rb = l.substringAfter(':').trim().toLong()
                if (l.startsWith("write_bytes:")) wb = l.substringAfter(':').trim().toLong()
            }
        }
        Proc(ticks, rss, hwm, threads, rb, wb)
    }.getOrNull()

    private fun kb(line: String) = line.substringAfter(':').trim().split(' ')[0].toLong()

    fun cpuFreqsMhz(): JSONArray {
        val a = JSONArray()
        for (i in 0 until Runtime.getRuntime().availableProcessors()) {
            val v = runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toLong() / 1000
            }.getOrDefault(-1L)
            a.put(v)
        }
        return a
    }

    fun cpuMaxFreqsMhz(): JSONArray {
        val a = JSONArray()
        for (i in 0 until Runtime.getRuntime().availableProcessors()) {
            val v = runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000
            }.getOrDefault(-1L)
            a.put(v)
        }
        return a
    }

    /** Linux pressure stall info, where the system lets apps read it. */
    fun pressure(): JSONObject {
        val j = JSONObject()
        for (k in listOf("cpu", "memory", "io")) {
            runCatching {
                val some = File("/proc/pressure/$k").readLines().first { it.startsWith("some") }
                j.put(k, some.split(' ').first { it.startsWith("avg10=") }.substringAfter('=').toDouble())
            }
        }
        return j
    }

    fun device(): JSONObject {
        val j = JSONObject()
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("device", Build.DEVICE)
            .put("hardware", Build.HARDWARE).put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("cores", Runtime.getRuntime().availableProcessors())
            .put("cpu_max_mhz", cpuMaxFreqsMhz())
            .put("kernel", System.getProperty("os.version"))
            .put("memory", memory())
        if (Build.VERSION.SDK_INT >= 31) j.put("soc", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        return j
    }
}
