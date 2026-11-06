package com.awokenlazarus.eddy

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Everything needed to improve the sync later, kept on the phone:
 *  - events.jsonl: starts, exits, pauses and their reasons, network and thermal changes
 *  - samples-<day>.jsonl: one line a minute (node progress, CPU, RAM, I/O, heat, battery, lag)
 *  - summary.json: milestones (when each 10,000 blocks were reached) and totals
 *  - device.json: the phone
 *  - node-logs/: the node's own debug.log, rotated and gzipped
 * They live in Android/data/<package>/files/diag, readable over adb without root.
 */
class Diagnostics(ctx: Context) {
    val root: File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "diag").apply { mkdirs() }
    val nodeLogs: File = File(root, "node-logs").apply { mkdirs() }
    private val cacheDir = ctx.cacheDir

    @Synchronized
    fun event(type: String, vararg kv: Pair<String, Any?>) {
        val j = JSONObject().put("t", iso()).put("type", type)
        kv.forEach { (k, v) -> j.put(k, v ?: JSONObject.NULL) }
        File(root, "events.jsonl").appendText(j.toString() + "\n")
        Log.i(TAG, j.toString())
    }

    @Synchronized
    fun sample(j: JSONObject) {
        j.put("t", iso())
        File(root, "samples-${day()}.jsonl").appendText(j.toString() + "\n")
    }

    @Synchronized
    fun writeJson(name: String, j: JSONObject) {
        val tmp = File(root, "$name.tmp")
        tmp.writeText(j.toString(2))
        tmp.renameTo(File(root, name))
    }

    fun readJson(name: String): JSONObject =
        runCatching { JSONObject(File(root, name).readText()) }.getOrDefault(JSONObject())

    /** Move debug.log aside once it passes 64 MB, tell the node to reopen it, and gzip the old one. */
    fun rotateNodeLog(dataDir: File, pid: Int?) {
        val log = File(dataDir, "debug.log")
        if (log.length() < ROTATE_BYTES) return
        val aside = File(dataDir, "debug-rotating.log")
        if (aside.exists() || !log.renameTo(aside)) return
        if (pid != null) android.os.Process.sendSignal(pid, 1) // SIGHUP: reopen debug.log
        val out = File(nodeLogs, "debug-${stamp()}.log.gz")
        aside.inputStream().use { i -> GZIPOutputStream(out.outputStream()).use { o -> i.copyTo(o, 1 shl 16) } }
        aside.delete()
        // Keep at most 1 GB, but always keep the oldest file (the start of the sync).
        val files = nodeLogs.listFiles()?.sortedBy { it.name } ?: return
        var total = files.sumOf { it.length() }
        for (f in files.drop(1)) {
            if (total <= LOG_CAP_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    /** One zip with everything but the old node logs, plus the last 8 MB of the live one. */
    fun export(dataDir: File): File {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val zip = File(dir, "eddy-diagnostics-${stamp()}.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            root.listFiles()?.filter { it.isFile }?.forEach { f ->
                z.putNextEntry(ZipEntry("diag/${f.name}"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            File(dataDir, "bitcoin.conf").takeIf { it.exists() }?.let { f ->
                z.putNextEntry(ZipEntry("bitcoin.conf"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            File(dataDir, "debug.log").takeIf { it.exists() }?.let { f ->
                z.putNextEntry(ZipEntry("debug-tail.log"))
                z.write(tail(f, 8 shl 20))
                z.closeEntry()
            }
            z.putNextEntry(ZipEntry("node-logs.txt"))
            z.write((nodeLogs.listFiles()?.sortedBy { it.name }?.joinToString("\n") { "${it.name} ${it.length()}" } ?: "").toByteArray())
            z.closeEntry()
        }
        return zip
    }

    companion object {
        const val TAG = "Eddy"
        const val ROTATE_BYTES = 64L shl 20
        const val LOG_CAP_BYTES = 1L shl 30

        fun iso(t: Long = System.currentTimeMillis()): String = fmt("yyyy-MM-dd'T'HH:mm:ss'Z'").format(Date(t))
        fun day(): String = fmt("yyyy-MM-dd").format(Date())
        fun stamp(): String = fmt("yyyyMMdd-HHmmss").format(Date())
        private fun fmt(p: String) = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun tail(f: File, max: Int): ByteArray = RandomAccessFile(f, "r").use { r ->
            val n = minOf(max.toLong(), r.length()).toInt()
            r.seek(r.length() - n)
            ByteArray(n).also { r.readFully(it) }
        }

        fun tailLines(f: File, lines: Int): String = runCatching {
            String(tail(f, 64 shl 10)).lines().takeLast(lines).joinToString("\n")
        }.getOrDefault("")
    }
}
