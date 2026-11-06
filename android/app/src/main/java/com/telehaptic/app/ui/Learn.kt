@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.telehaptic.app.ui

import androidx.compose.material3.MaterialTheme
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
    // The capsule sits a little differently every time it is put on: until a re-fit or a training in
    // this run of the app, the re-fit is the main action and typing comes second.
    val fresh = s.savedMs > 0 && s.savedMs >= appStartMs
    Section("Training") {
        if (s.trained) {
            if (s.total > 0) {
                val share = s.correct.toDouble() / s.total
                Text("Recognises ${f0(share * 100)}% of quick taps", fontWeight = FontWeight.SemiBold)
                Muted(verdict(share) + " · " + age(s.savedMs) + if (detailed) " · ${s.correct} of ${s.total}" else "")
            } else Text("Model trained · ${age(s.savedMs)}", fontWeight = FontWeight.SemiBold)
            if (!linked) Muted("Connect the capsule to re-fit or train.")
            if (!fresh) {
                Button(onClick = { nav.go(Screen.Retrain) }, enabled = linked, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Capsule put on again? Re-fit, about a minute") }
            } else {
                OutlinedButton(onClick = { nav.go(Screen.Retrain) }, enabled = linked, modifier = Modifier.fillMaxWidth()) { Text("Re-fit, about a minute") }
            }
            TextButton(onClick = { nav.go(Screen.Train) }, enabled = linked) { Text("Train from scratch (replaces the model)") }
        } else {
            Text("Type with your thumb on the segments of your fingers, laid out like a phone keypad. First the phone learns how your hand feels each key.")
            Muted("About 4 minutes: 24 touches with a hold, then 24 quick taps. The model learns right on the phone.")
            if (!linked) Muted("Connect the capsule first.")
            Button(onClick = { nav.go(Screen.Train) }, enabled = linked, modifier = Modifier.fillMaxWidth()) { Text("Start training") }
        }
    }
    if (s.trained) Section("Typing") {
        Muted("Type without looking: a short click for every key read, a long buzz for a touch not like any key. The phone says each word on space.")
        if (fresh) Button(onClick = { nav.go(Screen.Typing) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Start typing") }
        else OutlinedButton(onClick = { nav.go(Screen.Typing) }, modifier = Modifier.fillMaxWidth()) { Text("Start typing") }
    }
}

/** When this run of the app started: a model saved after it was fitted to how the capsule sits now. */
val appStartMs = System.currentTimeMillis()

/** "trained today, 14:05" / "trained yesterday" / "trained 3 days ago". */
fun age(ms: Long): String {
    if (ms <= 0) return "trained earlier"
    val cal = java.util.Calendar.getInstance()
    fun day(t: Long) = cal.apply { timeInMillis = t }.let { it.get(java.util.Calendar.YEAR) * 400 + it.get(java.util.Calendar.DAY_OF_YEAR) }
    val days = day(System.currentTimeMillis()) - day(ms)
    return when {
        days <= 0 -> "trained today, " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(ms))
        days == 1 -> "trained yesterday"
        else -> "trained $days days ago"
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
    val title = when { u.practice -> "Practice"; retrain || u.retrain -> "Re-fit"; else -> "Training" }

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
            Training.Phase.IDLE -> if (retrain) RetrainIntro(practiseKeys(Evaluation.Report(sum.correct, sum.total, sum.confusions, sum.weakKeys)), nav) else TrainIntro(nav)
            Training.Phase.STARTING -> CircularProgressIndicator()
            Training.Phase.HOLDS, Training.Phase.TAPS -> {
                // One message about the link: the paused hint if there is one, else a short line.
                if (u.hint.isNotEmpty()) Text(u.hint, color = Palette.warn)
                else if (!cap.status.linked) Text("Capsule lost. Reconnecting…", color = Palette.bad)
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { app.training.pause(!u.paused) }, enabled = !u.paused || cap.status == com.telehaptic.app.Capsule.Status.CONNECTED) {
                        Text(if (u.paused) "Resume" else "Pause")
                    }
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
fun MagnetLine(nav: Nav, showMagnet: Boolean = true) {
    val app = App.get()
    if ((showMagnet && app.prefs.magnet.isEmpty()) || app.prefs.distanceMm <= 0) {
        Section("Before the first training") {
            MagnetFields()
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Muted((if (showMagnet) "${Prefs.magnetTitle(app.prefs.magnet)} · " else "") + "sensors ${app.prefs.distanceMm} mm apart", Modifier.weight(1f))
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
        Text("Part 1: 24 touches with a hold. The phone says the key, then one beep: touch it and hold. Two beeps: release.")
        Text("Part 2: 24 quick taps at your own pace. The capsule clicks when a touch is accepted.")
        Muted("About 4 minutes. Hand in a comfortable position, magnet on the nail.")
        HandDiagram("", Mark.NONE, Modifier.fillMaxWidth(), letters = true)
        Muted("Index finger 1 2 3, middle 4 5 6, ring 7 8 9, little finger * 0 #; the segment near the palm first.")
    }
    MagnetLine(nav)
    VoiceWarning()
    val missing = readiness()
    if (missing.isNotEmpty()) Text(missing, color = Palette.warn)
    Button(onClick = { app.training.startTraining() }, enabled = missing.isEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Start") }
}

@Composable
private fun RetrainIntro(weak: List<String>, nav: Nav) {
    val app = App.get()
    val missing = readiness()
    Section {
        Text("Quick taps on every key, so the model adjusts to how the capsule sits now.")
        Muted("Use it after putting the capsule on again. About a minute; if the result is worse, the earlier model is kept.")
    }
    MagnetLine(nav)
    VoiceWarning()
    if (missing.isNotEmpty()) Text(missing, color = Palette.warn)
    Button(onClick = { app.training.startRetrain(Keys.ALL, 2) }, enabled = missing.isEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text("Start: all keys, 2 taps each")
    }
    if (weak.isNotEmpty()) {
        val keys = weak
        OutlinedButton(onClick = { app.training.startRetrain(keys, 4) }, enabled = missing.isEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Practise only ${keyList(keys)} · 4 taps each")
        }
    }
}

/** Both keys of the most confused pairs (up to 4 keys): practising one side of a pair does not separate them. */
fun practiseKeys(r: Evaluation.Report): List<String> {
    val out = LinkedHashSet<String>()
    for ((a, b, _) in r.confusions) { if (out.size >= 4) break; out.add(a); if (out.size < 4) out.add(b) }
    if (out.isEmpty()) out.addAll(r.wrongKeys.take(4))
    return out.toList()
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
        if (u.undone) Text("Re-fit undone: the earlier model is back.", color = MaterialTheme.colorScheme.primary)
        else if (!u.kept) Text("The new taps did not help, so the previous model is kept. Try again, touching the way you do when typing.", color = Palette.warn)
        else if (u.canUndo) TextButton(onClick = { app.training.undo() }) { Text("Undo the re-fit") }
    } else {
        Text("Recognises ${f0(r.share * 100)}% of quick taps", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(verdict(r.share))
        if (detailed) Muted("${r.correct} of ${r.total} quick taps the model did not see in training.")
    }
    if (u.full) ConfusionList(r)
    val weak = practiseKeys(r)
    if (weak.isNotEmpty()) {
        Button(onClick = { app.training.close(); app.training.startRetrain(weak, 4) }, enabled = linked, modifier = Modifier.fillMaxWidth()) {
            Text("Practise ${keyList(weak)} · about a minute")
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
        onDismissRequest = { ask = false },   // stays in typing; asked again on leaving
        title = { Text("Teach the model your corrections?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text((if (u.corrections == 1) "1 touch looks" else "${u.corrections} touches look") + " misread: you erased a letter right away and typed another key.")
            Muted("Add them only if the phone read the wrong key. If you erased to change the word, they would teach the model wrong keys.")
        } },
        confirmButton = { TextButton(onClick = { app.typing.applyCorrections(true); ask = false; nav.back() }) { Text("Add to training") } },
        dismissButton = { TextButton(onClick = { app.typing.applyCorrections(false); ask = false; nav.back() }) { Text("Leave without") } },
    )
    if (askClear) AlertDialog(
        onDismissRequest = { askClear = false },
        title = { Text("Clear the text?") },
        confirmButton = { TextButton(onClick = { app.typing.clear(); askClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { askClear = false }) { Text("Keep") } },
    )

    Page("Typing", nav, onBack = leave) {
        when (cap.status) {
            com.telehaptic.app.Capsule.Status.CONNECTED -> {}
            com.telehaptic.app.Capsule.Status.NO_DATA -> Text("No data from the capsule. Waiting…", color = Palette.warn)
            com.telehaptic.app.Capsule.Status.RECONNECTING, com.telehaptic.app.Capsule.Status.CONNECTING, com.telehaptic.app.Capsule.Status.SEARCHING ->
                Text("Capsule lost. Reconnecting… Meanwhile you can tap the keys below.", color = Palette.warn)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Capsule not connected. You can tap the keys below.", color = Palette.warn, modifier = Modifier.weight(1f))
                TextButton(onClick = { app.capsule.connect() }) { Text("Connect") }
            }
        }
        VoiceWarning()
        Muted("Start with the hand relaxed and the thumb away from the fingers for a second.")
        if (u.dictError) Text("The word list could not be loaded: the digits are shown as typed, without words. Details in Logs.", color = Palette.warn)
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
        // The last key stays outlined until the next one, so it can be read from a distance.
        HandDiagram(u.lastKey, if (now < u.flashUntil) Mark.RECOGNIZED else Mark.LAST,
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
