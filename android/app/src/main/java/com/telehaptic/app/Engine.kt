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
    /** Touch found by the segmenter; classification is filled in TYPING mode. [jolt]: the largest jolt before it, m/s² (null without an accelerometer). */
    data class Detected(val tNs: Long, val x: DoubleArray, val result: Lda.Result?, val jolt: Double? = null)

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

    // Jolt of a touch: high-passed acceleration, each sample minus the mean of the 8 samples around
    // it (40 ms, centred, as the analysis computes it), so it lags 20 ms. A mean of the samples
    // before it lags behind any smooth movement and took a thumb moving in the air for a jolt: in
    // AIR_table_20261120-155715 that gave 55 touches in 30 s of air, the centred one 4. Kept for the
    // last 2 s; cleared around motor pulses, which shake the capsule far harder than a touch.
    private val accWin = ArrayDeque<DoubleArray>()
    private val accT = ArrayDeque<Long>()
    private val joltT = LongArray(JOLT_HIST)
    private val joltV = DoubleArray(JOLT_HIST)
    private var joltHead = 0
    private var joltSize = 0
    @Volatile var rejectedNoJolt = 0; private set

    private fun noteAcc(tNs: Long, a: DoubleArray) {
        accWin.addLast(a); accT.addLast(tNs)
        while (accWin.size > ACC_MEAN_N) { accWin.removeFirst(); accT.removeFirst() }
        if (accWin.size < ACC_MEAN_N) return
        val c = accWin.elementAt(ACC_MEAN_N / 2)   // numpy convolve "same" with 8 ones: samples c-4 .. c+3
        var s = 0.0
        for (i in 0..2) { var m = 0.0; for (v in accWin) m += v[i]; m /= ACC_MEAN_N; s += (c[i] - m) * (c[i] - m) }
        joltT[joltHead] = accT.elementAt(ACC_MEAN_N / 2); joltV[joltHead] = sqrt(s)
        joltHead = (joltHead + 1) % JOLT_HIST
        if (joltSize < JOLT_HIST) joltSize++
    }

    // A lift-off from a key settles just above it. AIR recordings: a lift-off lands a median 9 uT
    // from the touch it ends, a new tap 47 uT. A touch nearer than FAR_UT to the last one counts
    // only if the field went at least that far from it in between (a double letter: off and back on).
    private var lastTouchY: DoubleArray? = null
    private var leftLastTouch = true
    @Volatile var rejectedNear = 0; private set

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
            if (s.motor()) { motorQuietUntilNs = tNs[k] + MOTOR_RING_NS; accWin.clear(); accT.clear(); joltSize = 0; continue }
            if (tNs[k] < motorQuietUntilNs) continue
            val g = DoubleArray(3) { s.gyr[it] * capsule.info.gyrMdpsPerLsb / 1000.0 }
            val gyro = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
            if (capsule.info.imu) noteAcc(tNs[k], DoubleArray(3) { s.acc[it] * capsule.info.accMgPerLsb / 1000.0 * G })
            val last = lastTouchY
            if (last != null && !leftLastTouch && Segmenter.dist(segmenter.yOf(x, twoSensors), last) >= FAR_UT) leftLastTouch = true
            val ev = segmenter.add(tNs[k], x.copyOf(), twoSensors, gyro) ?: continue
            // A thumb that only stops in the air, near the fingers, also makes a settled plateau. A real
            // tap jolts the capsule. With the stacked magnet the field moves by 10-40 uT with the thumb
            // in the air alone, so a large move without a jolt is not taken either.
            val jolt = if (capsule.info.imu) joltSince(ev.tNs - JOLT_BEFORE_NS) else null
            if (!joltGate(jolt)) { rejectedNoJolt++; segmenter.cancelTouch(); continue }
            if (last != null && !leftLastTouch && Segmenter.dist(ev.y, last) < FAR_UT) { rejectedNear++; continue }
            lastTouchY = ev.y; leftLastTouch = false
            val res = if (mode == Mode.TYPING) model?.classify(ev.x) else null
            val d = Detected(ev.tNs, ev.x, res, jolt)
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
        lastTouchY = null; leftLastTouch = true
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
         * m/s², centred high-pass. AIR_table/AIR_down_20261120 (stacked 8x2): 30 s of the thumb in
         * the air gave 4 and 5 touches through the detector at 0.4 (3 and 2 at 0.5); taps found 22
         * and 23 of 24 (21 and 22 at 0.5).
         */
        const val JOLT_MS2 = 0.4

        /** uT, sensor difference. AIR recordings: 21-22 of 24 taps kept, lift-offs 14-15 -> 3-4 (with the centred jolt). */
        const val FAR_UT = 10.0

        /** A touch counts only with a jolt. No IMU data: no gate. */
        fun joltGate(jolt: Double?) = jolt == null || jolt >= JOLT_MS2
    }
}
