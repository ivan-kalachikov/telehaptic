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

    Page("Телехаптика", nav = null, actions = {
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 22.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Эксперименты") }, onClick = { menu = false; nav.go(Screen.Experiments) })
                DropdownMenuItem(text = { Text("Диагностика") }, onClick = { menu = false; nav.go(Screen.Diagnostics) })
                DropdownMenuItem(text = { Text("Логи") }, onClick = { menu = false; nav.go(Screen.Logs) })
                DropdownMenuItem(text = { Text("Настройки") }, onClick = { menu = false; nav.go(Screen.Settings) })
            }
        }
    }) {
        Text("Капсула", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        CapsuleCard(ui, app.prefs.detailed)

        when (ui.status) {
            Capsule.Status.NO_PERMISSION -> Section("Разрешения") {
                Text("Приложению нужен доступ к «Устройствам поблизости»: так оно находит капсулу и получает от неё данные. Местоположение не используется.")
                Text("Уведомление нужно, чтобы Android не обрывал связь с капсулой, пока экран погашен.")
                Button(onClick = { askPermissions.launch(permissions) }, modifier = Modifier.fillMaxWidth()) { Text("Разрешить") }
            }
            Capsule.Status.BT_OFF -> Button(
                onClick = { ctx.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Включить Bluetooth") }
            Capsule.Status.IDLE, Capsule.Status.NOT_FOUND -> Button(
                onClick = { app.capsule.connect() }, modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (app.prefs.capsuleAddress.isEmpty()) "Найти и подключить капсулу" else "Подключить капсулу") }
            else -> {}
        }

        Section("Обучение") {
            Muted("Появится в следующей сборке. Сейчас доступны эксперименты для первой серии записей: меню ⋮ → Эксперименты.")
        }
        Section("Набор") { Muted("Появится после обучения.") }

        if (ui.status.linked || ui.status == Capsule.Status.RECONNECTING || ui.status == Capsule.Status.CONNECTING) {
            TextButton(onClick = { app.capsule.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Отключить капсулу") }
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
            if (ui.status.linked && pct >= 0) Muted(if (detailed) "${f2(ui.batteryMv / 1000.0)} В" else "заряд ≈ $pct%")
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
                    2 -> "Оба датчика на месте"
                    1 -> "Найден один датчик из двух"
                    3 -> "Найдено три датчика"
                    else -> "Жду отчёт капсулы…"
                })
            } else ui.problems.forEach { Text(it, color = Palette.warn, fontSize = 14.sp) }
            if (detailed) Muted("${f0(ui.measuredHz)} Гц · потери ${f1(ui.recentLoss * 100)}% · RTT ${f0(ui.rttMs)} мс · прошивка ${ui.firmware}")
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

    Page("Эксперименты", nav) {
        Muted("Записи для анализа. Порядок любой.")
        ExperimentCard("rotate", "Калибровка", "Без магнита. 10 с кисть неподвижна, капсула пять раз вибрирует. Потом 40 с медленно вращайте кисть во все стороны.") {
            nav.go(Screen.Setup(Kind.CALIBRATION))
        }
        ExperimentCard("table", "Клавиши", "48 касаний: 4 блока по 12 клавиш. Удержание 2,5 с, покой 2 с. Около 4 минут.") {
            nav.go(Screen.Setup(Kind.KEYS))
        }
        ExperimentCard("nomagnet", "Без магнита", "То же, что «Клавиши», но магнит снят с ногтя. Показывает, что сигнал идёт от магнита, а не от позы.") {
            nav.go(Screen.Setup(Kind.NO_MAGNET))
        }

        if (files.isNotEmpty()) {
            Section("Записи") {
                files.take(30).forEach { f -> RecordingRow(f, items[f.name], app.uploader.isSent(f)) { share(ctx, f, "application/zip", "Отправить запись") } }
                if (files.any { !app.uploader.isSent(it) && !it.name.contains("_ABORTED") }) {
                    OutlinedButton(onClick = { app.uploader.uploadPending() }, modifier = Modifier.fillMaxWidth()) { Text("Отправить неотправленные в репозиторий") }
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
            sent || item?.state == Uploader.State.SENT -> "в репозитории" to Palette.ok
            item?.state == Uploader.State.SENDING || item?.state == Uploader.State.WAITING -> "отправляю…" to Palette.warn
            item?.state == Uploader.State.FAILED -> "не отправлено" to Palette.bad
            else -> "на телефоне" to MaterialTheme.colorScheme.onSurfaceVariant
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
    val title = when (kind) { Kind.CALIBRATION -> "Калибровка"; Kind.KEYS -> "Клавиши"; Kind.NO_MAGNET -> "Без магнита" }

    Page(title, nav) {
        when (kind) {
            Kind.CALIBRATION -> Section {
                Text("Снимите магнит с ногтя.")
                Text("После сигнала 10 с держите кисть неподвижно: капсула пять раз коротко завибрирует.")
                Text("После второго сигнала 40 с медленно вращайте кисть во все стороны, как будто осматриваете её.")
            }
            Kind.NO_MAGNET -> Section { Text("Снимите магнит с ногтя. Дальше всё как в «Клавишах»: касайтесь тех же фаланг, что и с магнитом.") }
            Kind.KEYS -> Section {
                Text("Одиночный сигнал — коснитесь и держите. Двойной — отпустите и расслабьте руку.")
                Muted("Клавишу на экране и голосом показывают заранее, во время покоя.")
            }
        }

        if (kind != Kind.CALIBRATION) {
            Section("Положение руки") {
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
                    Text("После повторного надевания капсулы")
                }
            }
        }

        Section("Магнит и датчики") {
            if (kind == Kind.KEYS) {
                Muted("Какой магнит на ногте")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Prefs.MAGNETS.forEach { (code, label) ->
                        FilterChip(selected = magnet == code, onClick = { magnet = code; app.prefs.magnet = code }, label = { Text(label.substringAfter(' ')) })
                    }
                }
            }
            OutlinedTextField(
                value = distance,
                onValueChange = { v -> distance = v.filter { it.isDigit() }.take(3); app.prefs.distanceMm = distance.toIntOrNull() ?: 0 },
                label = { Text("Расстояние между датчиками, мм") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Заметка (необязательно)") }, modifier = Modifier.fillMaxWidth())
        }

        val ready = ui.status.linked && ui.mags.isNotEmpty()
        val missing = when {
            !ui.status.linked -> "Подключите капсулу на главном экране."
            ui.mags.isEmpty() -> "Капсула не нашла магнитометров. Откройте Диагностику."
            kind == Kind.KEYS && magnet.isEmpty() -> "Выберите магнит."
            distance.isEmpty() -> "Введите расстояние между датчиками."
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
        ) { Text("Начать") }
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

    Page(if (s.kind == Kind.CALIBRATION) "Калибровка" else "Запись", nav = null) {
        if (!cap.status.linked) Text("Связь с капсулой потеряна. Запись идёт, переподключаюсь.", color = Palette.bad)

        if (s.kind == Kind.CALIBRATION) {
            val (big, small) = when (s.phase) {
                Session.Phase.STARTING, Session.Phase.LEAD -> "Приготовьтесь" to "Магнит снят, кисть расслаблена"
                Session.Phase.STILL -> "Держите кисть неподвижно" to "Капсула будет коротко вибрировать"
                Session.Phase.ROTATE -> "Медленно вращайте кисть" to "Во все стороны, плавно"
                Session.Phase.SAVING -> "Сохраняю…" to ""
                else -> "" to ""
            }
            Spacer(Modifier.height(24.dp))
            PosePicture("rotate", Modifier.size(120.dp).align(Alignment.CenterHorizontally))
            Text(big, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (small.isNotEmpty()) Muted(small, Modifier.fillMaxWidth())
            if (s.phase != Session.Phase.SAVING) Text("$left с", fontSize = 40.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            val done = (s.trial + if (s.phase == Session.Phase.HOLD) 0 else 1).coerceAtLeast(0)
            LinearProgressIndicator(progress = { if (s.trials > 0) done.toFloat() / s.trials else 0f }, modifier = Modifier.fillMaxWidth())
            Muted(if (s.trial >= 0) "Касание ${s.trial + 1} из ${s.trials}" else "Приготовьтесь")
            val key = if (s.phase == Session.Phase.HOLD) s.key else s.nextKey
            val mark = when (s.phase) { Session.Phase.HOLD -> Mark.HOLD; Session.Phase.ANNOUNCE, Session.Phase.REST -> if (key.isNotEmpty()) Mark.NEXT else Mark.NONE; else -> Mark.NONE }
            HandDiagram(key, mark, Modifier.fillMaxWidth(0.9f).align(Alignment.CenterHorizontally))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(key.ifEmpty { "·" }, fontSize = 64.sp, fontWeight = FontWeight.Bold, color = keyColor())
                Spacer(Modifier.width(16.dp))
                Column {
                    if (key.isNotEmpty()) {
                        Text(Keys.finger(key) + " палец", fontWeight = FontWeight.SemiBold)
                        Muted(Keys.phalanx(key))
                    }
                }
            }
            Text(
                when (s.phase) {
                    Session.Phase.HOLD -> "Держите"
                    Session.Phase.REST, Session.Phase.ANNOUNCE -> "Отпустите. Следующая — после сигнала"
                    Session.Phase.SAVING -> "Сохраняю…"
                    else -> "Приготовьтесь"
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
        Box(contentAlignment = Alignment.Center) { Text("Остановить — удерживайте", color = Palette.bad) }
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

    Page(if (r.aborted) "Запись остановлена" else "Запись сохранена", nav = null) {
        if (r.error != null) Text(r.error, color = Palette.bad)
        Section {
            KeyValue("Длительность", "${f0(r.durationS)} с")
            KeyValue("Отсчётов", "${r.samples}")
            KeyValue("Потеряно", "${r.lost} (${f1(r.lossShare * 100)}%)")
            KeyValue("Насыщение магнитометра", "${r.saturated} отсчётов")
            if (r.disconnects > 0) KeyValue("Обрывы связи", "${r.disconnects}")
            if (r.i2cErrors > 0) KeyValue("Ошибки чтения датчиков", "${r.i2cErrors}")
        }
        if (r.lossShare > 0.02) Text("Много потерь. Держите телефон ближе к руке, без преград между ними.", color = Palette.warn)
        if (r.saturated > 0) Text("Датчик зашкаливал. Если это повторяется, увеличьте диапазон в Диагностике или возьмите магнит слабее.", color = Palette.warn)
        if (r.samples == 0L) Text("Данных нет. Проверьте связь с капсулой и повторите запись.", color = Palette.bad)

        val f = r.file
        if (f != null) {
            val item = items[f.name]
            val sent = app.uploader.isSent(f) || item?.state == Uploader.State.SENT
            when {
                sent -> Text("Отправлено в репозиторий", color = Palette.ok)
                item?.state == Uploader.State.SENDING || item?.state == Uploader.State.WAITING -> Muted("Отправляю в репозиторий…")
                item?.state == Uploader.State.FAILED -> Text(item.message, color = Palette.warn)
            }
            if (!sent) Button(onClick = { app.uploader.upload(f) }, modifier = Modifier.fillMaxWidth()) { Text("Отправить в репозиторий") }
            OutlinedButton(onClick = { share(ctx, f, "application/zip", "Отправить запись") }, modifier = Modifier.fillMaxWidth()) { Text("Поделиться") }
            Muted(f.name)
        }
        TextButton(onClick = { app.session.reset(); nav.reset(Screen.Experiments) }, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
    }
}
