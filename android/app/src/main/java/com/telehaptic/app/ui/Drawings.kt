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
import androidx.compose.ui.graphics.drawscope.withTransform
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
private const val PALM_MM = 2f
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

/** Pictogram of a hand posture or of the calibration, flat two-tone style. */
@Composable
fun PosePicture(kind: String, modifier: Modifier = Modifier.size(72.dp)) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val fill = accent.copy(alpha = 0.18f)
    val bad = Palette.bad
    Canvas(modifier) {
        // All drawings use a 120 x 90 box, scaled to fit and centred.
        val k = minOf(size.width / 120f, size.height / 90f)
        val dx = (size.width - 120f * k) / 2
        val dy = (size.height - 90f * k) / 2
        fun p(d: String) = androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toPath()
        fun shape(d: String, f: Color, o: Color?, w: Float = 2f) {
            val path = p(d)
            withTransform({ translate(dx, dy); scale(k, k, Offset.Zero) }) {
                drawPath(path, f)
                if (o != null) drawPath(path, o, style = Stroke(width = w))
            }
        }
        val capsule = { d: String -> shape(d, accent, null) }
        when (kind) {
            "table", "nomagnet" -> {
                shape("M4 66 H116 V74 H4 Z", ink.copy(alpha = 0.25f), null)                                 // table top
                shape("M6 56 Q6 46 16 46 L66 44 Q74 44 74 52 L74 58 Q74 64 66 64 L16 66 Q6 66 6 56 Z", fill, accent)   // forearm lying on the table
                shape("M70 64 L70 34 Q70 26 78 26 L88 26 Q96 26 96 34 L96 64 Z", fill, accent)               // hand standing on its edge
                shape("M92 30 Q104 26 110 30 Q112 34 106 36 L94 38 Z", fill, accent)                        // thumb on top
                shape("M96 40 Q108 40 108 50 Q108 60 96 62 Z", fill, accent)                                 // curled fingers
                capsule("M74 36 Q74 33 77 33 L86 33 Q89 33 89 36 L89 44 Q89 47 86 47 L77 47 Q74 47 74 44 Z")   // capsule on the back of the hand
                if (kind == "nomagnet") {
                    shape("M98 6 A12 12 0 1 1 97.9 6 Z", Color.Transparent, bad, 3f)
                    shape("M90 10 L106 26", Color.Transparent, bad, 3f)
                }
            }
            "down" -> {
                shape("M60 4 A9 9 0 1 1 59.9 4 Z", fill, accent)                                              // head
                shape("M48 24 Q48 20 52 20 L68 20 Q72 20 72 24 L72 58 L48 58 Z", fill, accent)               // torso
                shape("M51 58 L58 58 L58 88 L51 88 Z M62 58 L69 58 L69 88 L62 88 Z", fill, accent)           // legs
                shape("M74 22 Q80 22 80 28 L80 64 L74 64 Z", fill, accent)                                    // arm hanging down
                shape("M73 64 L81 64 Q83 64 83 67 L83 78 Q83 82 79 82 L75 82 Q71 82 71 78 L71 67 Q71 64 73 64 Z", fill, accent)  // hand
                capsule("M73 66 L81 66 L81 72 L73 72 Z")
            }
            "rotate" -> {
                shape("M50 30 Q50 24 56 24 L64 24 Q70 24 70 30 L70 62 L50 62 Z", fill, accent)               // hand, back towards us
                capsule("M54 32 L66 32 L66 42 L54 42 Z")
                shape("M24 46 A36 30 0 1 1 30 66", Color.Transparent, accent, 3f)                              // turning arrow
                shape("M30 66 L24 56 M30 66 L38 60", Color.Transparent, accent, 3f)
            }
        }
    }
}
