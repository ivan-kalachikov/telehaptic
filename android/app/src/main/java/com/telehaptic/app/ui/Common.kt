package com.telehaptic.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (nav != null) TextButton(onClick = { nav.back() }) { Text("‹", fontSize = 28.sp) }
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
    Capsule.Status.NO_PERMISSION -> "Нужно разрешение на Bluetooth"
    Capsule.Status.BT_OFF -> "Bluetooth выключен"
    Capsule.Status.IDLE -> "Капсула не подключена"
    Capsule.Status.SEARCHING -> "Ищу капсулу…"
    Capsule.Status.NOT_FOUND -> "Капсула не найдена"
    Capsule.Status.CONNECTING -> "Подключаюсь…"
    Capsule.Status.CONNECTED -> "Подключена"
    Capsule.Status.NO_DATA -> "На связи, но данные не идут"
    Capsule.Status.RECONNECTING -> "Связь потеряна, переподключаюсь…"
}

fun statusHint(s: Capsule.Status) = when (s) {
    Capsule.Status.BT_OFF -> "Включите Bluetooth в быстрых настройках."
    Capsule.Status.NOT_FOUND -> "Включите капсулу и поднесите её к телефону, затем нажмите «Подключить»."
    Capsule.Status.NO_DATA -> "Перезапустите капсулу выключателем. Если не поможет — откройте Диагностику и Логи."
    Capsule.Status.RECONNECTING -> "Проверьте, что капсула включена и рядом."
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
