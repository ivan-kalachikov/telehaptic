package com.telehaptic.app

import android.os.Handler
import android.os.Looper
import com.telehaptic.app.core.Evaluation
import com.telehaptic.app.core.Lda
import com.telehaptic.app.core.Packet
import com.telehaptic.app.core.Proto
import com.telehaptic.app.core.Segmenter
import kotlin.math.sqrt

/**
 * Live processing on the capsule thread: field in uT, a short history for training windows,
 * and touch detection for quick taps and typing.
 */
class Engine(private val capsule: Capsule) : Capsule.Listener {
    enum class Mode { OFF, TAPS, TYPING }

    /** Touch found by the segmenter; classification is filled in TYPING mode. */
    data class Detected(val tNs: Long, val x: DoubleArray, val result: Lda.Result?)

    private val main = Handler(Looper.getMainLooper())
    val segmenter = Segmenter()
    @Volatile var mode = Mode.OFF; private set
    @Volatile var model: Lda? = null
    @Volatile private var onTouch: ((Detected) -> Unit)? = null
    @Volatile private var owner: Any? = null
    // The motor's field and shaking ring on for a moment after the motor flag ends (the analysis
    // masks 0.1 s after it too): the detector waits that long.
    private var motorQuietUntilNs = 0L

    /** Training and typing take the detector in turn; the last one to claim it gets the touches. */
    fun claim(who: Any, m: Mode, touch: (Detected) -> Unit) { owner = who; onTouch = touch; mode = m }

    /** Gives the detector back, but only if [who] still holds it (a newer owner keeps it). */
    fun release(who: Any) { if (owner === who) { owner = null; mode = Mode.OFF; onTouch = null } }

    fun ownedBy(who: Any) = owner === who

    // Jolt of a touch: high-passed acceleration (each sample minus the mean of the 8 before it, as
    // the analysis's 40 ms moving average), kept for the last second. Cleared around motor pulses:
    // the motor shakes the capsule far harder than a touch.
    private val accWin = ArrayDeque<DoubleArray>()
    private val joltT = LongArray(JOLT_HIST)
    private val joltV = DoubleArray(JOLT_HIST)
    private var joltHead = 0
    private var joltSize = 0
    @Volatile var rejectedNoJolt = 0; private set

    private fun noteAcc(tNs: Long, a: DoubleArray) {
        if (accWin.size >= ACC_MEAN_N) {
            var s = 0.0
            for (i in 0..2) { var m = 0.0; for (v in accWin) m += v[i]; m /= accWin.size; s += (a[i] - m) * (a[i] - m) }
            joltT[joltHead] = tNs; joltV[joltHead] = sqrt(s)
            joltHead = (joltHead + 1) % JOLT_HIST
            if (joltSize < JOLT_HIST) joltSize++
        }
        accWin.addLast(a)
        while (accWin.size > ACC_MEAN_N) accWin.removeFirst()
    }

    /** Largest jolt (m/s²) since [fromNs]; null if there is no accelerometer data for that time. */
    private fun joltSince(fromNs: Long): Double? {
        var best: Double? = null
        for (j in 0 until joltSize) {
            val i = (joltHead - 1 - j + JOLT_HIST) % JOLT_HIST
            if (joltT[i] < fromNs) break
            best = maxOf(best ?: 0.0, joltV[i])
        }
        return best
    }

    private val histT = LongArray(HIST)
    private val histX = Array(HIST) { DoubleArray(6) }
    private var head = 0
    private var size = 0
    @Volatile var twoSensors = false; private set

    init {
        capsule.addListener(this)
    }

    override fun onPacket(p: Packet, rxNs: Long, tNs: LongArray) {
        val mags = capsule.info.mags
        if (mags.size != p.nMags || mags.isEmpty()) return
        twoSensors = mags.size >= 2
        if (capsule.info.rateHz > 0) segmenter.setRate(capsule.info.rateHz.toDouble())
        for ((k, s) in p.samples.withIndex()) {
            val x = histX[head]
            for (i in 0 until 6) x[i] = 0.0
            for (i in 0 until minOf(2, mags.size)) for (a in 0..2) x[3 * i + a] = mags[i].uT(s.mag[3 * i + a])
            histT[head] = tNs[k]
            head = (head + 1) % HIST
            if (size < HIST) size++
            if (mode == Mode.OFF) continue
            // The motor's own field and shaking would disturb the plateau and the gyro gate: skip those samples.
            if (s.motor()) { motorQuietUntilNs = tNs[k] + MOTOR_RING_NS; accWin.clear(); joltSize = 0; continue }
            if (tNs[k] < motorQuietUntilNs) continue
            val g = DoubleArray(3) { s.gyr[it] * capsule.info.gyrMdpsPerLsb / 1000.0 }
            val gyro = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
            if (capsule.info.imu) noteAcc(tNs[k], DoubleArray(3) { s.acc[it] * capsule.info.accMgPerLsb / 1000.0 * G })
            val ev = segmenter.add(tNs[k], x.copyOf(), twoSensors, gyro) ?: continue
            // A thumb that only stops in the air, near the fingers, also makes a settled plateau.
            // A real quick tap jolts the capsule: without a jolt the field must move twice as far.
            val jolt = if (capsule.info.imu) joltSince(ev.tNs - JOLT_BEFORE_NS) else null
            if (!joltGate(jolt, segmenter.lastDeviation, segmenter.p.onUt)) { rejectedNoJolt++; segmenter.cancelTouch(); continue }
            val res = if (mode == Mode.TYPING) model?.classify(ev.x) else null
            val d = Detected(ev.tNs, ev.x, res)
            main.post { onTouch?.invoke(d) }
        }
    }

    /** Median field (6 values, uT) between two phone times; callback on the main thread. Null if no data. */
    fun windowMedian(t0: Long, t1: Long, done: (DoubleArray?) -> Unit) {
        capsule.handler.post {
            val rows = ArrayList<DoubleArray>()
            for (j in 0 until size) {
                val i = (head - 1 - j + HIST) % HIST
                if (histT[i] in t0..t1) rows.add(histX[i].copyOf())
                if (histT[i] < t0) break
            }
            val r = if (rows.size < 5) null else DoubleArray(6) { c -> Evaluation.median(rows.map { it[c] }) }
            main.post { done(r) }
        }
    }

    fun post(r: () -> Unit) = capsule.handler.post(r)

    fun resetSegmenter() = capsule.handler.post { segmenter.reset() }

    /** The segmenter lives on the capsule thread; settings change there too. */
    fun configure(p: Segmenter.Params, rest: DoubleArray?, guard: List<DoubleArray> = emptyList()) = capsule.handler.post {
        segmenter.p = p
        segmenter.reset()
        if (rest != null) segmenter.setRest(rest) else segmenter.clearRest(guard)
    }

    companion object {
        const val HIST = 2000  // 10 s at 200 Hz
        const val MOTOR_RING_NS = 100_000_000L

        const val G = 9.80665
        const val ACC_MEAN_N = 8              // 40 ms at 200 Hz
        const val JOLT_HIST = 400             // 2 s at 200 Hz
        /** The jolt is looked for from this long before the plateau: the rise to it takes about 0.3 s (median, first training). */
        const val JOLT_BEFORE_NS = 600_000_000L
        /**
         * m/s². First training (TRAIN_20261120-144944): 17 of 23 quick taps reached it in the
         * analysis's 0.4 s contact window, and 14% of same-length windows of a free thumb in the air.
         */
        const val JOLT_MS2 = 0.5

        /** A touch counts with a jolt, or without one if the field moved at least twice the threshold. No IMU data: no gate. */
        fun joltGate(jolt: Double?, deviationUt: Double, onUt: Double) = jolt == null || jolt >= JOLT_MS2 || deviationUt >= 2 * onUt
    }
}
