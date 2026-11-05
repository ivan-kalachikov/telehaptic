@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.telehaptic.app.ui

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

    Page("Диагностика", nav) {
        CapsuleCard(ui, detailed = true)
        if (!ui.status.linked) {
            Button(onClick = { app.capsule.connect() }, modifier = Modifier.fillMaxWidth()) { Text("Подключить капсулу") }
        }

        Section("Скан шины") {
            if (ui.scan.isEmpty()) Muted("Отчёта ещё нет.")
            ui.scan.forEach { Text(ScanText.describe(it), fontSize = 14.sp) }
            if (ui.bootReason.isNotEmpty()) Muted("Прошивка ${ui.firmware}, последний запуск: ${bootText(ui.bootReason)}")
            OutlinedButton(onClick = { app.capsule.rescan() }, enabled = ui.status.linked, modifier = Modifier.fillMaxWidth()) { Text("Пересканировать") }
        }

        Section("Поле, мкТл") {
            val live = ui.live
            if (live.magUt.isEmpty()) Muted("Нет данных.")
            live.magUt.forEachIndexed { i, v ->
                val m = ui.mags.getOrNull(i)
                Text("Датчик ${i + 1}" + (m?.let { " · ${it.chip} · ${it.where}" } ?: "") + if (live.sat.getOrElse(i) { false }) " · НАСЫЩЕНИЕ" else "",
                    fontWeight = FontWeight.SemiBold, color = if (live.sat.getOrElse(i) { false }) Palette.bad else androidx.compose.ui.graphics.Color.Unspecified)
                Mono("x ${f1(v[0])}   y ${f1(v[1])}   z ${f1(v[2])}   |B| ${f1(norm(v))}")
            }
            if (live.magUt.size >= 2) {
                val d = DoubleArray(3) { live.magUt[0][it] - live.magUt[1][it] }
                Text("Разность 1 − 2", fontWeight = FontWeight.SemiBold)
                Mono("x ${f1(d[0])}   y ${f1(d[1])}   z ${f1(d[2])}   |Δ| ${f1(norm(d))}")
            }
            if (ui.rangeG > 0) Muted("Диапазон ±${ui.rangeG} Гс = ±${ui.rangeG * 100} мкТл")
        }

        Section("Акселерометр и гироскоп") {
            if (!ui.imu) Muted("IMU не ответил.")
            else {
                Mono("g    x ${f2(ui.live.accG[0])}   y ${f2(ui.live.accG[1])}   z ${f2(ui.live.accG[2])}")
                Mono("°/с  x ${f1(ui.live.gyrDps[0])}   y ${f1(ui.live.gyrDps[1])}   z ${f1(ui.live.gyrDps[2])}")
            }
        }

        Section("Поток") {
            KeyValue("Частота: задана / реальная", "${ui.rateHz} / ${f1(ui.measuredHz)} Гц")
            KeyValue("Потери за 10 с", "${f1(ui.recentLoss * 100)}%")
            KeyValue("Потеряно / принято с подключения", "${ui.lost} / ${ui.received}")
            if (ui.restarts > 0) KeyValue("Перезапуски капсулы", "${ui.restarts}")
            KeyValue("Время туда-обратно (медиана)", "${f1(ui.rttMs)} мс")
            KeyValue("Сведение часов: разброс / дрейф", "${f2(ui.syncResidualMs)} мс / ${f1(ui.driftPpm)} ppm")
            KeyValue("Батарея", if (ui.batteryMv > 0) "${f2(ui.batteryMv / 1000.0)} В" else "—")
            if (ui.stats.isNotEmpty()) Muted(ui.stats)
        }

        Section("Мотор") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(40, 100, 300).forEach { ms ->
                    OutlinedButton(onClick = { app.capsule.motor(ms) }, enabled = ui.status.linked) { Text("$ms мс") }
                }
            }
            Muted("Во время импульса смотрите на поле выше: так видно, насколько мотор мешает датчикам.")
        }

        Section("Настройки датчиков") {
            Muted("Частота опроса")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(50, 100, 200).forEach { hz ->
                    FilterChip(selected = ui.rateHz == hz, onClick = { app.capsule.setRate(hz) }, label = { Text("$hz Гц") }, enabled = ui.status.linked)
                }
            }
            Muted("Диапазон")
            val l = ui.mags.any { it.chip == "QMC5883L" }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (l) listOf(8, 2) else listOf(30, 12, 8, 2)).forEach { g ->
                    FilterChip(selected = ui.rangeG == g, onClick = { app.capsule.setRange(g) }, label = { Text("±$g Гс") }, enabled = ui.status.linked)
                }
            }
            Muted("Усреднение" + if (l) " (OSR)" else " (OSR1/OSR2)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val opts = if (l) listOf(Triple(8, 8, "512"), Triple(4, 8, "256"), Triple(2, 8, "128"), Triple(1, 8, "64"))
                else listOf(Triple(8, 8, "8/8"), Triple(8, 4, "8/4"), Triple(4, 1, "4/1"), Triple(1, 1, "1/1"))
                opts.forEach { (a, b, label) ->
                    FilterChip(selected = ui.osr == "$a/$b", onClick = { app.capsule.setOsr(a, b) }, label = { Text(label) }, enabled = ui.status.linked)
                }
            }
            Muted("Настройки действуют до перезапуска капсулы.")
        }
    }
}

private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

private fun bootText(r: String) = when (r) {
    "power_on" -> "включение питания"
    "reset_button" -> "кнопка reset"
    "watchdog" -> "сторожевой таймер"
    "lockup" -> "сбой процессора"
    "software" -> "программный перезапуск"
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
    var magnet by remember { mutableStateOf(p.magnet) }
    var distance by remember { mutableStateOf(if (p.distanceMm > 0) p.distanceMm.toString() else "") }
    var repo by remember { mutableStateOf(p.githubRepo) }
    var token by remember { mutableStateOf(p.githubToken) }
    var auto by remember { mutableStateOf(p.autoUpload) }
    var check by remember { mutableStateOf("") }

    Page("Настройки", nav) {
        Section {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column1(Modifier.weight(1f)) {
                    Text("Подробный режим")
                    Muted("Частоты, потери, напряжение и уверенность модели на основных экранах.")
                }
                Switch(checked = detailed, onCheckedChange = { detailed = it; p.detailed = it })
            }
        }
        Section("Магнит и датчики") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Prefs.MAGNETS.forEach { (code, label) ->
                    FilterChip(selected = magnet == code, onClick = { magnet = code; p.magnet = code }, label = { Text(label) })
                }
            }
            OutlinedTextField(
                value = distance,
                onValueChange = { v -> distance = v.filter { it.isDigit() }.take(3); p.distanceMm = distance.toIntOrNull() ?: 0 },
                label = { Text("Расстояние между датчиками, мм") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        Section("Отправка записей в репозиторий") {
            Muted("Fine-grained token GitHub: доступ только к этому репозиторию, право Contents: Read and write.")
            OutlinedTextField(value = repo, onValueChange = { repo = it; p.githubRepo = it }, label = { Text("Репозиторий") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = token, onValueChange = { token = it; p.githubToken = it }, label = { Text("Токен") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Отправлять сразу после записи", modifier = Modifier.weight(1f))
                Switch(checked = auto, onCheckedChange = { auto = it; p.autoUpload = it })
            }
            OutlinedButton(onClick = {
                check = "Проверяю…"
                app.uploader.check { r -> check = r ?: "Доступ к репозиторию есть." }
            }, modifier = Modifier.fillMaxWidth()) { Text("Проверить доступ") }
            if (check.isNotEmpty()) Text(check, color = if (check.startsWith("Доступ")) Palette.ok else Palette.warn)
        }
        Section("Капсула") {
            KeyValue("Запомненная капсула", p.capsuleAddress.ifEmpty { "нет" })
            KeyValue("Прошивка", ui.firmware.ifEmpty { "—" })
            TextButton(onClick = { app.capsule.forget() }) { Text("Забыть капсулу") }
        }
        Muted("Приложение ${BuildConfig.VERSION_NAME}")
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

    Page("Логи", nav) {
        Muted("Технический журнал: связь, команды, строки капсулы, ошибки. Нужен, если что-то пошло не так.")
        Button(onClick = {
            val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
            val f = File(dir, "telehaptic-log.txt")
            f.writeText(app.log.allText())
            share(ctx, f, "text/plain", "Поделиться логами")
        }, modifier = Modifier.fillMaxWidth()) { Text("Поделиться логами") }
        OutlinedButton(onClick = {
            result = "Отправляю…"
            app.uploader.uploadLogs(app.log.allText()) { r -> result = r ?: "Логи в репозитории, папка logs/." }
        }, modifier = Modifier.fillMaxWidth()) { Text("Отправить в репозиторий") }
        if (result.isNotEmpty()) Muted(result)
        if (app.prefs.detailed) {
            Section {
                lines.takeLast(150).reversed().forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(vertical = 1.dp)) }
            }
        } else Muted("Строки журнала видны в подробном режиме (Настройки).")
        Spacer(Modifier.width(1.dp))
    }
}
