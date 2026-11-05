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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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

/** Plain verdict for a share of recognised touches. The thresholds are a rule of thumb, not measured. */
fun verdict(share: Double) = when {
    share >= 0.9 -> "Good enough to type"
    share >= 0.7 -> "Usable; practise the keys that get confused"
    else -> "Not reliable yet; practise more or train again"
}

// ---------------------------------------------------------------- home cards

@Composable
fun LearnCards(nav: Nav, linked: Boolean) {
    val app = App.get()
    val s by app.brain.summary.collectAsState()
    val detailed = app.prefs.detailed
    Section("Training") {
        if (s.trained) {
            if (s.total > 0) {
                val share = s.correct.toDouble() / s.total
                Text("Recognises ${f0(share * 100)}% of quick taps", fontWeight = FontWeight.SemiBold)
                Muted(verdict(share) + if (detailed) " · ${s.correct} of ${s.total} · ${s.date}" else "")
            } else Text("Model trained", fontWeight = FontWeight.SemiBold)
            if (!linked) Muted("Connect the capsule to re-fit or train.")
            OutlinedButton(onClick = { nav.go(Screen.Retrain) }, enabled = linked, modifier = Modifier.fillMaxWidth()) { Text("Re-fit after putting the capsule on") }
            TextButton(onClick = { nav.go(Screen.Train) }, enabled = linked) { Text("Train from scratch (replaces the model)") }
        } else {
            Muted("About 4 minutes: 24 touches with a hold, then 24 quick taps. The model learns right on the phone.")
            if (!linked) Muted("Connect the capsule first.")
            Button(onClick = { nav.go(Screen.Train) }, enabled = linked, modifier = Modifier.fillMaxWidth()) { Text("Start training") }
        }
    }
    if (s.trained) Section("Typing") {
        Muted("Type without looking. The capsule clicks on every accepted touch.")
        Button(onClick = { nav.go(Screen.Typing) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Start typing") }
    }
}

// ---------------------------------------------------------------- training / re-fit

@Composable
fun TrainScreen(nav: Nav, retrain: Boolean) {
    val app = App.get()
    val u by app.training.ui.collectAsState()
    val cap by app.capsule.ui.collectAsState()
    val sum by app.brain.summary.collectAsState()
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var askCancel by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(100) } }
    // Leaving the screen in any way resets a finished result, so the next visit starts at the intro.
    DisposableEffect(Unit) { onDispose { app.training.close() } }
    val running = u.phase == Training.Phase.HOLDS || u.phase == Training.Phase.TAPS || u.phase == Training.Phase.STARTING
    BackHandler(enabled = running) { askCancel = true }
    val title = if (retrain || u.retrain) "Re-fit" else "Training"

    if (askCancel) AlertDialog(
        onDismissRequest = { askCancel = false },
        title = { Text("Stop?") },
        text = { Text("What you did in this session will be lost. Pause keeps it.") },
        confirmButton = { TextButton(onClick = { askCancel = false; app.training.cancel(); nav.back() }) { Text("Stop", color = Palette.bad) } },
        dismissButton = { TextButton(onClick = { askCancel = false }) { Text("Keep going") } },
    )

    Page(title, nav = nav, onBack = { if (running) askCancel = true else nav.back() }) {
        if (u.error.isNotEmpty()) {
            Text(u.error, color = Palette.bad)
            Button(onClick = { app.training.close(); nav.back() }) { Text("OK") }
            return@Page
        }
        when (u.phase) {
            Training.Phase.IDLE -> if (retrain) RetrainIntro(sum.weakKeys, nav) else TrainIntro(nav)
            Training.Phase.STARTING -> CircularProgressIndicator()
            Training.Phase.HOLDS, Training.Phase.TAPS -> {
                // One message about the link: the paused hint if there is one, else a short line.
                if (u.hint.isNotEmpty()) Text(u.hint, color = Palette.warn)
                else if (!cap.status.linked) Text("Capsule disconnected. Reconnecting…", color = Palette.bad)
                val part = when {
                    u.retrain -> "Quick taps"
                    u.phase == Training.Phase.HOLDS -> "Part 1 of 2 · touches with a hold"
                    else -> "Part 2 of 2 · quick taps"
                }
                val holding = u.phase == Training.Phase.TAPS || u.holdStep == Training.HoldStep.HOLD
                CueBlock(
                    progressText = "$part · ${minOf(u.index + 1, u.total)} of ${u.total}",
                    fraction = if (u.total > 0) u.index.toFloat() / u.total else 0f,
                    action = when {
                        u.paused -> "Paused"
                        u.phase == Training.Phase.TAPS -> "Touch and release"
                        u.holdStep == Training.HoldStep.HOLD -> "Hold"
                        u.holdStep == Training.HoldStep.REST -> "Release"
                        else -> "Get ready"
                    },
                    key = u.key, holding = holding,
                    recognized = u.phase == Training.Phase.TAPS && now < u.flashUntil,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { app.training.pause(!u.paused) }) { Text(if (u.paused) "Resume" else "Pause") }
                    OutlinedButton(onClick = { app.training.repeatLast() }) { Text("Redo last") }
                    if (u.phase == Training.Phase.TAPS) TextButton(onClick = { app.training.skip() }) { Text("Skip") }
                }
                OutlinedButton(onClick = { askCancel = true }, modifier = Modifier.fillMaxWidth()) { Text("Stop", color = Palette.bad) }
            }
            Training.Phase.FITTING -> {
                CircularProgressIndicator()
                Text("Training the model…")
            }
            Training.Phase.RESULT -> ResultBlock(u, nav, cap.status.linked)
        }
    }
}

/** Magnet and sensor distance: asked only when missing, otherwise one line with a way to change it. */
@Composable
fun MagnetLine(nav: Nav) {
    val app = App.get()
    if (app.prefs.magnet.isEmpty() || app.prefs.distanceMm <= 0) {
        Section("Before the first training") {
            MagnetFields()
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Muted("${Prefs.magnetTitle(app.prefs.magnet)} · sensors ${app.prefs.distanceMm} mm apart", Modifier.weight(1f))
            TextButton(onClick = { nav.go(Screen.Settings) }) { Text("Change") }
        }
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
private fun TrainIntro(nav: Nav) {
    val app = App.get()
    Section {
        Text("Part 1: 24 touches with a hold. One beep: touch and hold. Two beeps: release.")
        Text("Part 2: 24 quick taps at your own pace. The capsule clicks when a touch is accepted.")
        Muted("About 4 minutes. Hand in a comfortable position, magnet on the nail.")
    }
    MagnetLine(nav)
    val missing = readiness()
    if (missing.isNotEmpty()) Text(missing, color = Palette.warn)
    Button(onClick = { app.training.startTraining() }, enabled = missing.isEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Start") }
}

@Composable
private fun RetrainIntro(weak: List<String>, nav: Nav) {
    val app = App.get()
    val missing = readiness()
    Section {
        Text("Quick taps on every key. The model adjusts to how the capsule sits now.")
        Muted("Use it after putting the capsule on again. About a minute.")
    }
    MagnetLine(nav)
    if (missing.isNotEmpty()) Text(missing, color = Palette.warn)
    Button(onClick = { app.training.startRetrain(Keys.ALL, 2) }, enabled = missing.isEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text("Start: all keys, 2 taps each")
    }
    if (weak.isNotEmpty()) {
        val keys = weak.take(4)
        OutlinedButton(onClick = { app.training.startRetrain(keys, 4) }, enabled = missing.isEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Practise only ${keyList(keys)} (4 taps each)")
        }
    }
}

private fun keyList(k: List<String>) = if (k.size == 1) k[0] else k.dropLast(1).joinToString(", ") + " and " + k.last()

@Composable
private fun ResultBlock(u: Training.Ui, nav: Nav, linked: Boolean) {
    val app = App.get()
    val r = u.report ?: return
    val detailed = app.prefs.detailed
    if (u.before != null) {
        val was = u.before.share; val now = r.share
        Text("${f0(was * 100)}% → ${f0(now * 100)}%", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Muted(if (u.full) "Recognised taps before and after the re-fit." else "Recognised taps on the practised keys, before and after.")
        if (detailed) Muted("Before: the old model on the new taps (${u.before.correct} of ${u.before.total}). After: the new model, each tap left out of its own training (${r.correct} of ${r.total}).")
        if (!u.kept) Text("The new taps did not help, so the previous model is kept. Try again, touching the way you do when typing.", color = Palette.warn)
        else if (u.canUndo) TextButton(onClick = { app.training.undo() }) { Text("Undo the re-fit") }
    } else {
        Text("Recognises ${f0(r.share * 100)}% of quick taps", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(verdict(r.share))
        if (detailed) Muted("${r.correct} of ${r.total} quick taps the model did not see in training.")
    }
    if (u.full) ConfusionList(r)
    val weak = r.wrongKeys.take(3)
    if (weak.isNotEmpty()) {
        Button(onClick = { app.training.close(); app.training.startRetrain(weak, 4) }, enabled = linked, modifier = Modifier.fillMaxWidth()) {
            Text("Practise ${keyList(weak)} (4 taps each, about a minute)")
        }
        if (!linked) Muted("Connect the capsule to practise.")
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
                Muted(if (n == 1) "1 time" else "$n times")
            }
        }
    }
}

// ---------------------------------------------------------------- typing

@Composable
fun TypingScreen(nav: Nav) {
    val app = App.get()
    val u by app.typing.ui.collectAsState()
    val cap by app.capsule.ui.collectAsState()
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var ask by remember { mutableStateOf(false) }
    var askClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(80) } }
    DisposableEffect(Unit) {
        app.typing.start()
        onDispose { app.typing.stop() }
    }
    val leave: () -> Unit = { if (app.typing.pendingCorrections() > 0) ask = true else nav.back() }
    BackHandler { leave() }

    if (ask) AlertDialog(
        onDismissRequest = { },   // must be answered: the corrections would be lost silently otherwise
        title = { Text("Use your corrections?") },
        text = { Text((if (u.corrections == 1) "Once" else "${u.corrections} times") + " you erased a letter key and typed another one. Add these touches to training as the keys you meant?") },
        confirmButton = { TextButton(onClick = { app.typing.applyCorrections(true); ask = false; nav.back() }) { Text("Add") } },
        dismissButton = { TextButton(onClick = { app.typing.applyCorrections(false); ask = false; nav.back() }) { Text("Discard") } },
    )
    if (askClear) AlertDialog(
        onDismissRequest = { askClear = false },
        title = { Text("Clear the text?") },
        confirmButton = { TextButton(onClick = { app.typing.clear(); askClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { askClear = false }) { Text("Keep") } },
    )

    Page("Typing", nav, onBack = leave) {
        if (!cap.status.linked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Capsule not connected. You can tap the keys below.", color = Palette.warn, modifier = Modifier.weight(1f))
                TextButton(onClick = { app.capsule.connect() }) { Text("Connect") }
            }
        }
        if (u.dictError) Text("The word list could not be loaded, so words are not suggested. Details in Logs.", color = Palette.warn)
        else if (!u.ready) Muted("Loading the dictionary…")
        Section {
            Text(u.text.ifEmpty { " " }, fontSize = 28.sp, lineHeight = 36.sp, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp))
        }
        if (u.candidates.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                u.candidates.forEachIndexed { i, w ->
                    FilterChip(selected = i == u.variant % u.candidates.size, onClick = { app.typing.choose(i) },
                        label = { Text(w, fontSize = 16.sp) }, colors = chipColors())
                }
            }
        }
        HandDiagram(u.lastKey, if (now < u.flashUntil) Mark.RECOGNIZED else Mark.NONE,
            Modifier.fillMaxWidth(), letters = true, onKey = { app.typing.screenKey(it) })
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
            TextButton(onClick = { if (u.text.isNotEmpty()) askClear = true }) { Text("Clear") }
        }
        if (u.corrections > 0) Muted("Corrections collected: ${u.corrections}. You will be asked about them when you leave.")
    }
}
