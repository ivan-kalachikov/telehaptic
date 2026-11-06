package com.telehaptic.app

import android.os.SystemClock
import com.telehaptic.app.core.T9
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * The whole path without hardware: protocol packets from a fake capsule → parser → clock sync →
 * detector → training → model → typing with T9, with a simulated person who follows the cues.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EndToEndTest {
    private val app get() = App.get()

    /** A person who follows the training cues: touches on the beep, quick taps a moment after each cue. */
    private inner class Trainee(val cap: FakeCapsule) {
        var lastStep: Training.HoldStep? = null
        var stepAt = 0L
        var lastIndex = -1
        var cueAt = 0L
        var skipFirstTap = false

        fun act() {
            val u = app.training.ui.value
            val now = SystemClock.elapsedRealtime()
            when (u.phase) {
                Training.Phase.HOLDS -> {
                    if (u.holdStep != lastStep) { lastStep = u.holdStep; stepAt = now }
                    val since = now - stepAt
                    cap.touch(if (u.holdStep == Training.HoldStep.HOLD && since > 250) u.key else if (u.holdStep == Training.HoldStep.HOLD) cap.key else if (since > 150) null else cap.key)
                }
                Training.Phase.TAPS -> {
                    if (u.index != lastIndex || u.paused) { lastIndex = u.index; cueAt = now; if (!u.paused) cap.touch(null) }
                    val since = now - cueAt
                    cap.touch(if (since in 600..900) u.key else null)
                }
                else -> cap.touch(null)
            }
        }
    }

    private fun train(cap: FakeCapsule): Training.Ui {
        cap.run(2000)                         // stream settles, clock sync has a few bins
        app.training.startTraining()
        val who = Trainee(cap)
        var guard = 0
        while (app.training.ui.value.phase != Training.Phase.RESULT && app.training.ui.value.error.isEmpty() && guard++ < 10_000)
            cap.run(20) { who.act() }
        // The recording is zipped on another thread.
        repeat(100) { if (app.recorder.isOpen || recordings().isEmpty()) { Thread.sleep(20); cap.run(20) } }
        return app.training.ui.value
    }

    private fun recordings() = File(app.filesDir, "recordings").listFiles()?.filter { it.name.endsWith(".zip") } ?: emptyList()

    private fun type(cap: FakeCapsule, keys: String) {
        repeat(200) { if (!app.typing.ui.value.ready) { Thread.sleep(20); cap.run(20) } }
        assertTrue("dictionary loaded", app.typing.ui.value.ready)
        app.typing.start()
        cap.run(1000)                         // rest is measured again from the first still moment
        for (k in keys) {
            val r0 = app.typing.ui.value.rejected
            cap.touch(k.toString()); cap.run(300)
            cap.touch(null); cap.run(400)
            if (app.typing.ui.value.rejected != r0) println("rejected touch on $k")
        }
        app.typing.stop()
    }

    @Test fun trainThenType() {
        val cap = FakeCapsule(app)
        val u = train(cap)
        assertEquals("training ended with: ${u.error}", Training.Phase.RESULT, u.phase)
        val r = u.report!!
        println("training: ${r.correct}/${r.total}, confusions ${r.confusions}, motor pulses ${cap.motorPulses}")
        assertEquals("every quick tap was found and tested", 24, r.total)
        assertTrue("held-out taps mostly right: ${r.correct}/${r.total}", r.correct >= 22)
        assertEquals("one click per detected tap", 24, cap.motorPulses)
        assertTrue(app.brain.summary.value.trained)

        // The training recording is a complete zip.
        val zip = recordings().single { it.name.contains("TRAIN") }
        ZipFile(zip).use { z ->
            for (f in listOf("samples.csv", "events.csv", "packets.csv", "sync.csv", "info.csv", "meta.json"))
                assertTrue("$f in ${zip.name}", z.getEntry(f) != null)
            val events = z.getInputStream(z.getEntry("events.csv")).bufferedReader().readLines()
            assertEquals(24, events.count { it.contains(",tap_plateau,") })
            assertEquals(24, events.count { it.contains(",press,") })
        }

        // Typing: "hello world" on the keypad, T9 picks the words.
        val digits = T9.code("hello")!! + "0" + T9.code("world")!! + "0"
        type(cap, digits)
        val t = app.typing.ui.value
        println("typed: '${t.text}', rejected ${t.rejected}, motor ${cap.motorPulses}")
        assertEquals("hello world ", t.text)
        assertEquals(0, t.rejected)
    }

    @Test fun capsuleLostDuringTapsPausesAndGoesOn() {
        val cap = FakeCapsule(app)
        cap.run(2000)
        app.training.startTraining()
        val who = Trainee(cap)
        var guard = 0
        while (app.training.ui.value.phase != Training.Phase.TAPS && guard++ < 10_000) cap.run(20) { who.act() }
        cap.run(3000) { who.act() }
        val before = app.training.ui.value.index
        cap.sending = false
        app.capsule.testLink(false); cap.idleCapsule(); cap.run(20)
        val paused = app.training.ui.value
        assertTrue("paused on link loss", paused.paused)
        assertTrue(paused.hint, paused.hint.contains("lost"))
        // Touches while paused are not taken.
        cap.touch("5"); cap.run(400); cap.touch(null); cap.run(400)
        assertEquals(before, app.training.ui.value.index)
        // Reconnected but no data yet: not announced as back, still paused.
        app.capsule.testLink(true); cap.idleCapsule(); cap.run(1000)
        assertTrue("no data yet: still paused", app.training.ui.value.paused)
        assertTrue(app.training.ui.value.hint, app.training.ui.value.hint.contains("lost"))
        // Data again: training goes on by itself after a short countdown, no tap on the phone needed.
        cap.sending = true; cap.run(500)
        assertTrue("still paused during the countdown", app.training.ui.value.paused)
        cap.run(3000)
        assertTrue("resumed by itself", !app.training.ui.value.paused)
        who.lastIndex = -1
        cap.run(3000) { who.act() }
        assertTrue("taps go on", app.training.ui.value.index > before)
        app.training.cancel()
    }

    @Test fun pausedDuringHoldAsksTheKeyAgain() {
        val cap = FakeCapsule(app)
        cap.run(2000)
        app.training.startTraining()
        val who = Trainee(cap)
        var guard = 0
        while (app.training.ui.value.holdStep != Training.HoldStep.HOLD && guard++ < 10_000) cap.run(20) { who.act() }
        val total = app.training.ui.value.total
        app.training.pause(true); cap.run(300); app.training.pause(false)
        cap.run(2000) { who.act() }
        assertEquals("the interrupted key comes again", total + 1, app.training.ui.value.total)
        app.training.cancel()
    }
}
