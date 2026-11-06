package com.awokenlazarus.eddy.ui

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * The river behind the page, as on lightning-stream.com: two soft glows and a few slow streams in
 * lightning yellow and electric blue. 12 frames a second, only while the screen shows the app, and
 * one still frame when the phone has animations turned off.
 */
@Composable
fun River(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val still = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val owner = LocalLifecycleOwner.current
    val t by produceState(0f, owner, still) {
        if (still) return@produceState
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = (SystemClock.uptimeMillis() % 3_600_000L) / 1000f
                delay(83)
            }
        }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.radialGradient(listOf(Color(0x12FFD60A), Color.Transparent), Offset(w * 0.82f, -h * 0.04f), w * 0.9f))
        drawRect(Brush.radialGradient(listOf(Color(0x145AAEFF), Color.Transparent), Offset(w * 0.06f, h * 1.04f), w * 0.9f))
        val step = 18.dp.toPx()
        val stroke = Stroke(1.1.dp.toPx())
        for (i in 0 until 4) {
            val path = Path()
            val base = h * (0.16f + 0.22f * i)
            val amp = h * 0.035f
            var x = -step
            var firstPt = true
            while (x <= w + step) {
                val u = x / w
                val y = base + u * h * 0.08f +
                    amp * sin(u * 2.3f * PI.toFloat() + t * 0.22f + i * 1.7f) +
                    amp * 0.45f * sin(u * 5.1f * PI.toFloat() - t * 0.31f + i * 0.9f)
                if (firstPt) { path.moveTo(x, y); firstPt = false } else path.lineTo(x, y)
                x += step
            }
            drawPath(
                path,
                Brush.horizontalGradient(listOf(C.volt.copy(alpha = 0.10f), C.arc2.copy(alpha = 0.13f))),
                style = stroke,
            )
        }
    }
}
