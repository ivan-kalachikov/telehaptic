package com.telehaptic.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Technical log for "Share logs": everything a person does not need to read on screen. */
class AppLog(ctx: Context) {
    val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
    val file = File(dir, "app.log")
    private val old = File(dir, "app.old.log")
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun i(tag: String, msg: String) = write("I", tag, msg)

    @Synchronized
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg + (t?.let { " | $it" } ?: ""))

    private fun write(level: String, tag: String, msg: String) {
        if (level == "W") Log.w("telehaptic/$tag", msg) else Log.i("telehaptic/$tag", msg)
        try {
            if (file.length() > 2_000_000) { old.delete(); file.renameTo(old) }
            FileWriter(file, true).use { it.write("${fmt.format(Date())} $level $tag: $msg\n") }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun tail(lines: Int): List<String> = try {
        file.readLines().takeLast(lines)
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun allText(): String = (if (old.exists()) old.readText() else "") + (if (file.exists()) file.readText() else "")
}
