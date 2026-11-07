package com.awokenlazarus.eddy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * SwiftSync data from plain file hosting. All of it is untrusted: the node checks every coin against
 * its block and proves the whole set at the hints' end height. Hosting only has to be fast.
 *
 *  - v1/latest.json names the current hints file (height, block hash, size, SHA-256).
 *  - v1/<hash>/hints is the hints file.
 *  - v1/<hash>/undo/<block hash> is one block's undo data; 404 means the block spends nothing.
 *
 * The node never asks for a block it knows spends nothing, so a 404 here means the file isn't up
 * (yet): it is asked for again later, and peers can supply it meanwhile.
 */
class SwiftSyncFetcher(private val diag: Diagnostics) {
    companion object {
        const val BASE = "https://swiftsync.lightning-stream.com/"
        private const val PARALLEL = 48
        private const val RETRY_404_MS = 120_000L
    }

    val files = AtomicLong()
    val bytes = AtomicLong()
    val missing = AtomicLong()
    val errors = AtomicLong()

    /** Download the current hints file into [dest] if there's none yet. Returns true when [dest] exists. */
    fun ensureHints(dest: File): Boolean {
        if (dest.exists()) return true
        return try {
            val latest = JSONObject(String(get(BASE + "v1/latest.json") ?: return false))
            val want = latest.getString("hints_sha256")
            val part = File(dest.path + ".part")
            diag.event("swiftsync_hints_download", "height" to latest.optInt("height"), "bytes" to latest.optLong("hints_bytes"))
            val md = MessageDigest.getInstance("SHA-256")
            val c = URL(BASE + latest.getString("hints")).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            try {
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                c.inputStream.use { i ->
                    part.outputStream().use { o ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = i.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            o.write(buf, 0, n)
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (got != want) {
                part.delete()
                diag.event("swiftsync_hints_bad", "sha256" to got)
                return false
            }
            part.renameTo(dest)
            diag.event("swiftsync_hints_ready", "sha256" to got)
            true
        } catch (e: Exception) {
            diag.event("swiftsync_hints_failed", "error" to e.toString().take(200))
            false
        }
    }

    /**
     * Keep the node supplied with undo data ahead of its tip, until SwiftSync is done or [alive]
     * turns false. Peers serving undo over P2P still work alongside this.
     */
    fun run(scope: CoroutineScope, rpc: Rpc, alive: () -> Boolean, allowed: () -> Boolean) = scope.launch(Dispatchers.IO) {
        val gate = Semaphore(PARALLEL)
        val inflight = ConcurrentHashMap.newKeySet<String>()
        val retryAt = ConcurrentHashMap<String, Long>() // 404: not hosted (yet); ask again after this
        while (isActive && alive()) {
            val info = runCatching { rpc.obj("getswiftsyncinfo") }.getOrNull()
            if (info == null) { delay(2_000); continue }
            if (!info.optBoolean("active")) break
            val terminal = info.optString("terminal_hash")
            if (!allowed() || terminal.isEmpty()) { delay(2_000); continue }
            val wants = runCatching { rpc.arr("getswiftsyncwants", 512) }.getOrNull()
            var started = 0
            if (wants != null) for (i in 0 until wants.length()) {
                val hash = wants.getJSONObject(i).getString("hash")
                val wait = retryAt[hash]
                if (wait != null && System.currentTimeMillis() < wait) continue
                if (!inflight.add(hash)) continue
                started++
                launch {
                    gate.withPermit {
                        try {
                            val data = get(BASE + "v1/$terminal/undo/$hash")
                            if (data == null) {
                                retryAt[hash] = System.currentTimeMillis() + RETRY_404_MS
                                if (missing.incrementAndGet() % 100 == 1L) diag.event("swiftsync_fetch_missing", "hash" to hash)
                            } else {
                                retryAt.remove(hash)
                                rpc.call("submitswiftsyncundo", hash, hex(data))
                                files.incrementAndGet()
                                bytes.addAndGet(data.size.toLong())
                            }
                        } catch (e: Exception) {
                            if (errors.incrementAndGet() % 100 == 1L) diag.event("swiftsync_fetch_error", "error" to e.toString().take(200))
                        } finally {
                            inflight.remove(hash)
                        }
                    }
                }
            }
            delay(if (started == 0) 1_000 else 200)
        }
        diag.event("swiftsync_fetcher_stopped", "files" to files.get(), "bytes" to bytes.get(), "missing" to missing.get(), "errors" to errors.get())
    }

    fun stats(): JSONObject = JSONObject().put("files", files.get()).put("bytes", bytes.get())
        .put("missing", missing.get()).put("errors", errors.get())

    /** The body, or null on 404. */
    private fun get(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        try {
            return when (c.responseCode) {
                200 -> c.inputStream.use { it.readBytes() }
                404 -> null
                else -> throw IOException("HTTP ${c.responseCode} for $url")
            }
        } finally {
            c.disconnect()
        }
    }

    private val digits = "0123456789abcdef".toCharArray()
    private fun hex(b: ByteArray): String {
        val out = CharArray(b.size * 2)
        for (i in b.indices) {
            val v = b[i].toInt() and 0xff
            out[2 * i] = digits[v ushr 4]
            out[2 * i + 1] = digits[v and 0x0f]
        }
        return String(out)
    }
}
