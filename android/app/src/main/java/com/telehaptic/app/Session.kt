package com.telehaptic.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.telehaptic.app.core.Kind
import com.telehaptic.app.core.Keys
import com.telehaptic.app.core.PlanSettings
import com.telehaptic.app.core.Plans
import com.telehaptic.app.core.Posture
import com.telehaptic.app.core.Step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/** Runs one experiment recording: cues on time, events into the recording. Main thread. */
class Session(private val app: App) {
    enum class Phase { IDLE, STARTING, LEAD, ANNOUNCE, HOLD, REST, STILL, ROTATE, SAVING, DONE }

    data class Ui(
        val phase: Phase = Phase.IDLE,
        val kind: Kind = Kind.KEYS,
        val key: String = "",
        val nextKey: String = "",
        val trial: Int = -1,
        val trials: Int = 0,
        val phaseEndsAt: Long = 0,       // SystemClock.elapsedRealtime of the phase end, for countdowns
        val startedAt: Long = 0,
        val totalMs: Long = 0,
        val id: String = "",
        val result: RecordingResult? = null,
        val note: String = "",          // why the recording ended early, in plain words
    )

    private val main = Handler(Looper.getMainLooper())

    init {
        // Capsule lost while recording: warn at once; if it is not back in 5 s, stop, the rest would be lost anyway.
        app.capsule.addListener(object : Capsule.Listener {
            override fun onUsable(usable: Boolean) { main.post { linkChange(usable) } }
        })
    }

    private fun linkChange(up: Boolean) {
        if (!running) return
        if (up) { main.removeCallbacks(lostCheck); app.cues.say("Capsule is back"); return }
        app.cues.alert("Capsule lost")
        main.removeCallbacks(lostCheck)
        main.postDelayed(lostCheck, 5000)
    }

    private val lostCheck = Runnable {
        // Connected without data counts as lost too.
        if (running && app.capsule.ui.value.status != Capsule.Status.CONNECTED) {
            app.recorder.event("abort", _ui.value.trial, "link lost")
            app.cues.alert("Recording stopped. The capsule was lost.")
            _ui.value = _ui.value.copy(note = "Stopped: the capsule was lost for more than 5 seconds. Record again when it is connected.")
            finish(aborted = true)
        }
    }
    private val token = Any()
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
    val running get() = _ui.value.phase !in listOf(Phase.IDLE, Phase.DONE)

    fun start(kind: Kind, posture: Posture?, rewear: Boolean, note: String) {
        if (running || app.training.active || app.recorder.isOpen) return
        val plan = PlanSettings()
        val seed = Random.nextLong()
        val seq = if (kind == Kind.CALIBRATION) emptyList() else Keys.sequence(plan.blocks, Random(seed))
        val steps = if (kind == Kind.CALIBRATION) Plans.calibration(plan) else Plans.keys(seq, plan)
        val spec = RecordingSpec(kind, posture, rewear, app.prefs.magnet, app.prefs.distanceMm, note, seed, seq, plan)
        _ui.value = Ui(phase = Phase.STARTING, kind = kind, trials = seq.size)
        val ok = app.recorder.start(spec) { id ->
            if (_ui.value.phase != Phase.STARTING) return@start   // stopped meanwhile
            if (!app.recorder.isOpen) {
                // The files could not be created (disk full?): say so now, not after the whole plan.
                _ui.value = _ui.value.copy(phase = Phase.SAVING, note = "Could not start the recording. Details in Logs.")
                app.recorder.finish(true) { r -> _ui.value = _ui.value.copy(phase = Phase.DONE, result = r) }   // the Done screen shows the error
                app.cues.alert("Could not start the recording")
                return@start
            }
            val t0 = SystemClock.uptimeMillis()
            val total = steps.last().atMs
            _ui.value = _ui.value.copy(phase = Phase.LEAD, id = id, startedAt = SystemClock.elapsedRealtime(),
                totalMs = total, phaseEndsAt = SystemClock.elapsedRealtime() + (steps.getOrNull(1)?.atMs ?: 0))
            app.cues.say(if (kind == Kind.CALIBRATION) "Calibration. Take the magnet off. Keep the hand still." else "Get ready")
            for (s in steps) main.postAtTime({ run(s, plan, steps) }, token, t0 + s.atMs)
        }
        if (!ok) _ui.value = Ui()
    }

    private fun run(s: Step, plan: PlanSettings, steps: List<Step>) {
        val rec = app.recorder
        val now = SystemClock.elapsedRealtime()
        when (s.event) {
            "start" -> rec.event("start")
            "announce" -> {
                rec.event("announce", s.trial, s.key)
                app.cues.sayKey(s.key)
                _ui.value = _ui.value.copy(phase = Phase.ANNOUNCE, nextKey = s.key, key = "")
            }
            "press" -> {
                rec.event("press", s.trial, s.key)
                app.cues.press()
                _ui.value = _ui.value.copy(phase = Phase.HOLD, key = s.key, nextKey = "", trial = s.trial, phaseEndsAt = now + plan.holdMs)
            }
            "release" -> {
                rec.event("release", s.trial, s.key)
                app.cues.release()
                _ui.value = _ui.value.copy(phase = Phase.REST, key = "", phaseEndsAt = now + plan.restMs)
            }
            "still_start" -> {
                rec.event("still_start")
                app.cues.press()
                _ui.value = _ui.value.copy(phase = Phase.STILL, phaseEndsAt = now + plan.stillMs)
            }
            "motor" -> {
                rec.event("motor_cmd", s.trial, s.motorMs.toString())
                app.capsule.motor(s.motorMs)
            }
            "calib_start" -> {
                rec.event("calib_start")
                app.cues.press()
                app.cues.say("Slowly turn the hand in all directions")
                _ui.value = _ui.value.copy(phase = Phase.ROTATE, phaseEndsAt = now + plan.rotateMs)
            }
            "calib_end" -> {
                rec.event("calib_end")
                app.cues.release()
            }
            "end" -> {
                rec.event("end")
                app.cues.end()
                app.cues.say("Done")
                finish(aborted = false)
            }
        }
    }

    fun stop() {
        if (!running) return
        app.recorder.event("abort", _ui.value.trial)
        app.cues.say("Stopped")
        finish(aborted = true)
    }

    private fun finish(aborted: Boolean) {
        main.removeCallbacksAndMessages(token)
        main.removeCallbacks(lostCheck)
        _ui.value = _ui.value.copy(phase = Phase.SAVING, key = "", nextKey = "")
        app.recorder.finish(aborted) { r ->
            _ui.value = _ui.value.copy(phase = Phase.DONE, result = r)
            app.cues.say(when {
                r.file == null -> "The recording could not be saved. Check the phone."
                aborted -> "Recording stopped and kept."
                r.lost > 0 || r.disconnects > 0 -> "Done. Saved, with some data lost. Check the phone."
                else -> "Done. Saved."
            })
            if (r.file != null && !aborted && app.prefs.autoUpload && app.prefs.githubToken.isNotEmpty()) app.uploader.upload(r.file)
        }
    }

    /** For screenshots only. */
    fun showForPreview(u: Ui) { _ui.value = u }

    /** Back to the start state after the result was seen. */
    fun reset() {
        if (!running) _ui.value = Ui()
    }
}
