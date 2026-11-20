package com.telehaptic.app

import android.os.SystemClock
import com.telehaptic.app.core.Keys
import com.telehaptic.app.core.Kind
import com.telehaptic.app.core.PhraseLearning
import com.telehaptic.app.core.PlanSettings
import com.telehaptic.app.core.T9
import com.telehaptic.app.core.Touch
import com.telehaptic.app.core.Typing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Typing test: detected touches go through the model into T9, each letter touch with the model's
 * probabilities for all keys, so the word list can overrule a doubtful touch (soft T9, see
 * [T9.decode]). Each accepted touch clicks the motor. "Erase, then another key" right away is remembered as a possible correction.
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
        /** Where the current word starts in [text], and its letters the word list put in place of the model's key. */
        val wordStart: Int = 0,
        val corrected: List<Int> = emptyList(),
        /** Typing test: the phrase to type now ("" outside a test), its number and how many; done = all typed. */
        val target: String = "",
        val phrase: Int = 0,
        val phrases: Int = 0,
        val testDone: Boolean = false,
        /** After a test: the model is being learned from its phrases; then what came of it. */
        val learning: Boolean = false,
        val learned: String = "",
        val canUndoLearn: Boolean = false,
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

    // ------------------------------------------------------------ typing test
    //
    // The phone gives phrases; each ends with a full stop (key 1). The recording keeps, for each
    // phrase, what was asked ("phrase") and what came out ("phrase_end"), next to every touch, so
    // missed, extra and misread touches can be counted afterwards.

    private var test = false
    private var phraseList: List<String> = emptyList()
    private var phraseIndex = 0
    /** Touches of the phrase being typed (6 numbers each, taken or not), and the phrases typed so far. */
    private var phraseOpen = false
    private val phraseTouches = ArrayList<DoubleArray>()
    private val typed = ArrayList<PhraseLearning.Phrase>()

    private fun askPhrase() {
        val p = phraseList.getOrNull(phraseIndex) ?: return
        typing?.clear()
        phraseTouches.clear()
        phraseOpen = true
        if (recording) app.recorder.event("phrase", phraseIndex, p)
        app.cues.say(if (p == KEYS_ITEM) "Last one: star three times, space, hash three times. Then full stop." else "Type: $p. Then full stop.")
        _ui.update { it.copy(target = p, phrase = phraseIndex + 1, phrases = phraseList.size, testDone = false) }
    }

    /** Full stop in a test: the phrase is done; the typed text goes into the recording. */
    private fun phraseDone(typed: String) {
        if (recording) app.recorder.event("phrase_end", phraseIndex, typed)
        if (phraseOpen) PhraseLearning.keysOf(phraseList[phraseIndex])?.let { this.typed.add(PhraseLearning.Phrase(it, phraseTouches.toList())) }
        phraseOpen = false
        phraseIndex++
        if (phraseIndex < phraseList.size) { main.postDelayed({ if (test && active) askPhrase() }, 1200); return }
        test = false
        _ui.update { it.copy(target = "", testDone = true) }
        finishRecording()
        learnFromTest()
    }

    /**
     * The test's phrases teach a new model (see [Brain.learnFromTyping]); it is kept only if it
     * reads them better than the old one. Runs on the capsule thread, the result is said.
     */
    private fun learnFromTest() {
        val phrases = typed.toList()
        typed.clear()
        if (phrases.size < Brain.MIN_TYPED_PHRASES) { app.cues.say("Test done. Thank you."); return }
        app.cues.say("Test done. Learning from it.")
        _ui.update { it.copy(learning = true, learned = "") }
        val session = "typing-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        app.engine.post {
            val r = try { app.brain.learnFromTyping(session, phrases) } catch (e: Exception) { app.log.w("typing", "learning", e); null }
            main.post {
                val pct = { sc: PhraseLearning.Score -> (sc.share * 100).toInt() }
                val text = when {
                    r == null -> "Learning failed; the old model stays."
                    r.kept -> "Learned from ${phrases.size} phrases: the new model read ${pct(r.after)}% of the keys of phrases it had not seen, the old one ${pct(r.before)}%."
                    else -> "The old model stays: it read ${pct(r.before)}% of the keys, a model learned from these phrases ${pct(r.after)}%."
                }
                app.cues.say(if (r?.kept == true) "Learned. New model ${pct(r.after)} percent, old ${pct(r.before)} percent." else "The old model stays.")
                _ui.update { it.copy(learning = false, learned = text, canUndoLearn = r?.kept == true) }
            }
        }
    }

    /** Puts back the model from before the test. */
    fun undoLearn() {
        app.engine.post { app.brain.undoRetrain() }
        app.cues.say("Undone. The earlier model is back.")
        _ui.update { it.copy(canUndoLearn = false, learned = "Undone: the model from before the test is back.") }
    }

    fun start(asTest: Boolean = false) {
        val d = t9
        if (typing == null && d != null) typing = Typing(d)
        active = true
        test = asTest
        _ui.update { it.copy(target = "", phrase = 0, phrases = 0, testDone = false, learning = false, learned = "", canUndoLearn = false) }
        typed.clear(); phraseOpen = false
        // Training running (e.g. started from a second window) keeps the detector; screen keys still work.
        if (!app.training.active) {
            app.brain.armSegmenterFreshRest()   // rest is measured again: the capsule may sit differently now
            app.engine.claim(this, Engine.Mode.TYPING) { onTouch(it) }
            startRecording()
            if (app.capsule.ui.value.status == Capsule.Status.CONNECTED) app.cues.say("Typing. Hand relaxed, thumb away from the fingers for a second.")
        }
        if (test) {
            val from = app.prefs.phraseRound * TEST_PHRASES
            app.prefs.phraseRound = app.prefs.phraseRound + 1
            phraseList = List(TEST_PHRASES) { PHRASES[(from + it) % PHRASES.size] } + KEYS_ITEM
            phraseIndex = 0
            main.postDelayed({ if (test && active) askPhrase() }, 3500)
        }
        publish()
    }

    fun stop() {
        active = false
        test = false
        app.engine.release(this)
        finishRecording()
    }

    private fun finishRecording() {
        if (!recording) return
        recording = false
        app.recorder.event("end")
        app.recorder.finish(false) { r -> if (r.file != null && app.prefs.autoUpload && app.prefs.githubToken.isNotEmpty()) app.uploader.upload(r.file) }
    }

    /**
     * Typing is recorded like training (kind TYPE): the stream and every touch the detector found,
     * taken or not, so false presses can be looked at afterwards. Only when no other recording runs.
     */
    private var recording = false
    private fun startRecording() {
        if (recording || app.recorder.isOpen || app.capsule.ui.value.status != Capsule.Status.CONNECTED) return
        val spec = RecordingSpec(Kind.TYPING, null, false, app.prefs.magnet, app.prefs.distanceMm, if (test) "typing test" else "", 0, emptyList(), PlanSettings())
        recording = app.recorder.start(spec) { _ ->
            if (!active) { app.recorder.finish(true) {}; recording = false; return@start }
            app.recorder.event("start")
            app.recorder.event("detector", -1, app.brain.detectorText())
        }
    }

    private fun onTouch(d: Engine.Detected) {
        if (test && phraseOpen) phraseTouches.add(d.x)
        val r = d.result ?: return
        val jolt = d.jolt?.let { "%.2f".format(java.util.Locale.US, it) } ?: "-"
        if (!r.accepted) {
            // Not like any key: ignored without a sound (a long buzz here fired on every false touch).
            app.log.i("typing", "ignored (unlike): nearest ${r.key} d2 ${"%.1f".format(java.util.Locale.US, r.d2)} jolt $jolt")
            if (recording) app.recorder.eventAt(d.tNs, "touch_unlike", -1, r.key)
            _ui.update { it.copy(rejected = it.rejected + 1) }
            return
        }
        if (recording) app.recorder.eventAt(d.tNs, "touch", -1, r.key)
        if (typing == null) t9?.let { typing = Typing(it) }
        val t = typing ?: return
        app.capsule.motor(Training.CLICK_MS)
        // Possible correction: a letter key erased right away, then another letter key. Only keys 2-9:
        // an erased space or full stop was usually meant, it is the word that changes.
        val letter = { k: String -> k.length == 1 && k[0] in '2'..'9' }
        if (r.key == Keys.ERASE) {
            erased = lastTouch?.takeIf { letter(it.first) }?.second
        } else {
            erased?.let { x -> if (letter(r.key)) corrections.add(Touch(r.key, x, "typing", "correction")) }
            erased = null
        }
        lastTouch = r.key to d.x
        val textBefore = t.display()
        speak(t, r.key, r.probs)
        if (test && r.key == "1") phraseDone(textBefore.trimEnd())
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
    private fun speak(t: Typing, k: String, probs: Map<String, Double>) {
        val word = t.currentWord()
        val before = t.display()
        val keys = t.digits
        val fixed = t.corrected().isNotEmpty()
        // Correction off: the model's first choice is taken as certain, i.e. classic T9.
        t.key(k, probs, if (app.prefs.correctKeys) app.brain.keyErrorRate else 0.0)
        // For checking later how often the word list overrules the model, and whether it was right.
        if ((k == "0" || k == "1") && fixed) app.log.i("typing", "word '$word' overrules the keys $keys")
        if (!app.prefs.speakTyping) return
        // A word that is not what the keys said is announced as corrected, so it can be checked by ear.
        val said = spoken(word) + if (fixed) ", corrected" else ""
        when (k) {
            "0" -> app.cues.say(said.ifEmpty { "space" })
            "1" -> app.cues.say(if (word.isEmpty()) "full stop" else "$said, full stop")
            Keys.NEXT -> app.cues.say(spoken(t.currentWord()).ifEmpty { "no other word" } + if (t.corrected().isNotEmpty()) ", corrected" else "")
            Keys.ERASE -> {
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
        val textBefore = typing?.display() ?: ""
        if (recording) app.recorder.event("screen_key", -1, k)
        typing?.key(k)
        if (test && k == "1") phraseDone(textBefore.trimEnd())
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
            wordStart = (t?.display()?.length ?: 0) - (t?.currentWord()?.length ?: 0),
            corrected = t?.corrected() ?: emptyList(),
        ) }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        const val REJECT_MS = 200
        const val TEST_PHRASES = 6

        /** The test's last item: the erase and next keys, which no phrase has, so learning sees them too. */
        const val KEYS_ITEM = "*** ###"

        /**
         * Everyday phrases chosen for learning: every word is in the word list, keys are spread evenly
         * (more of 3, 6, 9, 2 and 5, the keys mixed up most) and as many key-to-key moves as possible
         * occur. Picked greedily from a larger pool (analysis/phrases.py). Tests go through the list in
         * order, so six tests type each phrase once.
         */
        val PHRASES = listOf(
            "my favorite color is yellow", "which one do you like", "happy birthday to you", "exactly six weeks",
            "bring some milk please", "let us grab a drink", "take an umbrella", "fill the water jug", "maybe next week",
            "zero waste kitchen", "it is very cold outside", "type the next word", "play some jazz", "who is coming tonight",
            "my phone is almost dead", "fix the broken window", "thank you very much", "congrats on the new job",
            "wear a warm jacket", "it will rain later", "miss you already", "turn off the lights",
            "the weather is lovely today", "join us for lunch", "do you want pizza", "buy some fresh bread",
            "we will walk back", "i forgot my keys", "where should we meet", "the wifi is down", "a lazy sunday",
            "what is the address", "the sky is blue", "my battery is low", "eggs for breakfast", "the train is delayed",
        )
    }

    /** For screenshots only. */
    fun showForPreview(u: Ui) { _ui.value = u }
}
