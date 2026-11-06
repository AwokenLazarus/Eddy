package com.awokenlazarus.eddy

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * The node's settings, written fresh at every start.
 *
 * Aims, in order: validate everything from the genesis block (no assumevalid shortcut), keep the
 * phone usable while it does, then go as fast as that allows.
 */
object NodeConfig {
    const val RPC_PORT = 48332
    const val PRUNE_MB = 4000

    /** Below normal priority, so the screen and the apps in use get the CPU first. */
    const val NICE = 10

    data class Plan(val dbcacheMb: Int, val par: Int, val synced: Boolean, val totalRamMb: Int, val cores: Int)

    fun plan(ctx: Context, synced: Boolean): Plan {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        val totalMb = (mi.totalMem shr 20).toInt()
        val cores = Runtime.getRuntime().availableProcessors()
        // A big UTXO cache is the main speed lever during the first sync, but a sixth of RAM (at
        // most 2 GB) keeps the rest of the phone out of swap. Once synced, a small cache is enough.
        val dbcache = if (synced) 300 else (totalMb / 6).coerceIn(450, 2048)
        // Leave two cores for the user.
        val par = (cores - 2).coerceAtLeast(1)
        return Plan(dbcache, par, synced, totalMb, cores)
    }

    fun write(ctx: Context, dataDir: File, plan: Plan, benchLogs: Boolean): File {
        val peers = runCatching {
            ctx.assets.open("peers.txt").bufferedReader().readLines()
        }.getOrDefault(emptyList()).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        val lines = buildList {
            add("# Written by Eddy at every start; edits here are replaced.")
            add("server=1")
            // Check every signature from 2009 on.
            add("assumevalid=0")
            add("prune=$PRUNE_MB")
            add("dbcache=${plan.dbcacheMb}")
            add("par=${plan.par}")
            // During the first sync, skip relaying loose transactions: less traffic, less CPU.
            add("blocksonly=${if (plan.synced) 0 else 1}")
            add("maxconnections=12")
            add("maxmempool=50")
            add("persistmempool=0")
            // A phone is behind NAT and on battery: outbound connections only.
            add("listen=0")
            add("listenonion=0")
            add("discover=0")
            add("upnp=0")
            add("natpmp=0")
            add("disablewallet=1")
            // Eddy turns the network on only when its rules allow (Wi-Fi, temperature, battery).
            add("networkactive=0")
            add("rpcbind=127.0.0.1")
            add("rpcallowip=127.0.0.1")
            add("rpcport=$RPC_PORT")
            add("rpcthreads=4")
            add("shrinkdebugfile=0")
            if (benchLogs) add("debug=bench")
            peers.forEach { add("addnode=$it") }
        }
        val f = File(dataDir, "bitcoin.conf")
        f.writeText(lines.joinToString("\n", postfix = "\n"))
        return f
    }
}
