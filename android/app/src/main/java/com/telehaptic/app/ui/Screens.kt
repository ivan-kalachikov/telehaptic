@file:OptIn(ExperimentalMaterial3Api::class)

package com.telehaptic.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telehaptic.app.App
import com.telehaptic.app.Capsule
import com.telehaptic.app.Nav
import com.telehaptic.app.Prefs
import com.telehaptic.app.Screen
import com.telehaptic.app.Session
import com.telehaptic.app.Uploader
import com.telehaptic.app.core.Keys
import com.telehaptic.app.core.Kind
import com.telehaptic.app.core.Posture
import kotlinx.coroutines.delay
import java.io.File

// ---------------------------------------------------------------- home

@Composable
fun HomeScreen(nav: Nav) {
    val app = App.get()
    val ui by app.capsule.ui.collectAsState()
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        app.log.i("perm", res.toString())
        app.capsule.onUiResume()
        if (app.capsule.hasPermissions()) app.capsule.connect()
    }
    val permissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)

    Page("Telehaptic", nav = null, actions = {
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 22.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Experiments") }, onClick = { menu = false; nav.go(Screen.Experiments) })
                DropdownMenuItem(text = { Text("Diagnostics") }, onClick = { menu = false; nav.go(Screen.Diagnostics) })
                DropdownMenuItem(text = { Text("Logs") }, onClick = { menu = false; nav.go(Screen.Logs) })
                DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; nav.go(Screen.Settings) })
            }
        }
    }) {
        Text("Capsule", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        CapsuleCard(ui, app.prefs.detailed)

        when (ui.status) {
            Capsule.Status.NO_PERMISSION -> Section("Permissions") {
                Text("The app needs Nearby devices access to find the capsule and receive its data. Location is not used.")
                Text("The notification keeps Android from dropping the capsule link while the screen is off.")
                Button(onClick = { askPermissions.launch(permissions) }, modifier = Modifier.fillMaxWidth()) { Text("Allow") }
            }
            Capsule.Status.BT_OFF -> Button(
                onClick = { ctx.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Turn on Bluetooth") }
            Capsule.Status.IDLE, Capsule.Status.NOT_FOUND -> Button(
                onClick = { app.capsule.connect() }, modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (app.prefs.capsuleAddress.isEmpty()) "Find and connect the capsule" else "Connect the capsule") }
            else -> {}
        }

        LearnCards(nav)

        if (ui.status.linked || ui.status == Capsule.Status.RECONNECTING || ui.status == Capsule.Status.CONNECTING) {
            TextButton(onClick = { app.capsule.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Disconnect the capsule") }
        }
    }
}

@Composable
fun CapsuleCard(ui: Capsule.Ui, detailed: Boolean) {
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(statusColor(ui.status, ui.problems.isNotEmpty()))
            Spacer(Modifier.width(8.dp))
            Text(statusTitle(ui.status), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val pct = batteryPercent(ui.batteryMv)
            if (ui.status.linked && pct >= 0) Muted(if (detailed) "${f2(ui.batteryMv / 1000.0)} V" else "battery ≈ $pct%")
        }
        val hint = statusHint(ui.status)
        if (hint.isNotEmpty()) Muted(hint)
        if (ui.status == Capsule.Status.SEARCHING || ui.status == Capsule.Status.CONNECTING) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (ui.status.linked) {
            if (ui.problems.isEmpty()) {
                val n = ui.mags.size
                Muted(when (n) {
                    2 -> "Both sensors in place"
                    1 -> "Only one of two sensors found"
                    3 -> "Three sensors found"
                    else -> "Waiting for the capsule report…"
                })
            } else ui.problems.forEach { Text(it, color = Palette.warn, fontSize = 14.sp) }
            if (detailed) Muted("${f0(ui.measuredHz)} Hz · loss ${f1(ui.recentLoss * 100)}% · RTT ${f0(ui.rttMs)} ms · firmware ${ui.firmware}")
        }
    }
}

// ---------------------------------------------------------------- experiments

@Composable
fun ExperimentsScreen(nav: Nav) {
    val app = App.get()
    val items by app.uploader.items.collectAsState()
    var files by remember { mutableStateOf(app.uploader.list()) }
    LaunchedEffect(items) { files = app.uploader.list() }
    val ctx = LocalContext.current

    Page("Experiments", nav) {
        Muted("Recordings for the analysis. Any order.")
        ExperimentCard("rotate", "Calibration", "No magnet. 10 s with the hand still while the capsule vibrates five times, then 40 s of slowly turning the hand in all directions.") {
            nav.go(Screen.Setup(Kind.CALIBRATION))
        }
        ExperimentCard("table", "Keys", "48 touches: 4 blocks of 12 keys. Hold 2.5 s, rest 2 s. About 4 minutes.") {
            nav.go(Screen.Setup(Kind.KEYS))
        }
        ExperimentCard("nomagnet", "No magnet", "Same as Keys, with the magnet taken off the nail. Shows that the signal comes from the magnet, not from the hand posture.") {
            nav.go(Screen.Setup(Kind.NO_MAGNET))
        }

        if (files.isNotEmpty()) {
            Section("Recordings") {
                files.take(30).forEach { f -> RecordingRow(f, items[f.name], app.uploader.isSent(f)) { share(ctx, f, "application/zip", "Send recording") } }
                if (files.any { !app.uploader.isSent(it) && !it.name.contains("_ABORTED") }) {
                    OutlinedButton(onClick = { app.uploader.uploadPending() }, modifier = Modifier.fillMaxWidth()) { Text("Send the rest to the repository") }
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(f: File, item: Uploader.Item?, sent: Boolean, onShare: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onShare() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(f.nameWithoutExtension, fontSize = 13.sp, modifier = Modifier.weight(1f))
        val (text, color) = when {
            sent || item?.state == Uploader.State.SENT -> "in the repository" to Palette.ok
            item?.state == Uploader.State.SENDING || item?.state == Uploader.State.WAITING -> "sending…" to Palette.warn
            item?.state == Uploader.State.FAILED -> "not sent" to Palette.bad
            else -> "on the phone" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(text, color = color, fontSize = 12.sp)
    }
}

@Composable
private fun ExperimentCard(picture: String, title: String, text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            PosePicture(picture, Modifier.size(64.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Muted(text)
            }
        }
    }
}

// ---------------------------------------------------------------- setup

@Composable
fun SetupScreen(nav: Nav, kind: Kind) {
    val app = App.get()
    val ui by app.capsule.ui.collectAsState()
    var posture by remember { mutableStateOf(Posture.TABLE) }
    var rewear by remember { mutableStateOf(false) }
    var magnet by remember { mutableStateOf(app.prefs.magnet) }
    var distance by remember { mutableStateOf(if (app.prefs.distanceMm > 0) app.prefs.distanceMm.toString() else "") }
    var note by remember { mutableStateOf("") }
    val title = when (kind) { Kind.CALIBRATION -> "Calibration"; Kind.NO_MAGNET -> "No magnet"; else -> "Keys" }

    Page(title, nav) {
        when (kind) {
            Kind.CALIBRATION -> Section {
                Text("Take the magnet off the nail.")
                Text("After the beep keep the hand still for 10 s: the capsule vibrates briefly five times.")
                Text("After the second beep slowly turn the hand in all directions for 40 s, as if looking it over.")
            }
            Kind.NO_MAGNET -> Section { Text("Take the magnet off the nail. The rest is as in Keys: touch the same phalanges as with the magnet.") }
            else -> Section {
                Text("One beep: touch and hold. Two beeps: release and relax the hand.")
                Muted("The next key is shown and spoken in advance, during the rest.")
            }
        }

        if (kind != Kind.CALIBRATION) {
            Section("Hand posture") {
                Posture.entries.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { posture = p }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        PosePicture(p.code, Modifier.size(48.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(p.title, modifier = Modifier.weight(1f), fontWeight = if (p == posture) FontWeight.SemiBold else FontWeight.Normal)
                        if (p == posture) Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { rewear = !rewear }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rewear, onCheckedChange = { rewear = it })
                    Text("After putting the capsule on again")
                }
            }
        }

        Section("Magnet and sensors") {
            if (kind == Kind.KEYS) {
                Muted("Magnet on the nail")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Prefs.MAGNETS.forEach { (code, label) ->
                        FilterChip(selected = magnet == code, onClick = { magnet = code; app.prefs.magnet = code }, label = { Text(label.substringAfter(' ')) })
                    }
                }
            }
            OutlinedTextField(
                value = distance,
                onValueChange = { v -> distance = v.filter { it.isDigit() }.take(3); app.prefs.distanceMm = distance.toIntOrNull() ?: 0 },
                label = { Text("Distance between the sensors, mm") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
        }

        val ready = ui.status.linked && ui.mags.isNotEmpty()
        val missing = when {
            !ui.status.linked -> "Connect the capsule on the main screen."
            ui.mags.isEmpty() -> "The capsule found no magnetometers. Open Diagnostics."
            kind == Kind.KEYS && magnet.isEmpty() -> "Choose the magnet."
            distance.isEmpty() -> "Enter the distance between the sensors."
            else -> ""
        }
        if (ui.problems.isNotEmpty()) ui.problems.forEach { Text(it, color = Palette.warn, fontSize = 14.sp) }
        if (missing.isNotEmpty()) Text(missing, color = Palette.warn)
        Button(
            onClick = {
                app.session.start(kind, if (kind == Kind.CALIBRATION) null else posture, rewear, note)
                nav.go(Screen.Run)
            },
            enabled = ready && missing.isEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Start") }
    }
}

// ---------------------------------------------------------------- run

@Composable
fun RunScreen(nav: Nav) {
    val app = App.get()
    val s by app.session.ui.collectAsState()
    val cap by app.capsule.ui.collectAsState()
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(100) } }
    val left = ((s.phaseEndsAt - now).coerceAtLeast(0) + 999) / 1000

    Page(if (s.kind == Kind.CALIBRATION) "Calibration" else "Recording", nav = null) {
        if (!cap.status.linked) Text("Capsule link lost. Still recording, reconnecting.", color = Palette.bad)

        if (s.kind == Kind.CALIBRATION) {
            val (big, small) = when (s.phase) {
                Session.Phase.STARTING, Session.Phase.LEAD -> "Get ready" to "Magnet off, hand relaxed"
                Session.Phase.STILL -> "Keep the hand still" to "The capsule will vibrate briefly"
                Session.Phase.ROTATE -> "Slowly turn the hand" to "In all directions, smoothly"
                Session.Phase.SAVING -> "Saving…" to ""
                else -> "" to ""
            }
            Spacer(Modifier.height(24.dp))
            PosePicture("rotate", Modifier.size(120.dp).align(Alignment.CenterHorizontally))
            Text(big, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (small.isNotEmpty()) Muted(small, Modifier.fillMaxWidth())
            if (s.phase != Session.Phase.SAVING) Text("$left s", fontSize = 40.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            val done = (s.trial + if (s.phase == Session.Phase.HOLD) 0 else 1).coerceAtLeast(0)
            LinearProgressIndicator(progress = { if (s.trials > 0) done.toFloat() / s.trials else 0f }, modifier = Modifier.fillMaxWidth())
            Muted(if (s.trial >= 0) "Touch ${s.trial + 1} of ${s.trials}" else "Get ready")
            val key = if (s.phase == Session.Phase.HOLD) s.key else s.nextKey
            val mark = when (s.phase) { Session.Phase.HOLD -> Mark.HOLD; Session.Phase.ANNOUNCE, Session.Phase.REST -> if (key.isNotEmpty()) Mark.NEXT else Mark.NONE; else -> Mark.NONE }
            HandDiagram(key, mark, Modifier.fillMaxWidth(0.9f).align(Alignment.CenterHorizontally))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(key.ifEmpty { "·" }, fontSize = 64.sp, fontWeight = FontWeight.Bold, color = keyColor())
                Spacer(Modifier.width(16.dp))
                Column {
                    if (key.isNotEmpty()) {
                        Text(Keys.finger(key) + " finger", fontWeight = FontWeight.SemiBold)
                        Muted(Keys.phalanx(key))
                    }
                }
            }
            Text(
                when (s.phase) {
                    Session.Phase.HOLD -> "Hold"
                    Session.Phase.REST, Session.Phase.ANNOUNCE -> "Release. Next one after the beep"
                    Session.Phase.SAVING -> "Saving…"
                    else -> "Get ready"
                },
                fontSize = 20.sp,
            )
        }

        if (s.phase == Session.Phase.SAVING) CircularProgressIndicator()
        else StopButton { app.session.stop() }
    }
}

@Composable
private fun StopButton(onStop: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().height(52.dp).pointerInput(Unit) { detectTapGestures(onLongPress = { onStop() }) },
    ) {
        Box(contentAlignment = Alignment.Center) { Text("Stop: press and hold", color = Palette.bad) }
    }
}

// ---------------------------------------------------------------- done

@Composable
fun DoneScreen(nav: Nav) {
    val app = App.get()
    val s by app.session.ui.collectAsState()
    val items by app.uploader.items.collectAsState()
    val ctx = LocalContext.current
    val r = s.result
    if (r == null) {
        LaunchedEffect(Unit) { nav.reset(Screen.Experiments) }
        return
    }

    Page(if (r.aborted) "Recording stopped" else "Recording saved", nav = null) {
        if (r.error != null) Text(r.error, color = Palette.bad)
        Section {
            KeyValue("Duration", "${f0(r.durationS)} s")
            KeyValue("Samples", "${r.samples}")
            KeyValue("Lost", "${r.lost} (${f1(r.lossShare * 100)}%)")
            KeyValue("Magnetometer saturation", "${r.saturated} samples")
            if (r.disconnects > 0) KeyValue("Disconnects", "${r.disconnects}")
            if (r.i2cErrors > 0) KeyValue("Sensor read errors", "${r.i2cErrors}")
        }
        if (r.lossShare > 0.02) Text("Many samples lost. Keep the phone closer to the hand, with nothing in between.", color = Palette.warn)
        if (r.saturated > 0) Text("A sensor saturated. If this repeats, choose a wider range in Diagnostics or a weaker magnet.", color = Palette.warn)
        if (r.samples == 0L) Text("No data. Check the capsule link and record again.", color = Palette.bad)

        val f = r.file
        if (f != null) {
            val item = items[f.name]
            val sent = app.uploader.isSent(f) || item?.state == Uploader.State.SENT
            when {
                sent -> Text("Sent to the repository", color = Palette.ok)
                item?.state == Uploader.State.SENDING || item?.state == Uploader.State.WAITING -> Muted("Sending to the repository…")
                item?.state == Uploader.State.FAILED -> Text(item.message, color = Palette.warn)
            }
            if (!sent) Button(onClick = { app.uploader.upload(f) }, modifier = Modifier.fillMaxWidth()) { Text("Send to the repository") }
            OutlinedButton(onClick = { share(ctx, f, "application/zip", "Send recording") }, modifier = Modifier.fillMaxWidth()) { Text("Share") }
            Muted(f.name)
        }
        TextButton(onClick = { app.session.reset(); nav.reset(Screen.Experiments) }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}
