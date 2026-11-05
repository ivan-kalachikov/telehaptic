package com.telehaptic.app.core

import kotlin.math.sqrt

/**
 * Maps the capsule clock (ticks of 1/32768 s) to the phone clock (elapsedRealtimeNanos).
 *
 * Every received packet gives a pair (phone receive time, capsule time of its last sample).
 * receive - send = transport delay >= 0, and the smallest delays are the packets that went
 * out at once. Per 1-second bin the pair with the smallest offset (receive - capsule time)
 * is kept, and a line offset = a + b * t is fitted through those minima over the last
 * [windowS] seconds. b absorbs the different rates of the two crystals.
 *
 * The constant part of the delay (radio + Android stack) cannot be seen this way; ping RTT / 2
 * bounds it. Everything needed to redo the fit offline is written to packets.csv.
 */
class TimeSync(private val windowS: Double = 20.0) {
    private class Bin(val sec: Long, var tS: Double, var offsetNs: Double)

    private val bins = ArrayDeque<Bin>()
    private var a = 0.0          // offset at t = 0, ns
    private var b = 0.0          // ns of offset per second of capsule time
    private var have = false
    var residualNs = 0.0         // sd of the bin minima around the line
        private set

    fun reset() {
        bins.clear()
        have = false
        a = 0.0; b = 0.0; residualNs = 0.0
    }

    val ready get() = have

    fun add(rxNs: Long, ticks: Long) {
        val tS = ticks / Proto.TICKS_PER_S
        val offset = rxNs - tS * 1e9
        val sec = ticks / 32768
        val last = bins.lastOrNull()
        if (last != null && last.sec == sec) {
            if (offset < last.offsetNs) { last.offsetNs = offset; last.tS = tS }
        } else {
            bins.addLast(Bin(sec, tS, offset))
            while (bins.size > 1 && tS - bins.first().tS > windowS) bins.removeFirst()
        }
        fit()
    }

    private fun fit() {
        // Use only complete bins plus the current one; with < 3 bins fall back to the minimum.
        val n = bins.size
        if (n < 3) {
            val m = bins.minByOrNull { it.offsetNs } ?: return
            a = m.offsetNs; b = 0.0; have = true; residualNs = 0.0
            return
        }
        var st = 0.0; var so = 0.0
        for (x in bins) { st += x.tS; so += x.offsetNs }
        val mt = st / n; val mo = so / n
        var stt = 0.0; var sto = 0.0
        for (x in bins) { stt += (x.tS - mt) * (x.tS - mt); sto += (x.tS - mt) * (x.offsetNs - mo) }
        b = if (stt > 0) sto / stt else 0.0
        a = mo - b * mt
        var ss = 0.0
        for (x in bins) { val r = x.offsetNs - (a + b * x.tS); ss += r * r }
        residualNs = sqrt(ss / n)
        have = true
    }

    /** Phone time (ns) of a capsule time in ticks. */
    fun toPhoneNs(ticks: Long): Long {
        val tS = ticks / Proto.TICKS_PER_S
        return (tS * 1e9 + a + b * tS).toLong()
    }

    /** Slope in ppm: how much faster the phone clock runs than the capsule's. */
    val driftPpm get() = b / 1e3
}

/**
 * Received / lost samples. Sample indices are consecutive from the capsule's start, so a gap
 * is a loss. A smaller index than expected means the capsule restarted.
 */
class StreamStats {
    var received = 0L; private set
    var lost = 0L; private set
    var packets = 0L; private set
    var restarts = 0; private set
    private var expected = -1L

    // For the measured rate: (capsule ticks, cumulative received) at the window start.
    private val window = ArrayDeque<Pair<Long, Long>>()
    private val windowLost = ArrayDeque<Pair<Long, Long>>()

    /** Call when a new connection starts: a gap across a reconnect is not counted as loss. */
    fun newConnection() { expected = -1 }

    /** Returns false if the capsule restarted (index went back). */
    fun onPacket(firstIndex: Long, count: Int, lastTicks: Long): Boolean {
        var ok = true
        if (expected >= 0) {
            when {
                firstIndex > expected -> lost += firstIndex - expected
                firstIndex < expected -> { restarts++; ok = false }
            }
        }
        expected = firstIndex + count
        received += count
        packets++
        window.addLast(lastTicks to received)
        windowLost.addLast(lastTicks to lost)
        while (window.size > 2 && lastTicks - window.first().first > 10 * 32768) window.removeFirst()
        while (windowLost.size > 2 && lastTicks - windowLost.first().first > 10 * 32768) windowLost.removeFirst()
        if (!ok) { window.clear(); windowLost.clear() }
        return ok
    }

    /** Samples per second of capsule time over the last ~10 s (same window as the loss). */
    fun rateHz(): Double {
        if (window.size < 2) return 0.0
        val (t0, r0) = window.first()
        val (t1, r1) = window.last()
        val dt = (t1 - t0) / Proto.TICKS_PER_S
        return if (dt > 0.5) (r1 - r0) / dt else 0.0
    }

    /** Share of lost samples over the last ~10 s, 0..1. */
    fun recentLoss(): Double {
        if (windowLost.size < 2) return 0.0
        val l = windowLost.last().second - windowLost.first().second
        val r = window.lastOrNull()?.second ?: 0L
        val r0 = window.firstOrNull()?.second ?: 0L
        val total = l + (r - r0)
        return if (total > 0) l.toDouble() / total else 0.0
    }
}
