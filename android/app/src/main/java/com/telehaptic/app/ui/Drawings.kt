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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.telehaptic.app.core.Keys

enum class Mark { NONE, NEXT, HOLD, RECOGNIZED }

/**
 * Four fingers seen from the palm, pointing right: index at the top, little finger at the bottom,
 * the phalanx near the palm on the left. The keys then sit as on a phone keypad. Phalanx lengths
 * follow a typical hand (mm, palm to tip); fingers start at a staggered knuckle line.
 */
// Columns line up like a phone keypad; the phalanx near the palm is the longest.
private val COLS = floatArrayOf(0.40f, 0.32f, 0.28f)

private class KeyBox(val key: String, val left: Float, val top: Float, val width: Float, val height: Float)

private fun layout(w: Float, h: Float): List<KeyBox> {
    val rowH = h / 4f
    val out = ArrayList<KeyBox>()
    for (f in 0 until 4) {
        val thick = rowH * 0.84f
        val top = f * rowH + (rowH - thick) / 2
        var x = 0f
        for (c in 0 until 3) {
            val len = w * COLS[c]
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
    Canvas(modifier.aspectRatio(1.6f).then(tapMod).semantics {
        contentDescription = "Keypad on the four fingers" + if (key.isNotEmpty() && mark != Mark.NONE) ", key $key highlighted" else ""
    }) {
        val boxes = layout(size.width, size.height)
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

/** Line illustration of a hand posture or of the calibration (160 x 110 box, drawn to fit). */
@Composable
fun PosePicture(kind: String, modifier: Modifier = Modifier.size(72.dp)) {
    val accent = MaterialTheme.colorScheme.primary
    val fill = accent.copy(alpha = 0.10f)
    val ground = MaterialTheme.colorScheme.onSurfaceVariant
    val magnet = keyColor()
    val bad = Palette.bad
    Canvas(modifier) {
        val k = minOf(size.width / 160f, size.height / 110f)
        val dx = (size.width - 160f * k) / 2
        val dy = (size.height - 110f * k) / 2
        fun path(d: String) = androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toPath()
        withTransform({ translate(dx, dy); scale(k, k, Offset.Zero) }) {
            val line = Stroke(width = 2.4f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            val thin = Stroke(width = 1.4f, cap = StrokeCap.Round)
            fun body(d: String) { val q = path(d); drawPath(q, fill); drawPath(q, accent, style = line) }
            fun stroke(d: String, c: Color, st: Stroke) = drawPath(path(d), c, style = st)
            fun box(x: Float, y: Float, w: Float, h: Float, r: Float, c: Color) = drawRoundRect(c, Offset(x, y), Size(w, h), CornerRadius(r))
            when (kind) {
                "table", "nomagnet" -> {
                    stroke("M4 93 H156", ground.copy(alpha = 0.55f), line)
                    stroke("M10 99 H150", ground.copy(alpha = 0.25f), thin)
                    body("M6 92 L6 74 C24 70 46 69 60 70 C68 68 74 60 82 54 C86 51 90 50 94 51 " +
                        "C97 44 103 38 110 37 C116 36 119 40 116 44 C113 48 106 50 103 54 " +
                        "L128 56 C135 56 138 60 135 64 L132 65 C138 66 139 71 134 73 L131 74 " +
                        "C137 75 137 80 132 82 L128 83 C133 85 132 90 126 90 L96 92 Z")
                    stroke("M104 64 L131 65 M104 73 L130 74 M102 82 L127 83", accent.copy(alpha = 0.5f), thin)
                    stroke("M60 71 C62 78 62 86 60 92", accent.copy(alpha = 0.4f), thin)
                    box(70f, 62f, 22f, 16f, 4f, accent)                                   // capsule
                    if (kind == "table") {
                        withTransform({ rotate(-20f, Offset(112f, 40.5f)) }) { box(109f, 38.5f, 6f, 4f, 1.2f, magnet) }
                    } else {
                        drawCircle(bad, 9f, Offset(128f, 22f), style = Stroke(width = 2.4f))
                        stroke("M121.6 15.6 L134.4 28.4", bad, Stroke(width = 2.4f, cap = StrokeCap.Round))
                    }
                }
                "down" -> {
                    drawCircle(fill, 10f, Offset(80f, 15f)); drawCircle(accent, 10f, Offset(80f, 15f), style = line)
                    body("M60 36 C52 36 47 41 47 49 L46 80 C46 83 48 84 50 84 L56 84 C58 84 59 83 59 80 L61 52")
                    body("M100 36 C108 36 113 41 113 49 L114 80 C114 83 112 84 110 84 L104 84 C102 84 101 83 101 80 L99 52")
                    body("M60 108 L60 50 C60 38 67 31 80 31 C93 31 100 38 100 50 L100 108")
                    body("M47 84 C44 90 45 98 50 101 C55 103 59 99 59 93 L59 84")
                    body("M101 84 L101 93 C101 99 105 103 110 101 C115 98 116 90 113 84")
                    box(102.5f, 86f, 10f, 8f, 2f, accent)
                }
                "rotate" -> {
                    body("M62 92 L62 36 C62 32 70 32 70 36 L70 48 L71 30 C71 26 79 26 79 30 L79 48 L80 32 C80 28 88 28 88 32 " +
                        "L88 50 L89 40 C89 36 97 36 97 40 L97 66 C101 60 107 58 109 61 C111 64 106 70 101 78 C98 84 95 88 92 92 Z")
                    box(68f, 56f, 22f, 15f, 3.5f, accent)
                    val c = Offset(80f, 59f); val r = 46f
                    val start = -50f; val sweep = 300f
                    drawArc(accent, start, sweep, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(width = 2.6f, cap = StrokeCap.Round))
                    val a = Math.toRadians((start + sweep).toDouble())
                    val n = Offset(kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())
                    val t = Offset(-n.y, n.x)
                    val e = c + n * r
                    val head = androidx.compose.ui.graphics.Path().apply {
                        moveTo((e + t * 9f).x, (e + t * 9f).y)
                        lineTo((e + n * 6f - t * 2f).x, (e + n * 6f - t * 2f).y)
                        lineTo((e - n * 6f - t * 2f).x, (e - n * 6f - t * 2f).y)
                        close()
                    }
                    drawPath(head, accent)
                }
            }
        }
    }
}
