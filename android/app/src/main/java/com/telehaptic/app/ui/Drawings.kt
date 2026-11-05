package com.telehaptic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.telehaptic.app.core.Keys

enum class Mark { NONE, NEXT, HOLD, RECOGNIZED }

/**
 * Four fingers seen from the palm: index on the left, little finger on the right,
 * the phalanx near the palm at the bottom. Digits as on a phone keypad.
 */
@Composable
fun HandDiagram(key: String, mark: Mark, modifier: Modifier = Modifier.fillMaxWidth()) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val keyC = keyColor()
    Canvas(modifier.aspectRatio(1.25f)) {
        val heights = floatArrayOf(0.84f, 1.0f, 0.92f, 0.70f)
        val gap = size.width * 0.04f
        val w = (size.width - gap * 5) / 4
        val maxH = size.height * 0.96f
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = w * 0.36f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        for (f in 0 until 4) {
            val h = maxH * heights[f]
            val x = gap + f * (w + gap)
            val top = size.height - h
            drawRoundRect(line, Offset(x, top), Size(w, h), CornerRadius(w * 0.45f), style = Stroke(width = 2.dp.toPx()))
            val seg = h / 3
            for (c in 0 until 3) {
                val k = Keys.ALL[f * 3 + c]
                val segTop = size.height - (c + 1) * seg
                if (c > 0) drawLine(line, Offset(x + w * 0.2f, segTop + seg), Offset(x + w * 0.8f, segTop + seg), 1.dp.toPx())
                val hit = k == key && mark != Mark.NONE
                val color = if (mark == Mark.RECOGNIZED) accent else keyC
                if (hit) {
                    val inset = w * 0.08f
                    val rect = Size(w - 2 * inset, seg - 2 * inset)
                    val o = Offset(x + inset, segTop + inset)
                    if (mark == Mark.NEXT) drawRoundRect(color, o, rect, CornerRadius(w * 0.25f), style = Stroke(width = 3.dp.toPx()))
                    else drawRoundRect(color.copy(alpha = 0.25f), o, rect, CornerRadius(w * 0.25f))
                    if (mark != Mark.NEXT) drawRoundRect(color, o, rect, CornerRadius(w * 0.25f), style = Stroke(width = 3.dp.toPx()))
                }
                paint.color = (if (hit) color else muted).toArgb()
                drawContext.canvas.nativeCanvas.drawText(k, x + w / 2, segTop + seg / 2 + paint.textSize * 0.35f, paint)
            }
        }
    }
}

/** Small pictogram of a hand posture or of the calibration. */
@Composable
fun PosePicture(kind: String, modifier: Modifier = Modifier.size(72.dp)) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val s = size.minDimension
        val sw = s * 0.06f
        when (kind) {
            "table" -> {
                drawLine(ink, Offset(s * 0.05f, s * 0.78f), Offset(s * 0.95f, s * 0.78f), sw, StrokeCap.Round)   // table
                drawLine(accent, Offset(s * 0.12f, s * 0.66f), Offset(s * 0.62f, s * 0.66f), sw * 2.2f, StrokeCap.Round) // forearm
                drawLine(accent, Offset(s * 0.66f, s * 0.66f), Offset(s * 0.8f, s * 0.66f), sw * 2.8f, StrokeCap.Round) // hand on edge
                drawLine(accent, Offset(s * 0.74f, s * 0.6f), Offset(s * 0.84f, s * 0.44f), sw, StrokeCap.Round)        // thumb up
            }
            "down" -> {
                drawCircle(ink, s * 0.09f, Offset(s * 0.42f, s * 0.14f), style = Stroke(sw))
                drawLine(ink, Offset(s * 0.42f, s * 0.24f), Offset(s * 0.42f, s * 0.7f), sw, StrokeCap.Round)          // body
                drawLine(accent, Offset(s * 0.56f, s * 0.28f), Offset(s * 0.58f, s * 0.72f), sw * 1.8f, StrokeCap.Round) // arm
                drawLine(accent, Offset(s * 0.58f, s * 0.74f), Offset(s * 0.58f, s * 0.88f), sw * 2.6f, StrokeCap.Round) // hand
            }
            "rotate" -> {
                drawArc(accent, 20f, 280f, false, Offset(s * 0.18f, s * 0.18f), Size(s * 0.64f, s * 0.64f), style = Stroke(sw, cap = StrokeCap.Round))
                drawLine(accent, Offset(s * 0.74f, s * 0.24f), Offset(s * 0.78f, s * 0.38f), sw, StrokeCap.Round)
                drawLine(accent, Offset(s * 0.74f, s * 0.24f), Offset(s * 0.62f, s * 0.3f), sw, StrokeCap.Round)
                drawCircle(ink, s * 0.08f, Offset(s * 0.5f, s * 0.5f))
            }
            "nomagnet" -> {
                drawLine(ink, Offset(s * 0.05f, s * 0.78f), Offset(s * 0.95f, s * 0.78f), sw, StrokeCap.Round)
                drawLine(accent, Offset(s * 0.12f, s * 0.66f), Offset(s * 0.62f, s * 0.66f), sw * 2.2f, StrokeCap.Round)
                drawLine(accent, Offset(s * 0.66f, s * 0.66f), Offset(s * 0.8f, s * 0.66f), sw * 2.8f, StrokeCap.Round)
                drawCircle(Color(0xFFC23B3B), s * 0.13f, Offset(s * 0.8f, s * 0.3f), style = Stroke(sw))
                drawLine(Color(0xFFC23B3B), Offset(s * 0.71f, s * 0.21f), Offset(s * 0.89f, s * 0.39f), sw)
            }
        }
    }
}
