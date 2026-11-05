@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.telehaptic.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telehaptic.app.App
import com.telehaptic.app.Nav
import com.telehaptic.app.Prefs
import com.telehaptic.app.Screen
import com.telehaptic.app.Training
import com.telehaptic.app.core.Evaluation
import com.telehaptic.app.core.Keys
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- home cards

@Composable
fun LearnCards(nav: Nav) {
    val app = App.get()
    val s by app.brain.summary.collectAsState()
    Section("Training") {
        if (s.trained) {
            Text("Model trained", fontWeight = FontWeight.SemiBold)
            if (s.total > 0) Muted("Recognises ${s.correct} of ${s.total} touches · ${s.date}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { nav.go(Screen.Retrain) }) { Text("Retrain") }
                TextButton(onClick = { nav.go(Screen.Train) }) { Text("Train from scratch") }
            }
        } else {
            Muted("About 4 minutes: 24 touches with a hold, then 24 quick taps. The model learns right on the phone.")
            Button(onClick = { nav.go(Screen.Train) }, modifier = Modifier.fillMaxWidth()) { Text("Start training") }
        }
    }
    Section("Typing") {
        if (s.trained) {
            Muted("Type without looking. The capsule clicks on every accepted touch.")
            Button(onClick = { nav.go(Screen.Typing) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Start typing") }
        } else Muted("Available after training.")
    }
}

// ---------------------------------------------------------------- training / retraining

@Composable
fun TrainScreen(nav: Nav, retrain: Boolean) {
    val app = App.get()
    val u by app.training.ui.collectAsState()
    val cap by app.capsule.ui.collectAsState()
    val sum by app.brain.summary.collectAsState()
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(100) } }
    BackHandler(enabled = app.training.active) { }
    val title = if (retrain || u.retrain) "Retraining" else "Training"

    Page(title, nav = if (app.training.active) null else nav) {
        if (u.error.isNotEmpty()) {
            Text(u.error, color = Palette.bad)
            Button(onClick = { app.training.close(); nav.back() }) { Text("OK") }
            return@Page
        }
        when (u.phase) {
            Training.Phase.IDLE -> if (retrain) RetrainIntro(sum.weakKeys) else TrainIntro()
            Training.Phase.HOLDS, Training.Phase.TAPS -> {
                if (!cap.status.linked) Text("Capsule link lost. Reconnecting…", color = Palette.bad)
                LinearProgressIndicator(progress = { if (u.total > 0) u.index.toFloat() / u.total else 0f }, modifier = Modifier.fillMaxWidth())
                Muted(if (u.phase == Training.Phase.HOLDS) "With a hold · ${u.index + 1} of ${u.total}" else "Quick taps · ${u.index + 1} of ${u.total}")
                val mark = when {
                    u.phase == Training.Phase.TAPS && now < u.flashUntil -> Mark.RECOGNIZED
                    u.phase == Training.Phase.HOLDS && u.holdStep == Training.HoldStep.HOLD -> Mark.HOLD
                    else -> Mark.NEXT
                }
                HandDiagram(u.key, mark, Modifier.fillMaxWidth(0.9f).align(Alignment.CenterHorizontally))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u.key.ifEmpty { "·" }, fontSize = 64.sp, fontWeight = FontWeight.Bold, color = keyColor())
                    Spacer(Modifier.width(16.dp))
                    Column {
                        if (u.key.isNotEmpty()) { Text(Keys.finger(u.key) + " finger", fontWeight = FontWeight.SemiBold); Muted(Keys.phalanx(u.key)) }
                    }
                }
                Text(
                    when {
                        u.paused -> "Paused"
                        u.phase == Training.Phase.TAPS -> "Touch and release"
                        u.holdStep == Training.HoldStep.HOLD -> "Hold"
                        u.holdStep == Training.HoldStep.REST -> "Release"
                        else -> "Get ready"
                    }, fontSize = 20.sp,
                )
                if (u.hint.isNotEmpty()) Text(u.hint, color = Palette.warn)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { app.training.pause(!u.paused) }) { Text(if (u.paused) "Resume" else "Pause") }
                    OutlinedButton(onClick = { app.training.repeatLast() }) { Text("Repeat") }
                    if (u.phase == Training.Phase.TAPS) TextButton(onClick = { app.training.skip() }) { Text("Skip") }
                }
                TextButton(onClick = { app.training.cancel(); nav.back() }) { Text("Cancel", color = Palette.bad) }
            }
            Training.Phase.FITTING -> {
                CircularProgressIndicator()
                Text("Training the model…")
            }
            Training.Phase.RESULT -> ResultBlock(u, nav)
        }
    }
}

@Composable
private fun MagnetAndDistance() {
    val app = App.get()
    var magnet by remember { mutableStateOf(app.prefs.magnet) }
    var distance by remember { mutableStateOf(if (app.prefs.distanceMm > 0) app.prefs.distanceMm.toString() else "") }
    Section("Magnet and sensors") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Prefs.MAGNETS.forEach { (code, label) ->
                FilterChip(selected = magnet == code, onClick = { magnet = code; app.prefs.magnet = code }, label = { Text(label.substringAfter(' ')) })
            }
        }
        OutlinedTextField(
            value = distance,
            onValueChange = { v -> distance = v.filter { it.isDigit() }.take(3); app.prefs.distanceMm = distance.toIntOrNull() ?: 0 },
            label = { Text("Distance between the sensors, mm") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun readiness(): String {
    val app = App.get()
    val cap by app.capsule.ui.collectAsState()
    return when {
        !cap.status.linked -> "Connect the capsule on the main screen."
        cap.mags.isEmpty() -> "The capsule found no magnetometers. Open Diagnostics."
        app.prefs.magnet.isEmpty() -> "Choose the magnet."
        app.prefs.distanceMm <= 0 -> "Enter the distance between the sensors."
        else -> ""
    }
}

@Composable
private fun TrainIntro() {
    val app = App.get()
    Section {
        Text("First 24 touches with a hold: one beep, touch and hold; two beeps, release.")
        Text("Then 24 quick taps at your own pace. The capsule clicks when a touch is accepted.")
        Muted("About 4 minutes. Hand in a comfortable position, magnet on the nail.")
    }
    MagnetAndDistance()
    val missing = readiness()
    if (missing.isNotEmpty()) Text(missing, color = Palette.warn)
    Button(onClick = { app.training.startTraining() }, enabled = missing.isEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Start") }
}

@Composable
private fun RetrainIntro(weak: List<String>) {
    val app = App.get()
    val missing = readiness()
    Section {
        Text("A short session of quick taps. The model adjusts to how the capsule sits now.")
        Muted("Use it after putting the capsule on again and for keys that get confused.")
    }
    if (missing.isNotEmpty()) Text(missing, color = Palette.warn)
    Button(onClick = { app.training.startRetrain(Keys.ALL, 2) }, enabled = missing.isEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text("All keys, 2 taps each (about a minute)")
    }
    if (weak.isNotEmpty()) {
        val keys = weak.take(4)
        OutlinedButton(onClick = { app.training.startRetrain(keys, 4) }, enabled = missing.isEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Only ${keys.joinToString(", ")}, 4 taps each")
        }
    }
}

@Composable
private fun ResultBlock(u: Training.Ui, nav: Nav) {
    val app = App.get()
    val r = u.report ?: return
    if (u.before != null) {
        Text("Was ${u.before.correct} of ${u.before.total} → now ${r.correct} of ${r.total}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Muted("Was: the old model on the new taps. Now: the new model, each tap checked without itself in training.")
        if (r.correct < u.before.correct) Text("It got worse. Try again, touching the way you do when typing.", color = Palette.warn)
    } else {
        Text("Recognises ${r.correct} of ${r.total} touches", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Muted("Checked on quick taps the model did not see in training.")
    }
    ConfusionList(r)
    val weak = r.wrongKeys.take(3)
    if (weak.isNotEmpty()) {
        Section { Text("Tip: retrain ${weak.joinToString(", ")}, 4 taps each, about a minute.") }
        Button(onClick = { app.training.close(); app.training.startRetrain(weak, 4) }, modifier = Modifier.fillMaxWidth()) {
            Text("Retrain ${weak.joinToString(", ")}")
        }
    }
    OutlinedButton(onClick = { app.training.close(); nav.reset(Screen.Typing) }, modifier = Modifier.fillMaxWidth()) { Text("Go to typing") }
    TextButton(onClick = { app.training.close(); nav.reset(Screen.Home) }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun ConfusionList(r: Evaluation.Report) {
    if (r.confusions.isEmpty()) return
    Section("Most often confused") {
        r.confusions.take(3).forEach { (a, b, n) ->
            Row(Modifier.fillMaxWidth()) {
                Text("$a and $b", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Muted(times(n))
            }
        }
    }
}

private fun times(n: Int) = if (n == 1) "1 time" else "$n times"

// ---------------------------------------------------------------- typing

@Composable
fun TypingScreen(nav: Nav) {
    val app = App.get()
    val u by app.typing.ui.collectAsState()
    val cap by app.capsule.ui.collectAsState()
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var ask by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(80) } }
    DisposableEffect(Unit) {
        app.typing.start()
        onDispose { app.typing.stop() }
    }
    BackHandler { if (app.typing.pendingCorrections() > 0) ask = true else nav.back() }

    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("Corrections") },
        text = { Text("Add ${u.corrections} corrections to training? These are touches you erased and typed again as another key.") },
        confirmButton = { TextButton(onClick = { app.typing.applyCorrections(true); ask = false; nav.back() }) { Text("Add") } },
        dismissButton = { TextButton(onClick = { app.typing.applyCorrections(false); ask = false; nav.back() }) { Text("No") } },
    )

    Page("Typing", nav) {
        if (!cap.status.linked) Text("Capsule not connected.", color = Palette.warn)
        if (!u.ready) Muted("Loading the dictionary…")
        Section {
            Text(u.text.ifEmpty { " " }, fontSize = 28.sp, lineHeight = 36.sp, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp))
        }
        if (u.candidates.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                u.candidates.forEachIndexed { i, w ->
                    FilterChip(selected = i == u.variant % u.candidates.size, onClick = { app.typing.choose(i) }, label = { Text(w, fontSize = 16.sp) })
                }
            }
        }
        HandDiagram(u.lastKey, if (now < u.flashUntil) Mark.RECOGNIZED else Mark.NONE, Modifier.fillMaxWidth(0.75f).align(Alignment.CenterHorizontally))
        Muted("0 space · 1 full stop · * next word · # erase")
        if (app.prefs.detailed && u.lastKey.isNotEmpty()) {
            Muted("${u.lastKey} · ${f0(u.posterior * 100)}% · runner-up ${u.second} (${f0(u.secondPosterior * 100)}%) · not like any key: ${u.rejected}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Telehaptic", u.text))
            }) { Text("Copy") }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u.text), "Share"))
            }) { Text("Share") }
            TextButton(onClick = { app.typing.clear() }) { Text("Clear") }
        }
        if (u.corrections > 0) Muted("Corrections for retraining: ${u.corrections}. I will ask when you leave.")
    }
}
