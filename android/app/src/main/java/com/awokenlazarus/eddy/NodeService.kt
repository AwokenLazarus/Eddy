package com.awokenlazarus.eddy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.ArrayDeque

/**
 * Runs the node (Bitcoin Knots, unmodified) as a child process and keeps it within the rules:
 * the first sync only on Wi-Fi, a pause when the phone is hot or the battery low, low CPU priority.
 */
class NodeService : Service() {

    companion object {
        const val ACTION_START = "com.awokenlazarus.eddy.START"
        const val ACTION_STOP = "com.awokenlazarus.eddy.STOP"
        private const val CHANNEL = "node"
        private const val NOTE_ID = 1
        private const val POLL_MS = 10_000L
        private const val SAMPLE_MS = 60_000L

        val state = MutableStateFlow(NodeState())

        /** Wakes the control loop at once (settings or conditions changed). */
        val kicks = Channel<Unit>(Channel.CONFLATED)

        fun start(ctx: Context) {
            Prefs(ctx).wantRunning = true
            ctx.startForegroundService(Intent(ctx, NodeService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            Prefs(ctx).wantRunning = false
            ctx.startForegroundService(Intent(ctx, NodeService::class.java).setAction(ACTION_STOP))
        }

        fun dataDir(ctx: Context) = File(ctx.noBackupFilesDir, "bitcoin").apply { mkdirs() }
    }

    private enum class Exit { Crashed, ModeChange, Stopped }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private lateinit var diag: Diagnostics
    private lateinit var probe: Probe
    private lateinit var dataDir: File
    private lateinit var rpc: Rpc
    private lateinit var fetcher: SwiftSyncFetcher
    private var loop: Job? = null
    @Volatile private var proc: java.lang.Process? = null
    @Volatile private var stopping = false
    /** Bytes this app had received when the service started, so "downloaded" means this session. */
    private val rxAtStart = TrafficStats.getUidRxBytes(android.os.Process.myUid())

    @Volatile private var wifiOk = false
    @Volatile private var netCaps: NetworkCapabilities? = null
    @Volatile private var hot = false
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var cm: ConnectivityManager

    // Main-thread lag: how late a 200 ms timer fires. It shows when the phone is bogged down.
    private val main = Handler(Looper.getMainLooper())
    private val lags = ArrayList<Long>()
    private var lagDue = 0L
    private val lagTick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (lagDue > 0) synchronized(lags) { lags.add(now - lagDue) }
            lagDue = now + 200
            main.postDelayed(this, 200)
        }
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            netCaps = caps
            val ok = (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            if (ok != wifiOk) {
                wifiOk = ok
                diag.event("network", "wifi" to ok, "transports" to transports(caps))
                kicks.trySend(Unit)
            }
        }

        override fun onLost(network: Network) {
            netCaps = null
            if (wifiOk) {
                wifiOk = false
                diag.event("network", "wifi" to false, "transports" to "none")
            }
            kicks.trySend(Unit)
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            diag.event("power", "action" to i.action?.substringAfterLast('.'))
            if (i.action == Intent.ACTION_SHUTDOWN) {
                // The phone is turning off: ask the node to write its cache now. Android gives only
                // seconds, so a big cache may not finish, but every flushed block is kept.
                proc?.let { if (it.isAlive) runCatching { scope.launch { runCatching { rpc.call("stop") } } } }
                return
            }
            kicks.trySend(Unit)
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { s ->
        diag.event("thermal", "status" to s, "headroom" to probe.thermalHeadroom().toDouble().takeIf { !it.isNaN() })
        kicks.trySend(Unit)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        diag = Diagnostics(this)
        probe = Probe(this)
        dataDir = dataDir(this)
        rpc = Rpc(dataDir, NodeConfig.RPC_PORT)
        fetcher = SwiftSyncFetcher(diag)
        cm = getSystemService(ConnectivityManager::class.java)
        createChannel()
        cm.registerDefaultNetworkCallback(netCallback)
        registerReceiver(powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_SHUTDOWN)
        })
        getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor, thermalListener)
        main.post(lagTick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTE_ID, notification("Starting", ""),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        if (intent?.action == ACTION_STOP) {
            if (!stopping) {
                stopping = true
                scope.launch {
                    shutdownNode("user")
                    loop?.join()
                    withContext(Dispatchers.Main) {
                        ServiceCompat.stopForeground(this@NodeService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        } else if (loop == null && prefs.wantRunning) {
            stopping = false
            loop = scope.launch { supervise() }
        } else if (loop == null) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(lagTick)
        runCatching { cm.unregisterNetworkCallback(netCallback) }
        runCatching { unregisterReceiver(powerReceiver) }
        runCatching { getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener) }
        holdLocks(false)
        // If Android tears the service down without a stop request, ask the node to stop cleanly.
        proc?.let { p -> if (p.isAlive) runCatching { rpc.call("stop") } }
        diag.event("service_destroyed", "user_stop" to stopping)
        scope.cancel()
        state.update { it.copy(phase = Phase.Stopped, netActive = false) }
        super.onDestroy()
    }

    // ---- the node process ------------------------------------------------------------------

    private suspend fun supervise() {
        diag.writeJson("device.json", probe.device().put("app_version", BuildConfigVersion.name(this)))
        var backoff = 5_000L
        var restarts = 0
        while (!stopping) {
            val plan = NodeConfig.plan(this, prefs.synced)
            val hints = swiftsyncHints(plan.synced)
            val conf = NodeConfig.write(this, dataDir, plan, prefs.benchLogs, diag.nodeLog, hints)
            val marker = File(dataDir, "eddy.running")
            if (marker.exists()) diag.event("unclean_previous_exit", "since" to marker.readText())
            marker.writeText(Diagnostics.iso())
            diag.event(
                "node_start", "dbcache_mb" to plan.dbcacheMb, "par" to plan.par, "synced" to plan.synced,
                "nice" to NodeConfig.NICE, "ram_mb" to plan.totalRamMb, "cores" to plan.cores,
                "bench_logs" to prefs.benchLogs
            )
            state.update { it.copy(phase = Phase.Starting, detail = "Starting the node", dbcacheMb = plan.dbcacheMb, restarts = restarts) }

            val started = SystemClock.elapsedRealtime()
            stopOrphan()
            val p = try {
                launchNode(conf)
            } catch (e: IOException) {
                diag.event("node_launch_failed", "error" to e.toString())
                state.update { it.copy(phase = Phase.Error, detail = "Could not start the node: ${e.message}") }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(300_000)
                continue
            }
            proc = p
            val exit = runCatching { monitor(p, plan) }.getOrElse {
                diag.event("monitor_error", "error" to it.toString())
                Exit.Crashed
            }
            if (exit == Exit.ModeChange) shutdownNode("mode_change")
            val code = waitExit(p, 15 * 60_000L)
            proc = null
            val ranS = (SystemClock.elapsedRealtime() - started) / 1000
            if (code == 0) marker.delete()
            diag.event("node_exit", "code" to code, "ran_s" to ranS, "reason" to exit.name,
                "log_tail" to if (code != 0) Diagnostics.tailLines(diag.nodeLog, 40) else null)
            if (stopping) break
            if (exit == Exit.ModeChange) { backoff = 5_000; continue }
            restarts++
            if (ranS > 600) backoff = 5_000
            state.update { it.copy(phase = Phase.Error, detail = "The node stopped (code $code); restarting", restarts = restarts) }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(300_000)
        }
        holdLocks(false)
        state.update { it.copy(phase = Phase.Stopped, detail = "", netActive = false) }
    }

    /**
     * The SwiftSync hints file, if one is in Eddy's files folder and the first sync isn't done.
     * A SwiftSync must start from genesis, so the first time hints appear the chain data is reset.
     */
    private fun swiftsyncHints(synced: Boolean): File? {
        val f = File(getExternalFilesDir(null), "swiftsync.hints")
        if (synced) return null
        // Fetch the current hints from file hosting on the first start, on Wi-Fi only.
        if (!f.exists() && !prefs.swiftsyncStarted && wifiOk) fetcher.ensureHints(f)
        if (!f.exists()) return null
        if (!prefs.swiftsyncStarted) {
            // Start the records fresh too, so the SwiftSync run's milestones and timings stand alone;
            // the earlier run's files move to diag/before-swiftsync-<time>/.
            val archive = File(diag.root, "before-swiftsync-${Diagnostics.stamp()}").apply { mkdirs() }
            diag.root.listFiles()?.filter { it != archive && !it.name.startsWith("before-swiftsync") }
                ?.forEach { it.renameTo(File(archive, it.name)) }
            File(diag.root, "node-logs").mkdirs()
            diag.event("swiftsync_reset", "hints_bytes" to f.length(), "archive" to archive.name)
            for (d in listOf("blocks", "chainstate", "swiftsync", "indexes")) File(dataDir, d).deleteRecursively()
            prefs.swiftsyncStarted = true
        }
        return f
    }

    /** A node still running from an earlier app process holds the data directory: stop it first. */
    private fun stopOrphan() {
        val pid = pid() ?: return
        val cmd = runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull() ?: return
        if (!cmd.contains("libbitcoind")) return
        diag.event("orphan_node", "pid" to pid)
        state.update { it.copy(phase = Phase.Stopping, detail = "Stopping the node left from before") }
        if (runCatching { rpc.call("stop") }.isFailure) android.os.Process.sendSignal(pid, 15)
        val until = SystemClock.elapsedRealtime() + 15 * 60_000L
        while (File("/proc/$pid").exists() && SystemClock.elapsedRealtime() < until) Thread.sleep(500)
        if (File("/proc/$pid").exists()) {
            diag.event("orphan_kill", "pid" to pid)
            android.os.Process.sendSignal(pid, 9)
            Thread.sleep(2_000)
        }
    }

    private fun launchNode(conf: File): java.lang.Process {
        val bin = File(applicationInfo.nativeLibraryDir, "libbitcoind.so")
        if (!bin.exists()) throw IOException("node binary missing for this phone's CPU")
        val cmd = mutableListOf<String>()
        // Lower CPU and I/O priority than the apps in use, where the tools exist.
        if (File("/system/bin/ionice").exists()) cmd += listOf("/system/bin/ionice", "-c", "2", "-n", "7")
        if (File("/system/bin/nice").exists()) cmd += listOf("/system/bin/nice", "-n", NodeConfig.NICE.toString())
        cmd += listOf(bin.path, "-datadir=${dataDir.path}", "-conf=${conf.path}")
        val out = File(diag.root, "node-stdout.log")
        if (out.length() > (8L shl 20)) out.delete()
        val pb = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(out))
        pb.environment()["TMPDIR"] = cacheDir.path
        pb.environment()["HOME"] = filesDir.path
        return pb.start()
    }

    private fun waitExit(p: java.lang.Process, maxMs: Long): Int {
        val until = SystemClock.elapsedRealtime() + maxMs
        while (p.isAlive && SystemClock.elapsedRealtime() < until) Thread.sleep(500)
        if (p.isAlive) {
            diag.event("node_kill", "after_ms" to maxMs)
            p.destroyForcibly()
            p.waitFor()
        }
        return p.exitValue()
    }

    private fun shutdownNode(reason: String) {
        val p = proc ?: return
        state.update { it.copy(phase = Phase.Stopping, detail = "Saving progress (this can take a few minutes)") }
        diag.event("node_stop_requested", "reason" to reason)
        val ok = runCatching { rpc.call("stop") }.isSuccess
        if (!ok) p.destroy() // SIGTERM: the node shuts down cleanly on it too
    }

    // ---- control loop ----------------------------------------------------------------------

    private suspend fun monitor(p: java.lang.Process, plan: NodeConfig.Plan): Exit {
        var netActive: Boolean? = null
        var lastRpcError: String? = null
        var lastSample = 0L
        var lastNote = 0L
        var lastDisk = 0L
        var diskBytes = 0L
        var lastCpu: Pair<Long, Long>? = null
        var cpuPct = 0.0
        var activeSec = 0L
        var lastTick = SystemClock.elapsedRealtime()
        val window = ArrayDeque<Triple<Long, Double, Long>>() // (elapsed ms, progress, height)
        val summary = diag.readJson("summary.json")
        val milestones = summary.optJSONObject("milestones") ?: JSONObject()
        if (!summary.has("first_start")) summary.put("first_start", Diagnostics.iso())
        activeSec = summary.optLong("active_sync_s", 0)

        // SwiftSync undo data from file hosting, alongside any peer that serves it over P2P.
        val fetchJob = if (!plan.synced) fetcher.run(scope, rpc, { p.isAlive && !stopping }, { netActive == true }) else null
        try {
        while (p.isAlive && !stopping) {
            val now = SystemClock.elapsedRealtime()
            val info = try {
                rpc.obj("getblockchaininfo")
            } catch (e: RpcError) {
                if (e.code == -28) state.update { it.copy(phase = Phase.Starting, detail = e.message ?: "Loading") }
                null
            } catch (e: Exception) {
                val msg = e.toString().take(200)
                if (msg != lastRpcError) { lastRpcError = msg; diag.event("rpc_error", "error" to msg) }
                null
            }

            val battery = probe.battery()
            val thermal = probe.thermalStatus()
            val headroom = probe.thermalHeadroom()
            // Android already slows the CPU as the phone warms, which keeps the sync going at a safe pace;
            // pausing on its headroom forecast (0.1.2) stopped the node every few minutes at 33 C.
            // Pause only when Android itself reports severe throttling, or the battery gets warm
            // enough to matter while charging.
            hot = if (hot) !(thermal <= PowerManager.THERMAL_STATUS_MODERATE && battery.tempC < 39.0)
            else thermal >= PowerManager.THERMAL_STATUS_SEVERE || battery.tempC >= 42.0

            if (info != null) {
                val height = info.getLong("blocks")
                val headers = info.getLong("headers")
                val progress = info.getDouble("verificationprogress")
                val ibd = info.getBoolean("initialblockdownload")
                val reason = pauseReason(battery, ibd)
                val want = reason == null
                if (want != netActive) {
                    if (runCatching { rpc.call("setnetworkactive", want) }.isSuccess) {
                        netActive = want
                        diag.event(if (want) "network_on" else "network_off", "reason" to reason, "height" to height)
                        window.clear()
                    }
                }
                holdLocks(want && (ibd || battery.charging))

                if (netActive == true && ibd) activeSec += (now - lastTick) / 1000
                window.addLast(Triple(now, progress, height))
                // Rates over the last 10 minutes; no estimate until there are 10 minutes of steady data,
                // so a restart or a pause doesn't produce nonsense like "108 days left".
                while (window.size > 2 && now - window.first.first > 10 * 60_000L) window.removeFirst()
                val first = window.first
                val dtH = (now - first.first) / 3_600_000.0
                val bph = if (dtH > 0.03) (height - first.third) / dtH else 0.0
                val pph = if (dtH > 0.15) (progress - first.second) / dtH else 0.0
                val eta = if (pph > 0 && netActive == true) ((1 - progress) / pph * 3600).toLong() else null

                if (now - lastDisk > 10 * 60_000L) { diskBytes = dirSize(dataDir); lastDisk = now }
                val pid = pid()
                val pr = pid?.let { probe.proc(it) }
                if (pr != null) {
                    lastCpu?.let { (t0, w0) ->
                        val dw = now - w0
                        if (dw > 0) cpuPct = (pr.cpuTicks - t0) * 10.0 * 100 / dw // 100 ticks a second
                    }
                    lastCpu = pr.cpuTicks to now
                }
                val cache = cacheFromLog()
                val synced = !ibd
                val phase = when {
                    reason != null -> Phase.Paused
                    synced -> Phase.Synced
                    else -> Phase.Syncing
                }
                val lagNow = lagStats()
                val peers = runCatching { (rpc.call("getconnectioncount") as Number).toInt() }.getOrDefault(0)
                val swift = runCatching { rpc.obj("getswiftsyncinfo") }.getOrNull()
                state.update {
                    it.copy(
                        peers = peers,
                        phase = phase, detail = pauseText(reason), height = height, headers = headers,
                        progress = progress, tipTime = info.optLong("time"), ibd = ibd,
                        netActive = netActive == true, pauseReason = reason, blocksPerHour = bph, etaSeconds = eta,
                        diskBytes = diskBytes, recvBytes = TrafficStats.getUidRxBytes(applicationInfo.uid) - rxAtStart,
                        nodeRssBytes = (pr?.rssKb ?: 0) * 1024, nodeCpuPct = cpuPct, cacheMb = cache ?: it.cacheMb,
                        dbcacheMb = plan.dbcacheMb, batteryPct = battery.pct, charging = battery.charging,
                        batteryTempC = battery.tempC, thermal = thermal, wifi = wifiOk, activeSyncSec = activeSec,
                        lagP95Ms = lagNow.second,
                        swiftTerminal = swift?.optInt("terminal_height") ?: 0,
                        swiftActive = swift?.optBoolean("active") ?: false,
                    )
                }

                // Milestones every 10,000 blocks: wall-clock since the first start and active sync time.
                val step = (height / 10_000) * 10_000
                if (step > 0 && !milestones.has(step.toString())) {
                    milestones.put(step.toString(), JSONObject().put("t", Diagnostics.iso()).put("active_sync_s", activeSec))
                }

                if (now - lastSample >= SAMPLE_MS) {
                    lastSample = now
                    diag.sample(sample(info, pid, pr, cpuPct, battery, thermal, headroom, reason, netActive == true, bph, eta, plan, cache, diskBytes, lagNow))
                    summary.put("milestones", milestones).put("active_sync_s", activeSec)
                        .put("last_height", height).put("last_sample", Diagnostics.iso())
                    diag.writeJson("summary.json", summary)
                    runCatching { diag.rotateNodeLog(pid) }.onFailure { diag.event("log_rotate_failed", "error" to it.toString()) }
                }
                if (now - lastNote >= 30_000L) { lastNote = now; updateNotification() }

                if (!plan.synced && synced && height > 0) {
                    prefs.synced = true
                    summary.put("ibd_complete", Diagnostics.iso()).put("ibd_height", height).put("active_sync_s", activeSec)
                    diag.writeJson("summary.json", summary)
                    diag.event("ibd_complete", "height" to height, "active_sync_s" to activeSec)
                    return Exit.ModeChange // restart with the small cache and transaction relay
                }
            }
            lastTick = now
            withTimeoutOrNull(POLL_MS) { kicks.receive() }
        }
        } finally {
            fetchJob?.cancel()
        }
        return if (stopping) Exit.Stopped else Exit.Crashed
    }

    private fun pauseReason(b: Probe.Battery, ibd: Boolean): String? = when {
        // Wi-Fi only for the first sync; once synced, mobile data is fine too.
        ibd && !wifiOk -> "no_wifi"
        prefs.chargingOnly && !b.charging -> "not_charging"
        prefs.pauseHot && hot -> "hot"
        !b.charging && b.pct in 0..14 -> "low_battery"
        prefs.pauseInUse && ibd && probe.interactive() -> "in_use"
        else -> null
    }

    private fun sample(
        info: JSONObject, pid: Int?, pr: Probe.Proc?, cpuPct: Double, b: Probe.Battery, thermal: Int,
        headroom: Float, reason: String?, netActive: Boolean, bph: Double, eta: Long?, plan: NodeConfig.Plan,
        cache: Double?, diskBytes: Long, lag: Triple<Long, Long, Long>,
    ): JSONObject {
        val j = JSONObject()
        j.put("height", info.getLong("blocks")).put("headers", info.getLong("headers"))
            .put("progress", info.getDouble("verificationprogress")).put("ibd", info.getBoolean("initialblockdownload"))
            .put("tip_time", info.optLong("time")).put("size_on_disk", info.optLong("size_on_disk"))
            .put("net_active", netActive).put("pause", reason ?: JSONObject.NULL)
            .put("blocks_per_h", bph.toInt()).put("eta_s", eta ?: JSONObject.NULL)
            .put("dbcache_mb", plan.dbcacheMb).put("cache_mb", cache ?: JSONObject.NULL).put("data_bytes", diskBytes)
        runCatching {
            val t = rpc.obj("getnettotals")
            j.put("net_recv", t.getLong("totalbytesrecv")).put("net_sent", t.getLong("totalbytessent"))
        }
        runCatching { j.put("peers", peerSummary(rpc.arr("getpeerinfo"))) }
        j.put("bench_s", benchFromLog())
        runCatching { j.put("swiftsync", rpc.obj("getswiftsyncinfo")) }
        j.put("swiftsync_cdn", fetcher.stats())
        j.put("app_rx", TrafficStats.getUidRxBytes(applicationInfo.uid)).put("app_tx", TrafficStats.getUidTxBytes(applicationInfo.uid))
        if (pr != null) {
            j.put("node", JSONObject().put("pid", pid).put("cpu_pct", cpuPct.toInt()).put("cpu_ticks", pr.cpuTicks)
                .put("rss_mb", pr.rssKb / 1024).put("hwm_mb", pr.hwmKb / 1024).put("threads", pr.threads)
                .put("read_bytes", pr.readBytes).put("write_bytes", pr.writeBytes))
        }
        j.put("battery", JSONObject().put("pct", b.pct).put("charging", b.charging).put("plugged", b.plugged)
            .put("temp_c", b.tempC).put("mv", b.mv).put("current_ua", b.currentUa))
        j.put("thermal", JSONObject().put("status", thermal).put("headroom", if (headroom.isNaN()) JSONObject.NULL else headroom.toDouble()).put("hot", hot))
        j.put("cpu_mhz", probe.cpuFreqsMhz()).put("memory", probe.memory()).put("power", probe.power()).put("pressure", probe.pressure())
        j.put("lag_ms", JSONObject().put("p50", lag.first).put("p95", lag.second).put("max", lag.third))
        j.put("network", network())
        j.put("app_rss_mb", Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) shr 20 })
        return j
    }

    private fun peerSummary(peers: JSONArray): JSONObject {
        val types = JSONObject()
        val versions = JSONObject()
        val pings = ArrayList<Double>()
        var maxSynced = 0L
        var recv = 0L
        var inflight = 0
        val manual = JSONArray()
        for (i in 0 until peers.length()) {
            val p = peers.getJSONObject(i)
            val t = p.optString("connection_type")
            types.put(t, types.optInt(t) + 1)
            val v = p.optString("subver")
            versions.put(v, versions.optInt(v) + 1)
            if (p.has("pingtime")) pings.add(p.getDouble("pingtime") * 1000)
            maxSynced = maxOf(maxSynced, p.optLong("synced_blocks"))
            recv += p.optLong("bytesrecv")
            inflight += p.optJSONArray("inflight")?.length() ?: 0
            // Bootstrap peers from Eddy's own list (public nodes): how much each one serves.
            if (t == "manual") manual.put(JSONObject().put("addr", p.optString("addr"))
                .put("bytes_recv", p.optLong("bytesrecv")).put("synced_blocks", p.optLong("synced_blocks"))
                .put("ping_ms", (p.optDouble("pingtime", -0.001) * 1000).toInt()))
        }
        pings.sort()
        return JSONObject().put("count", peers.length()).put("types", types).put("versions", versions)
            .put("ping_ms_median", if (pings.isEmpty()) JSONObject.NULL else pings[pings.size / 2].toInt())
            .put("max_synced_blocks", maxSynced).put("bytes_recv", recv)
            .put("blocks_in_flight", inflight).put("manual", manual)
    }

    private fun network(): JSONObject {
        val c = netCaps ?: return JSONObject().put("transports", "none")
        val j = JSONObject().put("transports", transports(c)).put("wifi_ok", wifiOk)
            .put("down_kbps", c.linkDownstreamBandwidthKbps).put("up_kbps", c.linkUpstreamBandwidthKbps)
        (c.transportInfo as? WifiInfo)?.let { w ->
            j.put("rssi", w.rssi).put("link_mbps", w.linkSpeed).put("freq_mhz", w.frequency)
        }
        return j
    }

    private fun transports(c: NetworkCapabilities): String = buildList {
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("metered")
    }.joinToString("+")

    /** p50, p95 and max of the main-thread lag since the last call. */
    private fun lagStats(): Triple<Long, Long, Long> {
        val l = synchronized(lags) { ArrayList(lags).also { lags.clear() } }
        if (l.isEmpty()) return Triple(0, 0, 0)
        l.sort()
        return Triple(l[l.size / 2], l[(l.size * 95 / 100).coerceAtMost(l.size - 1)], l.last())
    }

    private fun pid(): Int? = runCatching { File(dataDir, "bitcoind.pid").readText().trim().toInt() }.getOrNull()

    private val cacheRe = Regex("""cache=([\d.]+)MiB""")

    /** The UTXO cache size from the node's latest UpdateTip line. */
    private fun cacheFromLog(): Double? = runCatching {
        val t = String(Diagnostics.tail(diag.nodeLog, 256 shl 10))
        cacheRe.findAll(t).lastOrNull()?.groupValues?.get(1)?.toDouble()
    }.getOrNull()

    // "[bench]   - Verify 2345 txins: 1.20ms (0.001ms/txin) [83.21s (0.85ms/blk)]": the bracket is the
    // total since the node started, so the latest line of each step says where the time has gone.
    private val benchRe = Regex("""\[bench\]\s+-\s+([^:]+):\s+[\d.]+ms[^\[\n]*\[([\d.]+)s""")

    /** Seconds spent in each block-connecting step since the node started (needs -debug=bench). */
    private fun benchFromLog(): JSONObject = runCatching {
        val t = String(Diagnostics.tail(diag.nodeLog, 256 shl 10))
        val j = JSONObject()
        for (m in benchRe.findAll(t)) {
            val name = m.groupValues[1].replace(Regex("""\d+ """), "").trim().lowercase().replace(' ', '_')
            j.put(name, m.groupValues[2].toDouble())
        }
        j
    }.getOrDefault(JSONObject())

    private fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun holdLocks(on: Boolean) {
        if (on) {
            if (wake == null) {
                wake = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eddy:sync").apply { setReferenceCounted(false); acquire() }
                @Suppress("DEPRECATION")
                wifiLock = getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "eddy:sync").apply { setReferenceCounted(false); acquire() }
                diag.event("locks", "held" to true)
            }
        } else if (wake != null) {
            runCatching { wake?.release() }
            runCatching { wifiLock?.release() }
            wake = null
            wifiLock = null
            diag.event("locks", "held" to false)
        }
    }

    // ---- notification ----------------------------------------------------------------------

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Node", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        val s = state.value
        val title = when (s.phase) {
            Phase.Syncing -> "Validating · %.2f%%".format(s.progress * 100)
            Phase.Synced -> "Synced · block %,d".format(s.height)
            Phase.Paused -> "Paused · %.2f%%".format(s.progress * 100)
            else -> s.phase.name
        }
        val text = when (s.phase) {
            Phase.Paused -> s.detail
            Phase.Syncing -> "Block %,d of %,d".format(s.height, s.headers)
            else -> s.detail
        }
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, notification(title, text))
    }
}

object BuildConfigVersion {
    fun name(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" }.getOrDefault("")
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Prefs(c).wantRunning) {
            Diagnostics(c).event("auto_start", "action" to i.action?.substringAfterLast('.'))
            c.startForegroundService(Intent(c, NodeService::class.java).setAction(NodeService.ACTION_START))
        }
    }
}
