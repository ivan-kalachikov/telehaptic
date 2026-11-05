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
    @Volatile var mode = Mode.OFF
    @Volatile var model: Lda? = null
    @Volatile var onTouch: ((Detected) -> Unit)? = null

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
            val g = DoubleArray(3) { s.gyr[it] * capsule.info.gyrMdpsPerLsb / 1000.0 }
            val gyro = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
            val ev = segmenter.add(tNs[k], x.copyOf(), twoSensors, gyro) ?: continue
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
    fun configure(p: Segmenter.Params, rest: DoubleArray?) = capsule.handler.post {
        segmenter.p = p
        segmenter.reset()
        rest?.let { segmenter.setRest(it) }
    }

    companion object {
        const val HIST = 2000  // 10 s at 200 Hz
    }
}
