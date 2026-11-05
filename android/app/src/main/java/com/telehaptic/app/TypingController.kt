package com.telehaptic.app

import android.os.SystemClock
import com.telehaptic.app.core.T9
import com.telehaptic.app.core.Touch
import com.telehaptic.app.core.Typing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Typing test: detected touches go through the model into T9. Each accepted touch clicks
 * the motor. "Erase, then another key" right away is remembered as a possible correction.
 */
class TypingController(private val app: App) {
    data class Ui(
        val text: String = "",
        val candidates: List<String> = emptyList(),
        val variant: Int = 0,
        val lastKey: String = "",
        val flashUntil: Long = 0,
        val posterior: Double = 0.0,
        val second: String = "",
        val secondPosterior: Double = 0.0,
        val rejected: Int = 0,
        val corrections: Int = 0,
        val ready: Boolean = false,
    )

    @Volatile private var t9: T9? = null
    private var typing: Typing? = null
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    private val corrections = ArrayList<Touch>()
    private var lastTouch: Pair<String, DoubleArray>? = null
    private var erased: DoubleArray? = null

    /** Loads the dictionary in the background (about 47 thousand words). */
    fun preload() {
        Thread {
            try {
                val dict = T9(T9.parse(app.assets.open("en_words.txt").bufferedReader().lineSequence()))
                t9 = dict
                app.log.i("t9", "dictionary loaded")
            } catch (e: Exception) {
                app.log.w("t9", "dictionary", e)
            }
        }.start()
    }

    fun start() {
        val d = t9
        if (typing == null && d != null) typing = Typing(d)
        app.brain.armSegmenter()
        app.engine.onTouch = { onTouch(it) }
        app.engine.mode = Engine.Mode.TYPING
        publish()
    }

    fun stop() {
        if (app.engine.mode == Engine.Mode.TYPING) app.engine.mode = Engine.Mode.OFF
        app.engine.onTouch = null
    }

    private fun onTouch(d: Engine.Detected) {
        val r = d.result ?: return
        if (!r.accepted) {
            _ui.value = _ui.value.copy(rejected = _ui.value.rejected + 1)
            return
        }
        if (typing == null) t9?.let { typing = Typing(it) }
        val t = typing ?: return
        app.capsule.motor(Training.CLICK_MS)
        // Possible correction: erase right after a key, then a different key.
        if (r.key == "#") {
            erased = lastTouch?.takeIf { it.first != "#" }?.second
        } else {
            erased?.let { x -> corrections.add(Touch(r.key, x, "typing", "correction")) }
            erased = null
        }
        lastTouch = r.key to d.x
        t.key(r.key)
        _ui.value = _ui.value.copy(
            lastKey = r.key, flashUntil = SystemClock.elapsedRealtime() + 450,
            posterior = r.posterior, second = r.second, secondPosterior = r.secondPosterior,
        )
        publish()
    }

    /** Typing by tapping on the screen (works without the capsule; no corrections recorded). */
    fun screenKey(k: String) {
        if (typing == null) t9?.let { typing = Typing(it) }
        typing?.key(k)
        lastTouch = null; erased = null
        _ui.value = _ui.value.copy(lastKey = k, flashUntil = SystemClock.elapsedRealtime() + 450)
        publish()
    }

    fun choose(i: Int) { typing?.choose(i); publish() }

    fun clear() { typing?.clear(); lastTouch = null; erased = null; publish() }

    fun pendingCorrections() = corrections.size

    fun applyCorrections(yes: Boolean) {
        if (yes) app.brain.addCorrections(corrections.toList())
        corrections.clear()
        publish()
    }

    private fun publish() {
        val t = typing
        _ui.value = _ui.value.copy(
            text = t?.display() ?: "",
            candidates = t?.candidates() ?: emptyList(),
            variant = t?.variant ?: 0,
            corrections = corrections.size,
            ready = t9 != null,
        )
    }

    /** For screenshots only. */
    fun showForPreview(u: Ui) { _ui.value = u }
}
