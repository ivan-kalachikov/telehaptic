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
    Section("Обучение") {
        if (s.trained) {
            Text("Модель обучена", fontWeight = FontWeight.SemiBold)
            if (s.total > 0) Muted("Узнаёт ${s.correct} из ${s.total} касаний · ${s.date}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { nav.go(Screen.Retrain) }) { Text("Дообучить") }
                TextButton(onClick = { nav.go(Screen.Train) }) { Text("Обучить заново") }
            }
        } else {
            Muted("Около 4 минут: 24 касания с удержанием, потом 24 быстрых. Модель учится прямо на телефоне.")
            Button(onClick = { nav.go(Screen.Train) }, modifier = Modifier.fillMaxWidth()) { Text("Начать обучение") }
        }
    }
    Section("Набор") {
        if (s.trained) {
            Muted("Набирайте текст вслепую. Каждое принятое касание капсула отмечает щелчком.")
            Button(onClick = { nav.go(Screen.Typing) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Начать набор") }
        } else Muted("Станет доступен после обучения.")
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
    val title = if (retrain || u.retrain) "Дообучение" else "Обучение"

    Page(title, nav = if (app.training.active) null else nav) {
        if (u.error.isNotEmpty()) {
            Text(u.error, color = Palette.bad)
            Button(onClick = { app.training.close(); nav.back() }) { Text("Понятно") }
            return@Page
        }
        when (u.phase) {
            Training.Phase.IDLE -> if (retrain) RetrainIntro(sum.weakKeys) else TrainIntro()
            Training.Phase.HOLDS, Training.Phase.TAPS -> {
                if (!cap.status.linked) Text("Связь с капсулой потеряна. Переподключаюсь…", color = Palette.bad)
                LinearProgressIndicator(progress = { if (u.total > 0) u.index.toFloat() / u.total else 0f }, modifier = Modifier.fillMaxWidth())
                Muted(if (u.phase == Training.Phase.HOLDS) "С удержанием · ${u.index + 1} из ${u.total}" else "Быстрые касания · ${u.index + 1} из ${u.total}")
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
                        if (u.key.isNotEmpty()) { Text(Keys.finger(u.key) + " палец", fontWeight = FontWeight.SemiBold); Muted(Keys.phalanx(u.key)) }
                    }
                }
                Text(
                    when {
                        u.paused -> "Пауза"
                        u.phase == Training.Phase.TAPS -> "Коснитесь и отпустите"
                        u.holdStep == Training.HoldStep.HOLD -> "Держите"
                        u.holdStep == Training.HoldStep.REST -> "Отпустите"
                        else -> "Приготовьтесь"
                    }, fontSize = 20.sp,
                )
                if (u.hint.isNotEmpty()) Text(u.hint, color = Palette.warn)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { app.training.pause(!u.paused) }) { Text(if (u.paused) "Продолжить" else "Пауза") }
                    OutlinedButton(onClick = { app.training.repeatLast() }) { Text("Повторить") }
                    if (u.phase == Training.Phase.TAPS) TextButton(onClick = { app.training.skip() }) { Text("Пропустить") }
                }
                TextButton(onClick = { app.training.cancel(); nav.back() }) { Text("Прервать", color = Palette.bad) }
            }
            Training.Phase.FITTING -> {
                CircularProgressIndicator()
                Text("Обучаю модель…")
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
    Section("Магнит и датчики") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Prefs.MAGNETS.forEach { (code, label) ->
                FilterChip(selected = magnet == code, onClick = { magnet = code; app.prefs.magnet = code }, label = { Text(label.substringAfter(' ')) })
            }
        }
        OutlinedTextField(
            value = distance,
            onValueChange = { v -> distance = v.filter { it.isDigit() }.take(3); app.prefs.distanceMm = distance.toIntOrNull() ?: 0 },
            label = { Text("Расстояние между датчиками, мм") },
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
        !cap.status.linked -> "Подключите капсулу на главном экране."
        cap.mags.isEmpty() -> "Капсула не нашла магнитометров. Откройте Диагностику."
        app.prefs.magnet.isEmpty() -> "Выберите магнит."
        app.prefs.distanceMm <= 0 -> "Введите расстояние между датчиками."
        else -> ""
    }
}

@Composable
private fun TrainIntro() {
    val app = App.get()
    Section {
        Text("Сначала 24 касания с удержанием: одиночный сигнал — коснитесь и держите, двойной — отпустите.")
        Text("Потом 24 быстрых касания в своём темпе. Капсула щёлкает, когда касание принято.")
        Muted("Около 4 минут. Рука в удобном положении, магнит на ногте.")
    }
    MagnetAndDistance()
    val missing = readiness()
    if (missing.isNotEmpty()) Text(missing, color = Palette.warn)
    Button(onClick = { app.training.startTraining() }, enabled = missing.isEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Начать") }
}

@Composable
private fun RetrainIntro(weak: List<String>) {
    val app = App.get()
    val missing = readiness()
    Section {
        Text("Короткий сеанс быстрых касаний. Модель подстроится под то, как сидит капсула сейчас.")
        Muted("Подходит после повторного надевания и для клавиш, которые путаются.")
    }
    if (missing.isNotEmpty()) Text(missing, color = Palette.warn)
    Button(onClick = { app.training.startRetrain(Keys.ALL, 2) }, enabled = missing.isEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text("Все клавиши, по 2 касания (около минуты)")
    }
    if (weak.isNotEmpty()) {
        val keys = weak.take(4)
        OutlinedButton(onClick = { app.training.startRetrain(keys, 4) }, enabled = missing.isEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Только ${keys.joinToString(", ")}, по 4 касания")
        }
    }
}

@Composable
private fun ResultBlock(u: Training.Ui, nav: Nav) {
    val app = App.get()
    val r = u.report ?: return
    if (u.before != null) {
        Text("Было ${u.before.correct} из ${u.before.total} → стало ${r.correct} из ${r.total}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Muted("«Было» — старая модель на новых касаниях. «Стало» — новая модель, каждое касание проверено без него самого.")
        if (r.correct < u.before.correct) Text("Стало хуже. Попробуйте ещё раз: касайтесь так же, как при наборе.", color = Palette.warn)
    } else {
        Text("Узнаётся ${r.correct} из ${r.total} касаний", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Muted("Проверка на быстрых касаниях, которых модель не видела при обучении.")
    }
    ConfusionList(r)
    val weak = r.wrongKeys.take(3)
    if (weak.isNotEmpty()) {
        Section { Text("Совет: дообучите ${weak.joinToString(", ")} — по 4 касания, около минуты.") }
        Button(onClick = { app.training.close(); app.training.startRetrain(weak, 4) }, modifier = Modifier.fillMaxWidth()) {
            Text("Дообучить ${weak.joinToString(", ")}")
        }
    }
    OutlinedButton(onClick = { app.training.close(); nav.reset(Screen.Typing) }, modifier = Modifier.fillMaxWidth()) { Text("Перейти к набору") }
    TextButton(onClick = { app.training.close(); nav.reset(Screen.Home) }, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
}

@Composable
private fun ConfusionList(r: Evaluation.Report) {
    if (r.confusions.isEmpty()) return
    Section("Чаще всего путаются") {
        r.confusions.take(3).forEach { (a, b, n) ->
            Row(Modifier.fillMaxWidth()) {
                Text("$a и $b", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Muted(times(n))
            }
        }
    }
}

private fun times(n: Int) = when {
    n % 10 == 1 && n % 100 != 11 -> "$n раз"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "$n раза"
    else -> "$n раз"
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
    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(80) } }
    DisposableEffect(Unit) {
        app.typing.start()
        onDispose { app.typing.stop() }
    }
    BackHandler { if (app.typing.pendingCorrections() > 0) ask = true else nav.back() }

    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("Исправления") },
        text = { Text("Добавить ${u.corrections} исправлений в обучение? Это касания, которые вы стёрли и набрали другой клавишей.") },
        confirmButton = { TextButton(onClick = { app.typing.applyCorrections(true); ask = false; nav.back() }) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = { app.typing.applyCorrections(false); ask = false; nav.back() }) { Text("Не нужно") } },
    )

    Page("Набор", nav) {
        if (!cap.status.linked) Text("Капсула не подключена.", color = Palette.warn)
        if (!u.ready) Muted("Загружаю словарь…")
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
        Muted("0 — пробел · 1 — точка · * — другое слово · # — стереть")
        if (app.prefs.detailed && u.lastKey.isNotEmpty()) {
            Muted("${u.lastKey} · ${f0(u.posterior * 100)}% · второй вариант ${u.second} (${f0(u.secondPosterior * 100)}%) · не похоже ни на одну клавишу: ${u.rejected}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Телехаптика", u.text))
            }) { Text("Копировать") }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u.text), "Поделиться"))
            }) { Text("Поделиться") }
            TextButton(onClick = { app.typing.clear() }) { Text("Очистить") }
        }
        if (u.corrections > 0) Muted("Исправлений для дообучения: ${u.corrections}. Спрошу при выходе.")
    }
}
