package com.awokenlazarus.eddy.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.awokenlazarus.eddy.NodeState
import com.awokenlazarus.eddy.Phase
import com.awokenlazarus.eddy.Prefs
import com.awokenlazarus.eddy.R
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Settings(val chargingOnly: Boolean, val pauseHot: Boolean, val pauseInUse: Boolean, val benchLogs: Boolean)

private val body = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = C.ink2, lineHeight = 22.sp)
private val small = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = C.muted, lineHeight = 18.sp)
private val label = TextStyle(fontFamily = Inter, fontSize = 12.sp, color = C.faint, fontWeight = FontWeight(500), letterSpacing = 0.4.sp)
private val num = TextStyle(fontFamily = Mono, fontSize = 17.sp, color = C.arc, fontWeight = FontWeight(500))

@Composable
fun EddyScreen(
    state: StateFlow<NodeState>,
    prefs: Prefs,
    ignoringBatteryOpt: () -> Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettingsChanged: (Settings) -> Unit,
    onAllowBackground: () -> Unit,
    onShare: () -> Unit,
    diagPath: String,
) {
    val s by state.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(Settings(prefs.chargingOnly, prefs.pauseHot, prefs.pauseInUse, prefs.benchLogs)) }
    val owner = LocalLifecycleOwner.current
    val exempt by produceState(true, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { value = ignoringBatteryOpt(); awaitCancellation() }
    }
    fun change(n: Settings) { settings = n; onSettingsChanged(n) }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        River(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(s)
            ProgressCard(s)
            Stats(s)
            Controls(s, onStart, onStop)
            if (!exempt) BackgroundCard(onAllowBackground)
            SettingsCard(settings, ::change)
            DiagnosticsCard(diagPath, onShare)
            Text(
                "Bitcoin (XBT) is secured by BLAKE2b proof of work. Eddy runs Bitcoin Knots and checks every block and " +
                    "signature since 2009 itself. It trusts no other node's validation.",
                style = small.copy(color = C.faint), modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Header(s: NodeState) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.mark), null, Modifier.size(34.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Eddy", style = TextStyle(fontFamily = Inter, fontSize = 22.sp, fontWeight = FontWeight(700), color = C.fg))
            Text("Bitcoin (XBT) full node", style = small)
        }
        Pill(s.phase)
    }
}

@Composable
private fun Pill(p: Phase) {
    val (text, color) = when (p) {
        Phase.Syncing -> "Validating" to C.volt
        Phase.Synced -> "Synced" to C.ok
        Phase.Paused -> "Paused" to C.warn
        Phase.Starting -> "Starting" to C.arc
        Phase.Stopping -> "Stopping" to C.arc
        Phase.Error -> "Problem" to C.bad
        Phase.Stopped -> "Off" to C.faint
    }
    Row(
        Modifier.clip(RoundedCornerShape(99.dp)).background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.40f), RoundedCornerShape(99.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text, style = TextStyle(fontFamily = Inter, fontSize = 13.sp, fontWeight = FontWeight(600), color = color))
    }
}

@Composable
private fun ProgressCard(s: NodeState) {
    Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The big number is blocks validated, which is what people expect. Knots' own progress figure
        // counts transactions, which barely moves through the nearly empty early years, so it's
        // shown underneath instead.
        val blocks = if (s.headers > 0) s.height.toDouble() / s.headers else 0.0
        Text("CHAIN VALIDATED", style = label)
        Text(
            if (blocks < 0.1) "%.1f%%".format(blocks * 100) else "%.0f%%".format(blocks * 100),
            style = TextStyle(fontFamily = Mono, fontSize = 46.sp, fontWeight = FontWeight(700), color = C.volt),
        )
        Bar(blocks.toFloat())
        Text(
            if (s.headers > 0) "Block %,d of %,d".format(s.height, s.headers) else "Waiting for the first headers",
            style = num.copy(color = C.ink2, fontSize = 15.sp),
        )
        Text(
            (if (s.progress < 0.01) "%.3f%%" else "%.1f%%").format(s.progress * 100) +
                " of all transactions (the later years hold most of them)",
            style = small,
        )
        val line = buildList {
            if (s.tipTime > 0) add("Up to " + SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(s.tipTime * 1000)))
            if (s.blocksPerHour > 0 && s.ibd) add("%,d blocks/h".format(s.blocksPerHour.toLong()))
            if (s.ibd && s.phase == Phase.Syncing) add(s.etaSeconds?.let { "about ${duration(it)} left" } ?: "estimating time left")
        }.joinToString(" · ")
        if (line.isNotEmpty()) Text(line, style = small)
        if (s.swiftActive && s.swiftTerminal > 0) {
            Text(
                "SwiftSync: every block and signature is checked now; the coins it was given are proven at block %,d.".format(s.swiftTerminal),
                style = small.copy(color = C.arc),
            )
        }
        if (s.detail.isNotEmpty() && s.phase != Phase.Syncing && s.phase != Phase.Synced) {
            Text(s.detail, style = body.copy(color = if (s.phase == Phase.Error) C.bad else C.warn))
        }
    }
}

@Composable
private fun Bar(f: Float) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)).background(C.track)) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(f.coerceIn(0f, 1f)).clip(RoundedCornerShape(99.dp))
                .background(Brush.horizontalGradient(listOf(C.volt, C.volt2)))
        )
    }
}

@Composable
private fun Stats(s: NodeState) {
    val tiles = listOf(
        Triple("PEERS", if (s.netActive) "${s.peers}" else "–", if (s.wifi) "Wi-Fi" else "No Wi-Fi"),
        Triple("DOWNLOADED", bytes(s.recvBytes), "this session"),
        Triple("DISK", bytes(s.diskBytes), "of 32 GB budget"),
        Triple("CACHE", "%,d MB".format(s.cacheMb.toLong()), "of %,d MB".format(s.dbcacheMb)),
        Triple("NODE CPU", "%d%%".format(s.nodeCpuPct.toInt()), "low priority"),
        Triple("NODE RAM", bytes(s.nodeRssBytes), ""),
        Triple("BATTERY", if (s.batteryPct >= 0) "${s.batteryPct}%" else "–", "%.1f °C%s".format(s.batteryTempC, if (s.charging) " · charging" else "")),
        Triple("PHONE", thermal(s.thermal), if (s.lagP95Ms > 0) "lag ${s.lagP95Ms} ms" else ""),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (k, v, sub) ->
                    Column(Modifier.weight(1f).card()) {
                        Text(k, style = label)
                        Spacer(Modifier.height(4.dp))
                        Text(v, style = num)
                        if (sub.isNotEmpty()) Text(sub, style = small.copy(fontSize = 12.sp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Controls(s: NodeState, onStart: () -> Unit, onStop: () -> Unit) {
    val running = s.phase != Phase.Stopped
    if (!running) {
        Button("Start validating", primary = true, onClick = onStart)
    } else {
        Button(if (s.phase == Phase.Stopping) "Stopping…" else "Stop the node", primary = false, onClick = onStop)
    }
}

@Composable
private fun Button(text: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val m = Modifier.fillMaxWidth().height(54.dp).clip(shape)
    Box(
        (if (primary) m.background(Brush.verticalGradient(listOf(C.volt2, C.volt))) else m.border(1.dp, C.line2, shape))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontFamily = Inter, fontSize = 16.sp, fontWeight = FontWeight(600), color = if (primary) C.onVolt else C.fg))
    }
}

@Composable
private fun BackgroundCard(onAllow: () -> Unit) {
    Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("LET IT RUN IN THE BACKGROUND", style = label.copy(color = C.warn))
        Text("Android may stop the node to save battery. Allow Eddy to run unrestricted so the sync keeps going with the screen off.", style = body)
        Button("Allow", primary = false, onClick = onAllow)
    }
}

@Composable
private fun SettingsCard(s: Settings, change: (Settings) -> Unit) {
    Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("RULES", style = label)
        Toggle("Wi-Fi only for the first sync", "No mobile data until the chain is synced; after that, either.", true, enabled = false) {}
        Toggle("Only while charging", "Pause when the phone is unplugged.", s.chargingOnly) { change(s.copy(chargingOnly = it)) }
        Toggle("Pause when hot", "Pause if Android reports severe heat or the battery reaches 42 °C.", s.pauseHot) { change(s.copy(pauseHot = it)) }
        Toggle("Pause while I use the phone", "Sync only with the screen off.", s.pauseInUse) { change(s.copy(pauseInUse = it)) }
        Toggle("Detailed timing logs", "Per-block timings for diagnostics (takes effect at the next start).", s.benchLogs) { change(s.copy(benchLogs = it)) }
    }
}

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = body.copy(color = C.fg))
            Text(sub, style = small)
        }
        Switch(
            checked = on, onCheckedChange = set, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = C.onVolt, checkedTrackColor = C.volt, checkedBorderColor = C.volt,
                uncheckedThumbColor = C.muted, uncheckedTrackColor = C.bg2, uncheckedBorderColor = C.line2,
                disabledCheckedThumbColor = C.onVolt, disabledCheckedTrackColor = C.volt.copy(alpha = 0.55f),
            ),
        )
    }
}

@Composable
private fun DiagnosticsCard(path: String, onShare: () -> Unit) {
    Column(Modifier.fillMaxWidth().card(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("DIAGNOSTICS", style = label)
        Text("Eddy records the sync's speed, the phone's heat, battery, memory and responsiveness, and the node's own log.", style = body)
        Text(path, style = small.copy(fontFamily = Mono, fontSize = 12.sp, color = C.arc))
        Button("Share diagnostics", primary = false, onClick = onShare)
    }
}

private fun thermal(t: Int) = when (t) {
    0 -> "Cool"; 1 -> "Warm"; 2 -> "Warm+"; 3 -> "Hot"; 4 -> "Critical"; 5 -> "Emergency"; 6 -> "Shutdown"; else -> "–"
}

private fun bytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%d MB".format(b shr 20)
    b > 0 -> "%d KB".format(b shr 10)
    else -> "0"
}

private fun duration(s: Long): String = when {
    s >= 86_400 * 2 -> "${s / 86_400} days"
    s >= 3_600 -> "${s / 3_600} h ${(s % 3_600) / 60} min"
    else -> "${(s / 60).coerceAtLeast(1)} min"
}
