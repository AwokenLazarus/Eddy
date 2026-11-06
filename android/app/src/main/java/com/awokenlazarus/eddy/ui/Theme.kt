package com.awokenlazarus.eddy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.awokenlazarus.eddy.R

/** Lightning Stream's dark theme: near-black ground, lightning yellow for the main action, blue for data. */
object C {
    val bg = Color(0xFF050507)
    val bg2 = Color(0xFF0A0B0F)
    val fg = Color(0xFFF5F5F2)
    val ink2 = Color(0xFFD9DBE0)
    val muted = Color(0xFFA4A8B1)
    val faint = Color(0xFF80858F)
    val edge = Color(0x14FFFFFF)
    val line2 = Color(0x29FFFFFF)
    val volt = Color(0xFFFFD60A)
    val volt2 = Color(0xFFFFE766)
    val voltSoft = Color(0x1AFFD60A)
    val arc = Color(0xFF9FD8FF)
    val arc2 = Color(0xFF5AAEFF)
    val ok = Color(0xFF6BE3A4)
    val bad = Color(0xFFFF8A8A)
    val warn = Color(0xFFFFB648)
    val onVolt = Color(0xFF0A0900)
    val track = Color(0x0FFFFFFF)
}

@OptIn(ExperimentalTextApi::class)
private fun inter(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

@OptIn(ExperimentalTextApi::class)
private fun mono(w: Int) = Font(R.font.jetbrains_mono, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))
val Mono = FontFamily(mono(400), mono(500), mono(700))

private val cardShape = RoundedCornerShape(20.dp)
private val cardBrush = Brush.verticalGradient(listOf(Color(0xEB10131A), Color(0xF00A0C10)))

fun Modifier.card(): Modifier = this
    .clip(cardShape)
    .background(cardBrush)
    .border(1.dp, C.edge, cardShape)
    .padding(18.dp)
