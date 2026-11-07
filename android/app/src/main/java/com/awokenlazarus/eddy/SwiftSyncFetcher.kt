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
 *  - v1/<hash>/packs.json lists undo packs: v1/<hash>/pack/<first>-<last>.gz, each the undo data
 *    of every block in that height range that spends something. Packs are streamed ahead of the
 *    tip, unzipped next to the node's data and handed over with loadswiftsyncundopack; the node
 *    reads each block's entry from the file when the block connects. Single files only fill gaps.
 *  - v1/<hash>/blocks.json lists block packs: v1/<hash>/block/<first>-<last>.gz, every block in that
 *    height range. They are streamed ahead of the tip and handed over with loadblockpack, which
 *    stores the blocks as if a peer had sent them; each is fully validated when it connects. Peers
 *    still fill whatever the packs don't bring.
 *
 * The node never asks for a block it knows spends nothing, so a 404 here means the file isn't up
 * (yet): it is asked for again later, and peers can supply it meanwhile.
 */
class SwiftSyncFetcher(private val diag: Diagnostics) {
    companion object {
        const val BASE = "https://swiftsync.lightning-stream.com/"
        private const val PARALLEL = 48
        private const val RETRY_404_MS = 120_000L
        /** Unzipped pack data kept on disk ahead of the tip. */
        private const val PACK_AHEAD_BYTES = 768L shl 20
        private const val PACK_PARALLEL = 4
        /** Block packs: blocks stored ahead of the tip (they sit in the node's own block files until connected). */
        private const val BLOCK_AHEAD_BYTES = 1536L shl 20
        private const val BLOCK_PARALLEL = 12
        /** With packs, single files are fetched only this close to the tip (gaps, or a pack still downloading). */
        private const val SINGLES_NEAR_TIP = 16
        /** Blocks a pack stays on disk after the tip passes it. */
        private const val KEEP_BEHIND = 2_000
    }

    val files = AtomicLong()
    val bytes = AtomicLong()
    val missing = AtomicLong()
    val errors = AtomicLong()
    val packs = AtomicLong()
    val packBytes = AtomicLong()
    val packErrors = AtomicLong()
    val blockPacks = AtomicLong()
    val blockPackBytes = AtomicLong()
    val blockPackErrors = AtomicLong()

    private class Pack(val first: Int, val last: Int, val bytes: Long, val gzBytes: Long, val sha256: String) {
        val name get() = "$first-$last"
    }

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
    fun run(scope: CoroutineScope, rpc: Rpc, packDir: File, alive: () -> Boolean, allowed: () -> Boolean) = scope.launch(Dispatchers.IO) {
        val gate = Semaphore(PARALLEL)
        val inflight = ConcurrentHashMap.newKeySet<String>()
        val retryAt = ConcurrentHashMap<String, Long>() // 404: not hosted (yet); ask again after this
        var packList: List<Pack>? = null
        var packListTried = 0L
        var packJob: kotlinx.coroutines.Job? = null
        var blockList: List<Pack>? = null
        var blockListTried = 0L
        var blockJob: kotlinx.coroutines.Job? = null
        while (isActive && alive()) {
            val info = runCatching { rpc.obj("getswiftsyncinfo") }.getOrNull()
            if (info == null) { delay(2_000); continue }
            if (!info.optBoolean("active")) break
            val terminal = info.optString("terminal_hash")
            if (!allowed() || terminal.isEmpty()) { delay(2_000); continue }
            if (packList == null && System.currentTimeMillis() - packListTried > 600_000) {
                packListTried = System.currentTimeMillis()
                packList = runCatching { packIndex(terminal) }.getOrNull()
                if (packList != null) {
                    diag.event("swiftsync_packs", "packs" to packList!!.size)
                    packJob = runPacks(this, rpc, terminal, packList!!, packDir, alive, allowed)
                }
            }
            if (blockList == null && System.currentTimeMillis() - blockListTried > 600_000) {
                blockListTried = System.currentTimeMillis()
                blockList = runCatching { packIndex(terminal, "blocks.json") }.getOrNull()
                if (blockList != null) {
                    diag.event("swiftsync_block_packs", "packs" to blockList!!.size)
                    blockJob = runBlockPacks(this, rpc, terminal, blockList!!, File(packDir.parentFile, "blockpacks"), alive, allowed)
                }
            }
            val tip = if (packList != null) runCatching { (rpc.call("getblockcount") as Number).toInt() }.getOrNull() else null
            val wants = runCatching { rpc.arr("getswiftsyncwants", 512) }.getOrNull()
            var started = 0
            if (wants != null) for (i in 0 until wants.length()) {
                val want = wants.getJSONObject(i)
                if (packList != null && (tip == null || want.optInt("height") > tip + SINGLES_NEAR_TIP)) break
                val hash = want.getString("hash")
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
        packJob?.cancel()
        blockJob?.cancel()
        diag.event("swiftsync_fetcher_stopped", "files" to files.get(), "bytes" to bytes.get(), "missing" to missing.get(),
            "errors" to errors.get(), "packs" to packs.get(), "pack_bytes" to packBytes.get(), "pack_errors" to packErrors.get(),
            "block_packs" to blockPacks.get(), "block_pack_bytes" to blockPackBytes.get(), "block_pack_errors" to blockPackErrors.get())
    }

    fun stats(): JSONObject = JSONObject().put("files", files.get()).put("bytes", bytes.get())
        .put("missing", missing.get()).put("errors", errors.get())
        .put("packs", packs.get()).put("pack_bytes", packBytes.get()).put("pack_errors", packErrors.get())
        .put("block_packs", blockPacks.get()).put("block_pack_bytes", blockPackBytes.get()).put("block_pack_errors", blockPackErrors.get())

    /** The pack list for [terminal], or null when file hosting has none (then single files only). */
    private fun packIndex(terminal: String, file: String = "packs.json"): List<Pack>? {
        // The query string keeps a 404 cached at the edge before the list existed from hiding it.
        val body = get(BASE + "v1/$terminal/$file?t=${System.currentTimeMillis() / 600_000}") ?: return null
        val j = JSONObject(String(body))
        if (j.optInt("version") != 1) return null
        val a = j.getJSONArray("packs")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Pack(o.getInt("first"), o.getInt("last"), o.getLong("bytes"), o.getLong("gz_bytes"), o.getString("sha256"))
        }
    }

    /**
     * Keep up to [PACK_AHEAD_BYTES] of unzipped packs on disk ahead of the tip, loaded into the node.
     * A pack is loaded only once headers reach its last block (the node indexes only known blocks),
     * and deleted once the tip passes it.
     */
    private fun runPacks(scope: CoroutineScope, rpc: Rpc, terminal: String, list: List<Pack>, dir: File,
                         alive: () -> Boolean, allowed: () -> Boolean) = scope.launch(Dispatchers.IO) {
        dir.mkdirs()
        dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        val loaded = ConcurrentHashMap.newKeySet<String>()
        val busy = ConcurrentHashMap.newKeySet<String>()
        val failedAt = ConcurrentHashMap<String, Long>()
        val gate = Semaphore(PACK_PARALLEL)
        val byName = list.associateBy { it.name }
        while (isActive && alive()) {
            val chain = runCatching { rpc.obj("getblockchaininfo") }.getOrNull()
            if (chain == null) { delay(2_000); continue }
            val tip = chain.optInt("blocks")
            val headers = chain.optInt("headers")
            // Drop packs the tip has passed; (re)load those on disk the node doesn't know yet (after a restart).
            for (f in dir.listFiles().orEmpty()) {
                val p = byName[f.name] ?: continue
                // Kept a while past the tip: an unclean stop can roll the tip back by a few thousand blocks.
                if (p.last + KEEP_BEHIND <= tip) { f.delete(); loaded.remove(p.name); continue }
                if (p.name !in loaded && headers >= p.last && load(rpc, f)) loaded.add(p.name)
            }
            if (!allowed()) { delay(2_000); continue }
            var ahead = list.filter { it.last > tip && (it.name in loaded || it.name in busy) }.sumOf { it.bytes }
            for (p in list) {
                if (p.last <= tip || p.name in loaded || p.name in busy) continue
                if (File(dir, p.name).exists()) continue // waiting for headers
                // The pack the tip needs next always comes, whatever is already held further ahead.
                val next = p.first <= tip + 1
                if (!next && ahead + p.bytes > PACK_AHEAD_BYTES && ahead > 0) break
                if ((failedAt[p.name] ?: 0L) > System.currentTimeMillis() - 30_000) continue
                if (busy.size >= PACK_PARALLEL) break
                busy.add(p.name)
                ahead += p.bytes
                launch {
                    gate.withPermit {
                        try {
                            val f = download(terminal, p, dir)
                            packs.incrementAndGet()
                            packBytes.addAndGet(p.gzBytes)
                            val h = runCatching { rpc.obj("getblockchaininfo").optInt("headers") }.getOrDefault(0)
                            if (h >= p.last && load(rpc, f)) loaded.add(p.name)
                        } catch (e: Exception) {
                            failedAt[p.name] = System.currentTimeMillis()
                            if (packErrors.incrementAndGet() % 20 == 1L) diag.event("swiftsync_pack_error", "pack" to p.name, "error" to e.toString().take(200))
                        } finally {
                            busy.remove(p.name)
                        }
                    }
                }
            }
            delay(1_000)
        }
    }

    private fun load(rpc: Rpc, f: File): Boolean = try {
        rpc.obj("loadswiftsyncundopack", f.absolutePath)
        true
    } catch (e: RpcError) {
        // Not a usable pack: remove it so it's fetched again.
        diag.event("swiftsync_pack_refused", "pack" to f.name, "error" to (e.message ?: "").take(200))
        f.delete()
        false
    } catch (e: Exception) {
        false
    }

    /**
     * Keep up to [BLOCK_AHEAD_BYTES] of blocks from block packs stored ahead of the tip. Each pack is
     * streamed, checked, handed to the node (loadblockpack) once headers reach its last block, and
     * deleted: the node keeps the blocks in its own files until they connect.
     */
    private fun runBlockPacks(scope: CoroutineScope, rpc: Rpc, terminal: String, list: List<Pack>, dir: File,
                              alive: () -> Boolean, allowed: () -> Boolean) = scope.launch(Dispatchers.IO) {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val done = ConcurrentHashMap.newKeySet<String>()
        val busy = ConcurrentHashMap.newKeySet<String>()
        val failedAt = ConcurrentHashMap<String, Long>()
        while (isActive && alive()) {
            val chain = runCatching { rpc.obj("getblockchaininfo") }.getOrNull()
            if (chain == null || !allowed()) { delay(2_000); continue }
            val tip = chain.optInt("blocks")
            val headers = chain.optInt("headers")
            var ahead = list.filter { it.last > tip && (it.name in done || it.name in busy) }.sumOf { it.bytes }
            for (p in list) {
                if (p.last <= tip || p.name in done || p.name in busy) continue
                if (p.last > headers) break
                val next = p.first <= tip + 1
                if (!next && ahead + p.bytes > BLOCK_AHEAD_BYTES && ahead > 0) break
                if ((failedAt[p.name] ?: 0L) > System.currentTimeMillis() - 30_000) continue
                if (busy.size >= BLOCK_PARALLEL) break
                busy.add(p.name)
                ahead += p.bytes
                launch {
                    try {
                        val f = download(terminal, p, dir, "block")
                        blockPacks.incrementAndGet()
                        blockPackBytes.addAndGet(p.gzBytes)
                        try {
                            rpc.callTimeout(300_000, "loadblockpack", f.absolutePath)
                        } catch (e: java.net.SocketTimeoutException) {
                            // The node stores a pack's blocks before it starts connecting them, so a slow
                            // reply means it is busy connecting: the blocks are in.
                        }
                        done.add(p.name)
                    } catch (e: Exception) {
                        failedAt[p.name] = System.currentTimeMillis()
                        if (blockPackErrors.incrementAndGet() % 20 == 1L) diag.event("swiftsync_block_pack_error", "pack" to p.name, "error" to e.toString().take(200))
                    } finally {
                        File(dir, p.name).delete()
                        busy.remove(p.name)
                    }
                }
            }
            delay(1_000)
        }
    }

    /** Stream a pack's .gz, checking its SHA-256, and unzip it to [dir]/<first>-<last>. */
    private fun download(terminal: String, p: Pack, dir: File, sub: String = "pack"): File {
        val dest = File(dir, p.name)
        val part = File(dir, p.name + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        val c = URL(BASE + "v1/$terminal/$sub/${p.name}.gz").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            java.util.zip.GZIPInputStream(java.security.DigestInputStream(c.inputStream.buffered(1 shl 16), md), 1 shl 16).use { i ->
                part.outputStream().buffered(1 shl 16).use { o -> i.copyTo(o, 1 shl 16) }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != p.sha256 || part.length() != p.bytes) {
            part.delete()
            throw IOException("pack ${p.name} doesn't match its listing")
        }
        if (!part.renameTo(dest)) throw IOException("cannot rename ${part.name}")
        return dest
    }

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
