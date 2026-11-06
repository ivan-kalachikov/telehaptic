@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.telehaptic.app.ui

import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.selection.toggleable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telehaptic.app.App
import com.telehaptic.app.BuildConfig
import com.telehaptic.app.Nav
import com.telehaptic.app.Prefs
import com.telehaptic.app.core.ScanText
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.sqrt

// ---------------------------------------------------------------- diagnostics

@Composable
fun DiagnosticsScreen(nav: Nav) {
    val app = App.get()
    val ui by app.capsule.ui.collectAsState()

    Page("Diagnostics", nav) {
        CapsuleCard(ui, detailed = true)
        if (!ui.status.linked) {
            Button(onClick = { app.capsule.connect() }, modifier = Modifier.fillMaxWidth()) { Text("Connect the capsule") }
        }

        Section("Bus scan") {
            if (ui.scan.isEmpty()) Muted("No report yet.")
            ui.scan.forEach { Text(ScanText.describe(it), fontSize = 14.sp) }
            if (ui.bootReason.isNotEmpty()) Muted("Firmware ${ui.firmware}, last start: ${bootText(ui.bootReason)}")
            OutlinedButton(onClick = { app.capsule.rescan() }, enabled = ui.status.linked, modifier = Modifier.fillMaxWidth()) { Text("Rescan the sensors") }
        }

        Section("Field, µT") {
            val live = ui.live
            if (live.magUt.isEmpty()) Muted("No data.")
            live.magUt.forEachIndexed { i, v ->
                val m = ui.mags.getOrNull(i)
                Text("Sensor ${i + 1}" + (m?.let { " · ${it.chip} · ${it.where}" } ?: "") + if (live.sat.getOrElse(i) { false }) " · SATURATED" else "",
                    fontWeight = FontWeight.SemiBold, color = if (live.sat.getOrElse(i) { false }) Palette.bad else androidx.compose.ui.graphics.Color.Unspecified)
                Mono("x ${f1(v[0])}   y ${f1(v[1])}   z ${f1(v[2])}   |B| ${f1(norm(v))}")
            }
            if (live.magUt.size >= 2) {
                val d = DoubleArray(3) { live.magUt[0][it] - live.magUt[1][it] }
                Text("Difference 1 − 2", fontWeight = FontWeight.SemiBold)
                Mono("x ${f1(d[0])}   y ${f1(d[1])}   z ${f1(d[2])}   |Δ| ${f1(norm(d))}")
            }
            if (ui.rangeG > 0) Muted("Range ±${ui.rangeG} G = ±${ui.rangeG * 100} µT")
        }

        Section("Accelerometer and gyroscope") {
            if (!ui.imu) Muted("The IMU did not answer.")
            else {
                Mono("g    x ${f2(ui.live.accG[0])}   y ${f2(ui.live.accG[1])}   z ${f2(ui.live.accG[2])}")
                Mono("°/s  x ${f1(ui.live.gyrDps[0])}   y ${f1(ui.live.gyrDps[1])}   z ${f1(ui.live.gyrDps[2])}")
            }
        }

        Section("Stream") {
            KeyValue("Rate: set / measured", "${ui.rateHz} / ${f1(ui.measuredHz)} Hz")
            KeyValue("Loss over 10 s", "${f1(ui.recentLoss * 100)}%")
            KeyValue("Lost / received since connecting", "${ui.lost} / ${ui.received}")
            if (ui.restarts > 0) KeyValue("Capsule restarts", "${ui.restarts}")
            KeyValue("Round trip time (median)", "${f1(ui.rttMs)} ms")
            KeyValue("Clock sync: spread / drift", "${f2(ui.syncResidualMs)} ms / ${f1(ui.driftPpm)} ppm")
            KeyValue("Battery", if (ui.batteryMv > 0) "${f2(ui.batteryMv / 1000.0)} V" else "—")
            if (ui.stats.isNotEmpty()) Muted(ui.stats)
        }

        Section("Motor") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(40, 100, 300).forEach { ms ->
                    OutlinedButton(onClick = { app.capsule.motor(ms) }, enabled = ui.status.linked) { Text("$ms ms") }
                }
            }
            Muted("Watch the field above during a pulse: it shows how much the motor disturbs the sensors.")
        }

        Section("Sensor settings") {
            Muted("Sampling rate")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(50, 100, 200).forEach { hz ->
                    FilterChip(selected = ui.rateHz == hz, onClick = { app.capsule.setRate(hz) }, label = { Text("$hz Hz") }, colors = chipColors(), enabled = ui.status.linked)
                }
            }
            Muted("Range")
            val l = ui.mags.any { it.chip == "QMC5883L" }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (l) listOf(8, 2) else listOf(30, 12, 8, 2)).forEach { g ->
                    FilterChip(selected = ui.rangeG == g, onClick = { app.capsule.setRange(g) }, label = { Text("±$g G") }, colors = chipColors(), enabled = ui.status.linked)
                }
            }
            Muted("Averaging" + if (l) " (OSR)" else " (OSR1/OSR2)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val opts = if (l) listOf(Triple(8, 8, "512"), Triple(4, 8, "256"), Triple(2, 8, "128"), Triple(1, 8, "64"))
                else listOf(Triple(8, 8, "8/8"), Triple(8, 4, "8/4"), Triple(4, 1, "4/1"), Triple(1, 1, "1/1"))
                opts.forEach { (a, b, label) ->
                    FilterChip(selected = ui.osr == "$a/$b", onClick = { app.capsule.setOsr(a, b) }, label = { Text(label) }, colors = chipColors(), enabled = ui.status.linked)
                }
            }
            Muted("Settings last until the capsule restarts. A trained model may fit less well after changing the range or averaging.")
        }
    }
}

private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

private fun bootText(r: String) = when (r) {
    "power_on" -> "power on"
    "reset_button" -> "reset button"
    "watchdog" -> "watchdog"
    "lockup" -> "CPU lockup"
    "software" -> "restarted by software (the stall monitor, or the reboot after flashing)"
    else -> r
}

@Composable
private fun Mono(text: String) = Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen(nav: Nav) {
    val app = App.get()
    val p = app.prefs
    val ui by app.capsule.ui.collectAsState()
    var detailed by remember { mutableStateOf(p.detailed) }
    var repo by remember { mutableStateOf(p.githubRepo) }
    var token by remember { mutableStateOf(p.githubToken) }
    var auto by remember { mutableStateOf(p.autoUpload) }
    var speak by remember { mutableStateOf(p.speakTyping) }
    var check by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    if (confirm.isNotEmpty()) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirm = "" },
        title = { Text(if (confirm == "model") "Delete the model?" else "Forget the capsule?") },
        text = { Text(if (confirm == "model") "All training touches are deleted. You will need to train again." else "The next Connect searches for a capsule again.") },
        confirmButton = { TextButton(onClick = { if (confirm == "model") app.brain.forget() else app.capsule.forget(); confirm = "" }) { Text(if (confirm == "model") "Delete" else "Forget", color = Palette.bad) } },
        dismissButton = { TextButton(onClick = { confirm = "" }) { Text("Cancel") } },
    )

    Page("Settings", nav) {
        Section {
            Row(Modifier.fillMaxWidth().toggleable(value = detailed, role = Role.Switch) { detailed = it; p.detailed = it }, verticalAlignment = Alignment.CenterVertically) {
                Column1(Modifier.weight(1f)) {
                    Text("Detailed mode")
                    Muted("Rates, losses, voltage and model confidence on the main screens.")
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = detailed, onCheckedChange = null)
            }
            Row(Modifier.fillMaxWidth().toggleable(value = speak, role = Role.Switch) { speak = it; p.speakTyping = it }, verticalAlignment = Alignment.CenterVertically) {
                Column1(Modifier.weight(1f)) {
                    Text("Speak while typing")
                    Muted("The word on space and full stop, the new word on *, \"erase\" on #.")
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = speak, onCheckedChange = null)
            }
        }
        Section("Magnet and sensors") { MagnetFields() }
        Section("Sending recordings to the repository") {
            Muted("GitHub fine-grained token: access to this repository only, permission Contents: Read and write.")
            OutlinedTextField(value = repo, onValueChange = { repo = it; p.githubRepo = it }, label = { Text("Repository") }, placeholder = { Text("owner/repo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = token, onValueChange = { token = it; p.githubToken = it }, label = { Text("Token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().toggleable(value = auto, role = Role.Switch) { auto = it; p.autoUpload = it }, verticalAlignment = Alignment.CenterVertically) {
                Text("Send right after each recording", modifier = Modifier.weight(1f))
                Switch(checked = auto, onCheckedChange = null)
            }
            OutlinedButton(onClick = {
                check = "Checking…"
                app.uploader.check { r -> check = r ?: "The repository is accessible." }
            }, modifier = Modifier.fillMaxWidth()) { Text("Check access") }
            if (check.isNotEmpty()) Text(check, color = if (check.startsWith("The repository is")) MaterialTheme.colorScheme.primary else Palette.warn)
        }
        Section("Capsule") {
            KeyValue("Remembered capsule", p.capsuleAddress.ifEmpty { "none" })
            KeyValue("Firmware", ui.firmware.ifEmpty { "—" })
            TextButton(onClick = { confirm = "capsule" }) { Text("Forget the capsule") }
        }
        Section("Model") {
            val sum by app.brain.summary.collectAsState()
            KeyValue("Training touches", "${sum.touches}")
            TextButton(onClick = { confirm = "model" }, enabled = sum.trained) { Text("Delete the model", color = Palette.bad) }
        }
        Muted("App ${BuildConfig.VERSION_NAME}")
    }
}

@Composable
private fun Column1(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(modifier) { content() }
}

// ---------------------------------------------------------------- logs

@Composable
fun LogsScreen(nav: Nav) {
    val app = App.get()
    val ctx = LocalContext.current
    var lines by remember { mutableStateOf(app.log.tail(300)) }
    var result by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { delay(2000); lines = app.log.tail(300) } }

    Page("Logs", nav) {
        Muted("Technical log: link, commands, capsule lines, errors. Useful when something goes wrong.")
        Button(onClick = {
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            val f = File(dir, "telehaptic-log.txt")
            f.writeText(app.log.allText())
            share(ctx, f, "text/plain", "Share logs")
        }, modifier = Modifier.fillMaxWidth()) { Text("Share logs") }
        OutlinedButton(onClick = {
            result = "Sending…"
            app.uploader.uploadLogs(app.log.allText()) { r -> result = r ?: "Logs are in the repository, folder logs/." }
        }, modifier = Modifier.fillMaxWidth()) { Text("Send to the repository") }
        if (result.isNotEmpty()) Muted(result)
        if (app.prefs.detailed) {
            Section {
                lines.takeLast(150).reversed().forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(vertical = 1.dp)) }
            }
        } else Muted("Log lines are shown in detailed mode (Settings).")
        Spacer(Modifier.width(1.dp))
    }
}
