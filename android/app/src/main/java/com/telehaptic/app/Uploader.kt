package com.telehaptic.app

import android.content.Context
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Sends recordings to data/ of the GitHub repository (contents API, one commit per file).
 * A sent file gets a "<name>.sent" marker next to it.
 */
class Uploader(private val ctx: Context, private val prefs: Prefs, private val log: AppLog) {
    enum class State { WAITING, SENDING, SENT, FAILED }
    data class Item(val name: String, val state: State, val message: String = "")

    private val exec = Executors.newSingleThreadExecutor()
    private val _items = MutableStateFlow<Map<String, Item>>(emptyMap())
    val items: StateFlow<Map<String, Item>> = _items

    private fun recordings() = File(ctx.filesDir, "recordings")

    fun isSent(f: File) = File(f.path + ".sent").exists()

    /** All recordings, newest first. */
    fun list(): List<File> = (recordings().listFiles { f -> f.name.endsWith(".zip") } ?: emptyArray()).sortedByDescending { it.name }

    private fun set(name: String, st: State, msg: String = "") {
        _items.value = _items.value + (name to Item(name, st, msg))
    }

    fun upload(f: File) {
        if (isSent(f)) { set(f.name, State.SENT); return }
        set(f.name, State.WAITING)
        exec.execute {
            set(f.name, State.SENDING)
            val r = put("data/${f.name}", f.readBytes(), "data: ${f.nameWithoutExtension}")
            if (r == null) {
                File(f.path + ".sent").writeText(Date().toString())
                set(f.name, State.SENT)
            } else set(f.name, State.FAILED, r)
        }
    }

    fun uploadPending() = list().filter { !isSent(it) && !it.name.contains("_ABORTED") }.forEach { upload(it) }

    /** Logs go to logs/ in the repository. Result (null = ok, else the error) on the worker thread. */
    fun uploadLogs(text: String, done: (String?) -> Unit) {
        exec.execute {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = "logs/${Build.MODEL.replace(' ', '_')}_$stamp.txt"
            done(put(name, text.toByteArray(), "logs: $stamp"))
        }
    }

    /** Checks that the token can see the repository. Null = ok, else a message. */
    fun check(done: (String?) -> Unit) {
        exec.execute {
            done(try {
                val c = open("https://api.github.com/repos/${prefs.githubRepo}", "GET")
                when (val code = c.responseCode) {
                    200 -> null
                    401 -> "Токен не подходит: GitHub его не принял."
                    403, 404 -> "Репозиторий ${prefs.githubRepo} не найден или у токена нет к нему доступа."
                    else -> "GitHub ответил $code."
                }
            } catch (e: Exception) {
                "Нет связи с GitHub. Проверьте интернет."
            })
        }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "telehaptic-app")
        if (prefs.githubToken.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer ${prefs.githubToken}")
        return c
    }

    /** Null on success, else a message a person can act on. */
    private fun put(path: String, bytes: ByteArray, message: String): String? {
        if (prefs.githubToken.isEmpty()) return "Нет токена GitHub. Добавьте его в Настройках."
        return try {
            val body = JSONObject()
                .put("message", message)
                .put("branch", prefs.githubBranch)
                .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .toString().toByteArray()
            val c = open("https://api.github.com/repos/${prefs.githubRepo}/contents/$path", "PUT")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val err = if (code >= 400) (c.errorStream?.bufferedReader()?.readText() ?: "") else ""
            log.i("upload", "$path -> $code ${err.take(200)}")
            when {
                code == 200 || code == 201 -> null
                code == 422 && err.contains("sha") -> null   // the file is already there
                code == 401 -> "Токен не подходит. Проверьте его в Настройках."
                code == 403 || code == 404 -> "У токена нет права записи в ${prefs.githubRepo}."
                else -> "GitHub ответил $code. Попробуйте позже."
            }
        } catch (e: Exception) {
            log.w("upload", path, e)
            "Нет связи с GitHub. Отправлю, когда появится интернет: нажмите «Отправить»."
        }
    }
}
