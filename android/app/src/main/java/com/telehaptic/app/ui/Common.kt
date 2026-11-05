package com.telehaptic.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.telehaptic.app.Capsule
import com.telehaptic.app.Nav
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    nav: Nav?,
    actions: @Composable () -> Unit = {},
    onBack: (() -> Unit)? = null,   // the top-bar arrow; defaults to nav.back()
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (nav != null) TextButton(onClick = { onBack?.invoke() ?: nav.back() }) { Text("‹", fontSize = 28.sp) }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad: PaddingValues ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

fun statusTitle(s: Capsule.Status) = when (s) {
    Capsule.Status.NO_PERMISSION -> "Bluetooth permission needed"
    Capsule.Status.BT_OFF -> "Bluetooth is off"
    Capsule.Status.IDLE -> "Capsule not connected"
    Capsule.Status.SEARCHING -> "Looking for the capsule…"
    Capsule.Status.NOT_FOUND -> "Capsule not found"
    Capsule.Status.CONNECTING -> "Connecting…"
    Capsule.Status.CONNECTED -> "Connected"
    Capsule.Status.NO_DATA -> "Connected, but no data"
    Capsule.Status.RECONNECTING -> "Connection lost, reconnecting…"
}

fun statusHint(s: Capsule.Status) = when (s) {
    Capsule.Status.BT_OFF -> "Turn Bluetooth on in quick settings."
    Capsule.Status.NOT_FOUND -> "Switch the capsule on, bring it close to the phone and tap Connect the capsule."
    Capsule.Status.NO_DATA -> "Switch the capsule off and on. If that does not help, open Diagnostics and Logs."
    Capsule.Status.RECONNECTING -> "Check that the capsule is on and nearby."
    else -> ""
}

fun statusColor(s: Capsule.Status, problems: Boolean): Color = when (s) {
    Capsule.Status.CONNECTED -> if (problems) Palette.warn else Palette.ok
    Capsule.Status.SEARCHING, Capsule.Status.CONNECTING, Capsule.Status.RECONNECTING, Capsule.Status.NO_DATA -> Palette.warn
    Capsule.Status.NOT_FOUND, Capsule.Status.BT_OFF, Capsule.Status.NO_PERMISSION -> Palette.bad
    Capsule.Status.IDLE -> Color(0xFF8A90A0)
}

val Capsule.Status.linked get() = this == Capsule.Status.CONNECTED || this == Capsule.Status.NO_DATA

/** LiPo voltage to an approximate charge, %. Rough: the curve is not linear and depends on load. */
fun batteryPercent(mv: Int): Int = when {
    mv <= 0 -> -1
    mv >= 4150 -> 100
    mv <= 3300 -> 0
    else -> ((mv - 3300) * 100 / (4150 - 3300))
}

fun share(ctx: Context, file: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, title))
}

/** Chips in the app's accent colour (the Material default is purple). */
@Composable
fun chipColors() = androidx.compose.material3.FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
    selectedLabelColor = MaterialTheme.colorScheme.primary,
)

/** Magnet on the nail and distance between the sensors; saved as they change. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MagnetFields() {
    val app = com.telehaptic.app.App.get()
    var magnet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(app.prefs.magnet) }
    var distance by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(if (app.prefs.distanceMm > 0) app.prefs.distanceMm.toString() else "") }
    Muted("Magnet on the nail")
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.telehaptic.app.Prefs.MAGNETS.forEach { (code, label) ->
            androidx.compose.material3.FilterChip(selected = magnet == code, onClick = { magnet = code; app.prefs.magnet = code },
                label = { Text(label) }, colors = chipColors())
        }
    }
    androidx.compose.material3.OutlinedTextField(
        value = distance,
        onValueChange = { v -> distance = v.filter { it.isDigit() }.take(3); app.prefs.distanceMm = distance.toIntOrNull() ?: 0 },
        label = { Text("Distance between the sensors, mm") },
        supportingText = { Text("Centre of one chip to the centre of the other") },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}
