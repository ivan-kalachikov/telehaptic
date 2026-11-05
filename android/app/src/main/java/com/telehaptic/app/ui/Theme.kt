package com.telehaptic.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

object Palette {
    val accentLight = Color(0xFF2C56C9)
    val accentDark = Color(0xFF7D9BFF)
    val keyLight = Color(0xFFE08A1E)
    val keyDark = Color(0xFFF0A443)
    val ok = Color(0xFF1F8A4C)
    val warn = Color(0xFFB26B00)
    val bad = Color(0xFFC23B3B)
}

@Composable
fun keyColor() = if (isSystemInDarkTheme()) Palette.keyDark else Palette.keyLight

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Palette.accentDark,
        onPrimary = Color(0xFF0D1530),
        background = Color(0xFF111318),
        surface = Color(0xFF111318),
        surfaceVariant = Color(0xFF1C2029),
        onBackground = Color(0xFFE8EAEF),
        onSurface = Color(0xFFE8EAEF),
        onSurfaceVariant = Color(0xFF9AA1B2),
        outlineVariant = Color(0xFF2C313C),
    ) else lightColorScheme(
        primary = Palette.accentLight,
        onPrimary = Color.White,
        background = Color(0xFFF6F7F9),
        surface = Color(0xFFF6F7F9),
        surfaceVariant = Color(0xFFFFFFFF),
        onBackground = Color(0xFF16181D),
        onSurface = Color(0xFF16181D),
        onSurfaceVariant = Color(0xFF5B6170),
        outlineVariant = Color(0xFFDFE2E8),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Russian number formatting: 1,5 */
fun f1(x: Double) = String.format(Locale.US, "%.1f", x)
fun f0(x: Double) = String.format(Locale.US, "%.0f", x)
fun f2(x: Double) = String.format(Locale.US, "%.2f", x)

@Composable
fun Section(title: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(color))
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = modifier)
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(v, fontSize = 14.sp)
    }
}
