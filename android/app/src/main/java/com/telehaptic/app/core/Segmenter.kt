package com.telehaptic.app.core

import kotlin.math.sqrt

/**
 * Finds short touches in the stream.
 *
 * A touch is a moment when the field has moved away from rest and then stays almost still
 * (a plateau) for a short time. The check uses the difference of the two sensors when there
 * are two (it barely sees the Earth's field), or the one sensor otherwise. While the hand turns
 * faster than [Params.gyroMaxDps] nothing is registered. One plateau gives one touch; the next
 * one needs a return to rest or a clearly different plateau.
 *
 * Rest follows slow drift: while at rest and still, it creeps towards the current value.
 * Thresholds start from defaults and are set from training (see [Params.fromTraining]).
 */
class Segmenter(var p: Params = Params()) {
    data class Params(
        val windowMs: Int = 80,        // plateau check window
        val holdMs: Int = 40,          // the plateau must last this much beyond the window
        val stableUt: Double = 1.5,    // max sd per axis within the window (stand: holds 0.4 uT)
        val onUt: Double = 6.0,        // distance from rest that counts as a touch
        val gyroMaxDps: Double = 60.0,
        val restAlpha: Double = 0.02,  // rest follow rate per sample while at rest
    ) {
        val offUt get() = onUt * 0.5

        companion object {
            /** onUt = half the distance of the closest key from rest, not below 3 uT. */
            fun fromTraining(rest: DoubleArray, keyMeans: List<DoubleArray>, base: Params = Params()): Params {
                if (keyMeans.isEmpty()) return base
                val dmin = keyMeans.minOf { dist(it, rest) }
                return base.copy(onUt = maxOf(3.0, dmin * 0.5))
            }
        }
    }

    /** A detected touch: time of its plateau start and the mean field over the plateau window. */
    data class Event(val tNs: Long, val x: DoubleArray, val y: DoubleArray)

    enum class State { REST, TOUCH }

    var state = State.REST; private set
    var rest: DoubleArray? = null; private set
    var lastDeviation = 0.0; private set
    var lastStable = false; private set

    private var rateHz = 100.0
    private val bufX = ArrayDeque<DoubleArray>()
    private val bufY = ArrayDeque<DoubleArray>()
    private val bufT = ArrayDeque<Long>()
    private var stableSince = -1L
    private var stableRef: DoubleArray? = null   // window mean when the current still period began
    private var plateau: DoubleArray? = null
    private var emitted = false

    fun setRate(hz: Double) { if (hz > 0) rateHz = hz }

    /** Forget the rest level; the next still moment becomes rest. */
    fun clearRest() { rest = null }

    /** Sets rest explicitly (training measures it). */
    fun setRest(y: DoubleArray) { rest = y.copyOf() }

    fun reset() {
        bufX.clear(); bufY.clear(); bufT.clear()
        state = State.REST; stableSince = -1; stableRef = null; plateau = null; emitted = false
    }

    /** Projection used for the checks: sensor difference if two sensors, else sensor 1. */
    fun yOf(x6: DoubleArray, twoSensors: Boolean) =
        if (twoSensors) DoubleArray(3) { x6[it] - x6[3 + it] } else DoubleArray(3) { x6[it] }

    /** Feed one sample; returns a touch when one is registered. */
    fun add(tNs: Long, x6: DoubleArray, twoSensors: Boolean, gyroDps: Double): Event? {
        val y = yOf(x6, twoSensors)
        val n = maxOf(4, (p.windowMs * rateHz / 1000).toInt())
        bufX.addLast(x6); bufY.addLast(y); bufT.addLast(tNs)
        while (bufY.size > n) { bufX.removeFirst(); bufY.removeFirst(); bufT.removeFirst() }
        if (bufY.size < n) return null

        val mean = DoubleArray(3)
        for (v in bufY) for (i in 0..2) mean[i] += v[i] / n
        var maxSd = 0.0
        for (i in 0..2) {
            var s = 0.0
            for (v in bufY) s += (v[i] - mean[i]) * (v[i] - mean[i])
            maxSd = maxOf(maxSd, sqrt(s / n))
        }
        val stable = maxSd < p.stableUt
        lastStable = stable
        val r = rest ?: run { if (stable) rest = mean.copyOf(); return null }
        val dev = dist(mean, r)
        lastDeviation = dev

        if (!stable) { stableSince = -1; return null }
        // A slow drift keeps each window "still" but is not a plateau: restart the still period
        // whenever the mean has moved away from where it began.
        val ref = stableRef
        if (stableSince < 0 || ref == null || dist(mean, ref) > p.stableUt) { stableSince = tNs; stableRef = mean.copyOf() }

        when (state) {
            State.REST -> {
                if (dev < p.offUt) {
                    for (i in 0..2) r[i] += p.restAlpha * (mean[i] - r[i])
                    return null
                }
                if (dev >= p.onUt && gyroDps < p.gyroMaxDps && tNs - stableSince >= p.holdMs * 1_000_000L) {
                    state = State.TOUCH
                    plateau = mean
                    return event()
                }
            }
            State.TOUCH -> {
                if (dev < p.offUt) { state = State.REST; plateau = null; return null }
                val pl = plateau
                // Slid to another key without releasing: a clearly different, settled plateau.
                if (pl != null && dist(mean, pl) >= p.onUt && gyroDps < p.gyroMaxDps && tNs - stableSince >= p.holdMs * 1_000_000L) {
                    plateau = mean
                    return event()
                }
            }
        }
        return null
    }

    private fun event(): Event {
        val n = bufX.size
        val x = DoubleArray(6)
        for (v in bufX) for (i in 0 until 6) x[i] += v[i] / n
        val y = DoubleArray(3)
        for (v in bufY) for (i in 0..2) y[i] += v[i] / n
        return Event(bufT.first(), x, y)
    }

    companion object {
        fun dist(a: DoubleArray, b: DoubleArray): Double {
            var s = 0.0
            for (i in a.indices) s += (a[i] - b[i]) * (a[i] - b[i])
            return sqrt(s)
        }
    }
}
