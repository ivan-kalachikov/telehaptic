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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.telehaptic.app.core.Keys

enum class Mark { NONE, NEXT, HOLD, RECOGNIZED }

/**
 * Four fingers seen from the palm, pointing right: index at the top, little finger at the bottom,
 * the phalanx near the palm on the left. The keys then sit as on a phone keypad. Phalanx lengths
 * follow a typical hand (mm, palm to tip); fingers start at a staggered knuckle line.
 */
private val FINGER = arrayOf(           // knuckle offset, then the three phalanx lengths, mm
    floatArrayOf(4f, 40f, 24f, 19f),    // index
    floatArrayOf(6f, 45f, 28f, 20f),    // middle
    floatArrayOf(4f, 42f, 27f, 20f),    // ring
    floatArrayOf(0f, 33f, 19f, 18f),    // little
)
private const val PALM_MM = 22f
private const val SPAN_MM = PALM_MM + 6f + 45f + 28f + 20f + 4f

private class KeyBox(val key: String, val left: Float, val top: Float, val width: Float, val height: Float)

private fun layout(w: Float, h: Float): List<KeyBox> {
    val mm = w / SPAN_MM
    val rowH = h / 4f
    val out = ArrayList<KeyBox>()
    for (f in 0 until 4) {
        val g = FINGER[f]
        val thick = rowH * if (f == 3) 0.78f else 0.86f
        val top = f * rowH + (rowH - thick) / 2
        var x = (PALM_MM + g[0]) * mm
        for (c in 0 until 3) {
            val len = g[1 + c] * mm
            out.add(KeyBox(Keys.ALL[f * 3 + c], x, top, len, thick))
            x += len
        }
    }
    return out
}

@Composable
fun HandDiagram(
    key: String,
    mark: Mark,
    modifier: Modifier = Modifier.fillMaxWidth(),
    letters: Boolean = false,
    onKey: ((String) -> Unit)? = null,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val skin = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val keyC = keyColor()
    var tapMod: Modifier = Modifier
    if (onKey != null) tapMod = Modifier.pointerInput(Unit) {
        detectTapGestures { p ->
            layout(size.width.toFloat(), size.height.toFloat())
                .firstOrNull { p.x >= it.left && p.x <= it.left + it.width && p.y >= it.top && p.y <= it.top + it.height }
                ?.let { onKey(it.key) }
        }
    }
    Canvas(modifier.aspectRatio(1.45f).then(tapMod)) {
        val boxes = layout(size.width, size.height)
        val mm = size.width / SPAN_MM
        // palm on the left, behind the finger roots
        drawRoundRect(skin, Offset(0f, size.height * 0.02f), Size((PALM_MM + 10f) * mm, size.height * 0.96f), CornerRadius(14.dp.toPx()))
        drawRoundRect(line, Offset(0f, size.height * 0.02f), Size((PALM_MM + 10f) * mm, size.height * 0.96f), CornerRadius(14.dp.toPx()), style = Stroke(width = 1.5.dp.toPx()))
        val digit = android.graphics.Paint().apply {
            isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val small = android.graphics.Paint().apply { isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER }
        for (f in 0 until 4) {
            val row = boxes.subList(3 * f, 3 * f + 3)
            val first = row.first(); val last = row.last()
            val r = CornerRadius(first.height / 2)
            val o = Offset(first.left, first.top)
            val sz = Size(last.left + last.width - first.left, first.height)
            drawRoundRect(skin, o, sz, r)
            drawRoundRect(line, o, sz, r, style = Stroke(width = 1.5.dp.toPx()))
            for (b in row.drop(1)) drawLine(line, Offset(b.left, b.top + b.height * 0.18f), Offset(b.left, b.top + b.height * 0.82f), 1.dp.toPx())
        }
        for (b in boxes) {
            val hit = b.key == key && mark != Mark.NONE
            val color = if (mark == Mark.RECOGNIZED) accent else keyC
            if (hit) {
                val inset = b.height * 0.1f
                val o = Offset(b.left + inset, b.top + inset)
                val sz = Size(b.width - 2 * inset, b.height - 2 * inset)
                val cr = CornerRadius(b.height * 0.3f)
                if (mark != Mark.NEXT) drawRoundRect(color.copy(alpha = 0.22f), o, sz, cr)
                drawRoundRect(color, o, sz, cr, style = Stroke(width = 2.5.dp.toPx()))
            }
            val cx = b.left + b.width / 2
            digit.textSize = b.height * (if (letters) 0.4f else 0.44f)
            digit.color = (if (hit) color else ink).toArgb()
            val cy = if (letters) b.top + b.height * 0.5f else b.top + b.height / 2 + digit.textSize * 0.36f
            drawContext.canvas.nativeCanvas.drawText(b.key, cx, cy, digit)
            if (letters) {
                small.textSize = b.height * 0.24f
                small.color = muted.toArgb()
                drawContext.canvas.nativeCanvas.drawText(Keys.letters(b.key), cx, b.top + b.height * 0.82f, small)
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
