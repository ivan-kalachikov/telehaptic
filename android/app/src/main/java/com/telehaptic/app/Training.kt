package com.telehaptic.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.telehaptic.app.core.Evaluation
import com.telehaptic.app.core.Keys
import com.telehaptic.app.core.Kind
import com.telehaptic.app.core.PlanSettings
import com.telehaptic.app.core.Touch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/**
 * Training wizard and retraining. Main thread.
 *
 * Training = 2 blocks of holds (cued, 1.5 s) for clean key centres and the rest level, then
 * 2 blocks of quick taps at one's own pace, found by the same detector as in typing.
 * Retraining = quick taps only, on all keys or on the keys that get confused.
 */
class Training(private val app: App) {
    enum class Phase { IDLE, STARTING, HOLDS, TAPS, FITTING, RESULT }
    enum class HoldStep { READY, HOLD, REST }

    data class Ui(
        val phase: Phase = Phase.IDLE,
        val retrain: Boolean = false,
        val key: String = "",
        val holdStep: HoldStep = HoldStep.READY,
        val index: Int = 0,
        val total: Int = 0,
        val paused: Boolean = false,
        val hint: String = "",
        val flashUntil: Long = 0,
        val report: Evaluation.Report? = null,
        val before: Evaluation.Report? = null,
        val error: String = "",
        val kept: Boolean = true,        // re-fit: the new model replaced the old one
        val canUndo: Boolean = false,
        val full: Boolean = true,        // re-fit over all keys
    )

    private val main = Handler(Looper.getMainLooper())
    private val token = Any()
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    private var holdSeq: List<String> = emptyList()
    private var tapSeq: MutableList<String> = mutableListOf()
    private val touches = ArrayList<Touch>()
    private val restRows = ArrayList<DoubleArray>()
    private var tapIndex = 0
    private var acceptAfter = 0L
    private var lastTapAt = 0L
    private var session = ""

    val active get() = _ui.value.phase.let { it == Phase.STARTING || it == Phase.HOLDS || it == Phase.TAPS }

    init {
        // Capsule lost in the middle: pause, so the next touches are not lost or blamed on the person.
        // Data stopped while connected counts the same.
        app.capsule.addListener(object : Capsule.Listener {
            override fun onLink(connected: Boolean) { main.post { onLinkChange(connected) } }
            override fun onStall(stalled: Boolean) { main.post { onLinkChange(!stalled) } }
        })
    }

    /** Paused because the capsule went away: going on by itself when it is back. */
    private var pausedByLink = false

    private fun onLinkChange(up: Boolean) {
        val u = _ui.value
        if (u.phase != Phase.HOLDS && u.phase != Phase.TAPS) return
        if (!up && !u.paused) {
            pause(true)
            pausedByLink = true
            _ui.value = _ui.value.copy(hint = "Capsule lost. Paused; training goes on by itself when it is back.")
            app.cues.alert("Capsule lost. Paused.")
        } else if (up && u.paused && pausedByLink) {
            _ui.value = _ui.value.copy(hint = "Capsule is back. Going on in 3 seconds.")
            app.cues.say("Capsule is back. Going on.")
            main.postAtTime({
                val v = _ui.value
                if (v.paused && pausedByLink && app.capsule.ui.value.status == Capsule.Status.CONNECTED) pause(false)
            }, token, SystemClock.uptimeMillis() + 3000)
        }
    }

    // ------------------------------------------------------------ start

    fun startTraining() {
        if (active || app.session.running || app.recorder.isOpen) return
        touches.clear(); restRows.clear(); emptyHolds = 0; lastWasSkip = false; pausedByLink = false
        app.engine.claim(this, Engine.Mode.OFF) {}   // typing elsewhere must not take the touches of the holds
        holdSeq = Keys.sequence(2, Random.Default)
        tapSeq = Keys.sequence(2, Random.Default).toMutableList()
        _ui.value = Ui(phase = Phase.STARTING)
        beginRecording(Kind.TRAIN) {
            _ui.value = Ui(phase = Phase.HOLDS, total = holdSeq.size)
            app.cues.say("Training. First, touches with a hold.")
            main.postAtTime({ holdTrial(0) }, token, SystemClock.uptimeMillis() + 2500)
        }
    }

    /** keys = which keys to practise; each gets [reps] quick taps. */
    fun startRetrain(keys: List<String>, reps: Int) {
        if (active || app.session.running || app.recorder.isOpen) return
        if (app.brain.model == null) { _ui.value = Ui(error = "Complete the training first."); return }
        touches.clear(); lastWasSkip = false; pausedByLink = false
        app.engine.claim(this, Engine.Mode.OFF) {}
        val rnd = Random.Default
        val seq = ArrayList<String>()
        repeat(reps) { var b: List<String>; do { b = keys.shuffled(rnd) } while (seq.isNotEmpty() && keys.size > 1 && b[0] == seq.last()); seq.addAll(b) }
        tapSeq = seq
        _ui.value = Ui(phase = Phase.STARTING, retrain = true)
        retrainFull = keys.size == Keys.ALL.size
        beginRecording(Kind.RETRAIN) {
            app.brain.armSegmenterFreshRest()
            app.recorder.event("detector", -1, app.brain.detectorText())
            startTaps(retrain = true)
        }
    }

    private fun beginRecording(kind: Kind, then: () -> Unit) {
        val spec = RecordingSpec(kind, null, false, app.prefs.magnet, app.prefs.distanceMm, "", 0, emptyList(), TIMING)
        val ok = app.recorder.start(spec) { id ->
            if (_ui.value.phase != Phase.STARTING) return@start          // cancelled meanwhile
            if (!app.recorder.isOpen) { fail("Could not start the recording. Details in Logs."); return@start }
            session = id; app.recorder.event("start"); then()
        }
        if (!ok) _ui.value = Ui(error = "Another recording is still running. Finish it first.")
    }

    // ------------------------------------------------------------ holds

    private fun holdTrial(i: Int) {
        if (_ui.value.phase != Phase.HOLDS) return
        if (_ui.value.paused) { main.postAtTime({ holdTrial(i) }, token, SystemClock.uptimeMillis() + 300); return }
        if (i >= holdSeq.size) { finishHolds(); return }
        val k = holdSeq[i]
        _ui.value = _ui.value.copy(key = k, index = i, holdStep = HoldStep.READY, hint = "")
        app.recorder.event("announce", i, k)
        app.cues.sayKey(k)
        main.postAtTime({
            if (_ui.value.paused || _ui.value.phase != Phase.HOLDS) { holdTrial(i); return@postAtTime }   // paused before the press: redo this key
            val pausesAtPress = pauses
            val press = SystemClock.elapsedRealtimeNanos()
            app.recorder.event("press", i, k)
            app.cues.press()
            _ui.value = _ui.value.copy(holdStep = HoldStep.HOLD)
            main.postAtTime({
                val release = SystemClock.elapsedRealtimeNanos()
                app.recorder.event("release", i, k)
                app.cues.release()
                // The key is not shown while resting: the screen would say "Release" next to a key labelled "Next".
                _ui.value = _ui.value.copy(holdStep = HoldStep.REST, key = "")
                if (pauses != pausesAtPress) {
                    // Paused during the hold (capsule lost, or by hand): this hold is not used, the key comes again.
                    holdSeq = holdSeq.toMutableList().apply { add(minOf(i + 1, size), k) }
                    _ui.value = _ui.value.copy(total = holdSeq.size)
                } else app.engine.windowMedian(press + 500_000_000L, release - 200_000_000L) { x ->
                    if (x != null) { touches.add(Touch(k, x, "hold${i / 12}", "hold")); emptyHolds = 0 }
                    else if (++emptyHolds >= 3 && !_ui.value.paused) {
                        pause(true)
                        _ui.value = _ui.value.copy(hint = "No data from the capsule. Paused. Switch the capsule off and on, then tap Resume.")
                        app.cues.alert("No data from the capsule. Paused.")
                    }
                }
                main.postAtTime({
                    val r0 = SystemClock.elapsedRealtimeNanos()
                    app.engine.windowMedian(r0 - 800_000_000L, r0) { x -> if (x != null) restRows.add(x) }
                    holdTrial(i + 1)
                }, token, SystemClock.uptimeMillis() + 1000)
            }, token, SystemClock.uptimeMillis() + 1500)
        }, token, SystemClock.uptimeMillis() + 1500)
    }

    private fun finishHolds() {
        if (touches.size < 12) {
            fail("Too little data from the capsule. Check the connection and start again.")
            return
        }
        // Detector thresholds from the holds: rest level and the key centres.
        val two = app.engine.twoSensors
        val restX = DoubleArray(6) { c -> Evaluation.median(restRows.map { it[c] }) }
        val restY = app.engine.segmenter.yOf(restX, two)
        val keyY = Keys.ALL.mapNotNull { k ->
            val l = touches.filter { it.key == k }
            if (l.isEmpty()) null else app.engine.segmenter.yOf(DoubleArray(6) { i -> l.map { it.x[i] }.average() }, two)
        }
        val params = com.telehaptic.app.core.Segmenter.Params.fromTraining(restY, keyY)
        app.engine.configure(params, restY)
        app.recorder.event("detector", -1, app.brain.detectorText(params, restY))
        pendingRest = restY
        app.log.i("train", "holds done: ${touches.size}, onUt ${params.onUt}")
        app.cues.say("Now quick taps, at your own pace.")
        main.postAtTime({ startTaps(retrain = false) }, token, SystemClock.uptimeMillis() + 2500)
    }

    private var pendingRest: DoubleArray? = null
    private var emptyHolds = 0
    private var lastWasSkip = false
    private var retrainFull = true



    // ------------------------------------------------------------ taps

    private fun startTaps(retrain: Boolean) {
        tapIndex = 0
        _ui.value = _ui.value.copy(phase = Phase.TAPS, retrain = retrain, total = tapSeq.size, index = 0, key = tapSeq.firstOrNull() ?: "", hint = "")
        app.engine.claim(this, Engine.Mode.TAPS) { d -> onTap(d) }
        acceptAfter = SystemClock.elapsedRealtime() + 300
        announceTap()
    }

    private fun announceTap() {
        val k = tapSeq.getOrNull(tapIndex) ?: return
        lastTapAt = SystemClock.elapsedRealtime()
        app.recorder.event("tap_cue", tapIndex, k)
        app.cues.sayKey(k)
        val my = tapIndex
        main.postAtTime({
            if (_ui.value.phase == Phase.TAPS && tapIndex == my && !_ui.value.paused) {
                _ui.value = _ui.value.copy(hint = "Touch not detected. Touch a little firmer and keep the thumb there for a moment.")
                app.cues.say("Not detected. Touch a bit firmer. ${Cues.keyName(k)}.")
            }
        }, token, SystemClock.uptimeMillis() + 8000)
    }

    private fun onTap(d: Engine.Detected) {
        val u = _ui.value
        if (u.phase != Phase.TAPS || u.paused || SystemClock.elapsedRealtime() < acceptAfter) return
        val k = tapSeq.getOrNull(tapIndex) ?: return
        touches.add(Touch(k, d.x, "tap${tapIndex / 12}", "tap"))
        lastWasSkip = false
        app.recorder.eventAt(d.tNs, "tap_plateau", tapIndex, k)   // start of the plateau the detector found
        app.recorder.event("tap", tapIndex, k)
        app.capsule.motor(CLICK_MS)
        tapIndex++
        acceptAfter = SystemClock.elapsedRealtime() + 400
        if (tapIndex >= tapSeq.size) { fit(); return }
        _ui.value = u.copy(index = tapIndex, key = tapSeq[tapIndex], hint = "", flashUntil = SystemClock.elapsedRealtime() + 300)
        announceTap()
    }

    fun skip() {
        if (_ui.value.phase != Phase.TAPS) return
        lastWasSkip = true
        tapIndex++
        if (tapIndex >= tapSeq.size) { fit(); return }
        _ui.value = _ui.value.copy(index = tapIndex, key = tapSeq[tapIndex], hint = "")
        announceTap()
    }

    /** Takes back the last touch and asks for that key again. */
    fun repeatLast() {
        val u = _ui.value
        when (u.phase) {
            Phase.TAPS -> if (tapIndex > 0 && (lastWasSkip || touches.lastOrNull()?.source == "tap")) {
                if (!lastWasSkip) touches.removeAt(touches.size - 1)   // after a skip there is no tap to take back
                lastWasSkip = false
                tapIndex--
                app.recorder.event("repeat", tapIndex, tapSeq[tapIndex])
                _ui.value = u.copy(index = tapIndex, key = tapSeq[tapIndex], hint = "")
                acceptAfter = SystemClock.elapsedRealtime() + 400
                announceTap()
            }
            Phase.HOLDS -> if (touches.lastOrNull()?.source == "hold") {
                val last = touches.removeAt(touches.size - 1)
                // Put the key back right after the current one.
                val l = holdSeq.toMutableList()
                l.add(minOf(u.index + 1, l.size), last.key)
                holdSeq = l
                app.recorder.event("repeat", u.index, last.key)
                _ui.value = u.copy(total = holdSeq.size, hint = "Key ${last.key} will come again.")
            }
            else -> {}
        }
    }

    private var pauses = 0

    fun pause(p: Boolean) {
        if (p) pauses++ else pausedByLink = false
        _ui.value = _ui.value.copy(paused = p, hint = if (p) _ui.value.hint else "")
        app.recorder.event(if (p) "pause" else "resume")
        if (!p && _ui.value.phase == Phase.TAPS) { acceptAfter = SystemClock.elapsedRealtime() + 400; announceTap() }
    }

    // ------------------------------------------------------------ end

    private fun fit() {
        app.engine.release(this)
        val retrain = _ui.value.retrain
        _ui.value = _ui.value.copy(phase = Phase.FITTING, key = "", hint = "")
        app.recorder.event("end")
        app.cues.end()
        val list = touches.toList()
        val sess = session
        app.engine.post {   // Brain is used on the capsule thread only
            var before: Evaluation.Report? = null
            var after: Evaluation.Report? = null
            var kept = true
            try {
                if (retrain) {
                    val r = app.brain.retrain(sess, list, retrainFull)
                    before = r.before; after = r.after; kept = r.kept
                } else after = app.brain.train(sess, list, pendingRest)
            } catch (e: Exception) {
                app.log.w("train", "fit", e)
            }
            main.post {
                app.recorder.finish(false) { r -> if (r.file != null && app.prefs.autoUpload && app.prefs.githubToken.isNotEmpty()) app.uploader.upload(r.file) }
                val a = after
                if (a == null) fail("Could not train the model. Details in Logs.")
                else {
                    _ui.value = _ui.value.copy(phase = Phase.RESULT, before = before, report = a, kept = kept,
                        canUndo = retrain && kept, full = !retrain || retrainFull)
                    val pct = Math.round(a.share * 100)
                    app.cues.say(when {
                        !retrain -> "Training done. It recognises $pct percent of quick taps."
                        !kept -> "Re-fit done. The new taps did not help, so the previous model is kept."
                        else -> "Re-fit done. $pct percent."
                    })
                }
            }
        }
    }

    private fun fail(msg: String) {
        main.removeCallbacksAndMessages(token)
        app.cues.alert(msg.substringBefore(" Details"))
        app.engine.release(this)
        app.recorder.finish(true) {}
        _ui.value = Ui(error = msg)
    }

    fun cancel() {
        if (!active) { _ui.value = Ui(); return }
        app.recorder.event("abort")
        main.removeCallbacksAndMessages(token)
        app.engine.release(this)
        app.recorder.finish(true) {}
        _ui.value = Ui()
    }

    fun close() { if (!active) _ui.value = Ui() }

    /** Puts back the model from before the re-fit. */
    fun undo() {
        app.engine.post { app.brain.undoRetrain() }
        _ui.value = _ui.value.copy(canUndo = false, kept = false)
    }

    /** For screenshots only. */
    fun showForPreview(u: Ui) { _ui.value = u }

    companion object {
        const val CLICK_MS = 40
        /** Holds: announce, +1.5 s press, 1.5 s hold, 1 s until the next announce (rest 2.5 s before each press). */
        val TIMING = PlanSettings(leadMs = 2500, holdMs = 1500, restMs = 2500, blocks = 2, stillMs = 0, rotateMs = 0, motorPulses = 0)
    }
}
