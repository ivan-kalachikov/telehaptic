package com.telehaptic.app

import android.os.SystemClock
import com.telehaptic.app.core.T9
import com.telehaptic.app.core.Touch
import com.telehaptic.app.core.Typing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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
        val dictError: Boolean = false,
    )

    @Volatile private var t9: T9? = null
    @Volatile private var dictFailed = false
    @Volatile private var active = false

    init {
        app.capsule.addListener(object : Capsule.Listener {
            override fun onUsable(usable: Boolean) = linkChange(usable)
        })
    }
    private fun linkChange(up: Boolean) {
        if (!active) return
        if (up) app.cues.say("Capsule is back") else app.cues.alert("Capsule lost")   // once per loss (onUsable)
    }

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
                publish()
            } catch (e: Exception) {
                app.log.w("t9", "dictionary", e)
                // Without the word list typing still works: the digits are shown as typed.
                t9 = T9(emptySequence())
                dictFailed = true
                publish()
            }
        }.start()
    }

    fun start() {
        val d = t9
        if (typing == null && d != null) typing = Typing(d)
        active = true
        // Training running (e.g. started from a second window) keeps the detector; screen keys still work.
        if (!app.training.active) {
            app.brain.armSegmenterFreshRest()   // rest is measured again: the capsule may sit differently now
            app.engine.claim(this, Engine.Mode.TYPING) { onTouch(it) }
            if (app.capsule.ui.value.status == Capsule.Status.CONNECTED) app.cues.say("Typing. Hand relaxed, thumb away from the fingers for a second.")
        }
        publish()
    }

    fun stop() {
        active = false
        app.engine.release(this)
    }

    private fun onTouch(d: Engine.Detected) {
        val r = d.result ?: return
        if (!r.accepted) {
            app.log.i("typing", "rejected: nearest ${r.key} d2 ${"%.1f".format(java.util.Locale.US, r.d2)}")
            // Not like any key: a long buzz, different from the short click of an accepted touch.
            app.capsule.motor(REJECT_MS)
            _ui.update { it.copy(rejected = it.rejected + 1) }
            return
        }
        if (typing == null) t9?.let { typing = Typing(it) }
        val t = typing ?: return
        app.capsule.motor(Training.CLICK_MS)
        // Possible correction: a letter key erased right away, then another letter key. Only keys 2-9:
        // an erased space or full stop was usually meant, it is the word that changes.
        val letter = { k: String -> k.length == 1 && k[0] in '2'..'9' }
        if (r.key == "#") {
            erased = lastTouch?.takeIf { letter(it.first) }?.second
        } else {
            erased?.let { x -> if (letter(r.key)) corrections.add(Touch(r.key, x, "typing", "correction")) }
            erased = null
        }
        lastTouch = r.key to d.x
        speak(t, r.key)
        _ui.update { it.copy(
            lastKey = r.key, flashUntil = SystemClock.elapsedRealtime() + 450,
            posterior = r.posterior, second = r.second, secondPosterior = r.secondPosterior,
        ) }
        publish()
    }

    /**
     * Applies a key and says what changed, so the text can be followed without looking: the word
     * on space and full stop, the new variant on *, "erase" on #. Letter keys stay silent (the click is enough).
     */
    private fun speak(t: Typing, k: String) {
        val word = t.currentWord()
        val before = t.display()
        t.key(k)
        if (!app.prefs.speakTyping) return
        when (k) {
            "0" -> app.cues.say(spoken(word).ifEmpty { "space" })
            "1" -> app.cues.say(if (word.isEmpty()) "full stop" else "${spoken(word)}, full stop")
            "*" -> app.cues.say(spoken(t.currentWord()).ifEmpty { "no other word" })
            "#" -> {
                // Say what is left, so a correction can be followed by ear.
                val erased = before.lastOrNull()
                val now = t.currentWord()
                app.cues.say(when {
                    before.isEmpty() -> "nothing to erase"
                    erased == ' ' && now.isEmpty() -> "space erased"
                    erased == '.' && now.isEmpty() -> "full stop erased"
                    now.isNotEmpty() -> spoken(now)
                    else -> "word erased"
                })
            }
        }
    }

    /** A word as speech; a digit string with no word for it is read digit by digit, not as a number. */
    private fun spoken(w: String) = if (w.isNotEmpty() && w.all { it.isDigit() }) w.toList().joinToString(" ") else w

    /** Typing by tapping on the screen (works without the capsule; no corrections recorded). */
    fun screenKey(k: String) {
        if (typing == null) t9?.let { typing = Typing(it) }
        typing?.key(k)
        lastTouch = null; erased = null
        _ui.update { it.copy(lastKey = k, flashUntil = SystemClock.elapsedRealtime() + 450) }
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

    /** Main thread only: the typed text belongs to it. The dictionary thread posts here. */
    private fun publish() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { main.post { publish() }; return }
        if (typing == null) t9?.let { typing = Typing(it) }
        val t = typing
        _ui.update { it.copy(
            text = t?.display() ?: "",
            candidates = t?.candidates() ?: emptyList(),
            variant = t?.variant ?: 0,
            corrections = corrections.size,
            ready = t9 != null,
            dictError = dictFailed,
        ) }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        const val REJECT_MS = 200
    }

    /** For screenshots only. */
    fun showForPreview(u: Ui) { _ui.value = u }
}
