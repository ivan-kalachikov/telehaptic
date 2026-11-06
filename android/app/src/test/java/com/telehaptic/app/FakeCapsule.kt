package com.telehaptic.app

import android.os.Looper
import android.os.SystemClock
import com.telehaptic.app.core.Keys
import com.telehaptic.app.core.Proto
import org.robolectric.Shadows.shadowOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlin.random.Random

/**
 * A capsule without Bluetooth: two magnetometers and an IMU at 200 Hz, packed exactly as the
 * firmware packs them (docs/protocol.md), and a thumb that a test moves between keys.
 *
 * Field model (µT): Earth + a far-magnet part at rest; each key adds its own vector to both
 * sensors, with a small random change per touch (the thumb never lands on exactly the same
 * spot) and sensor noise. The motor adds its own field and shaking while it runs and rings
 * for a short time after it stops, which the firmware does not flag.
 */
class FakeCapsule(private val app: App, seed: Int = 1) {
    val rateHz = 200
    private val rnd = Random(seed)
    private val earth = doubleArrayOf(18.0, -6.0, 41.0, 17.0, -5.0, 42.0)
    private val keyField = Keys.ALL.associateWith { DoubleArray(6) { (rnd.nextDouble() - 0.5) * 120 } }
    var noiseUt = 0.3
    var touchJitterUt = 1.2
    /** Added to everything, e.g. the capsule sitting a bit differently after re-wearing. */
    var offset = DoubleArray(6)

    private var index = 0L
    private var seq = 0
    private var startNs = SystemClock.elapsedRealtimeNanos()
    private var motorUntilNs = 0L
    private var ringUntilNs = 0L
    var motorPulses = 0; private set
    /** False: the capsule is out of reach, no packets arrive (time still passes). */
    var sending = true

    // The thumb: where it is going and how far along the way (50 ms per move).
    private var from = DoubleArray(6)
    private var to = DoubleArray(6)
    private var moveStartNs = 0L
    var key: String? = null; private set

    init {
        app.capsule.testSink = { cmd ->
            if (cmd[0].toInt() == 0x10) {
                val ms = (cmd[1].toInt() and 0xFF) or ((cmd[2].toInt() and 0xFF) shl 8)
                val now = SystemClock.elapsedRealtimeNanos()
                motorUntilNs = now + ms * 1_000_000L
                ringUntilNs = motorUntilNs + 60_000_000L
                motorPulses++
            }
        }
        app.capsule.testLink(true)
        info("CFG rate=$rateHz range=30 osr=8/8 n=2 imu=1 mag1=QMC5883P@ch0,1000 mag2=QMC5883P@ch1,1000")
        info("IMU acc_mg_per_lsb=0.244 gyr_mdps_per_lsb=17.5 odr=208")
        to = earth.copyOf(); from = to.copyOf()
    }

    fun info(line: String) {
        app.capsule.testNotify(Proto.INFO, line.toByteArray(), SystemClock.elapsedRealtimeNanos())
        idleCapsule()
    }

    /** Thumb onto a key (null = away from the fingers). */
    fun touch(k: String?) {
        if (k == key) return
        val now = SystemClock.elapsedRealtimeNanos()
        from = fieldAt(now)
        to = if (k == null) earth.copyOf() else DoubleArray(6) { earth[it] + keyField.getValue(k)[it] + rnd.nextGaussian() * touchJitterUt }
        moveStartNs = now
        key = k
    }

    private fun fieldAt(t: Long): DoubleArray {
        val a = ((t - moveStartNs) / 50e6).coerceIn(0.0, 1.0)
        val s = a * a * (3 - 2 * a)
        return DoubleArray(6) { from[it] + (to[it] - from[it]) * s }
    }

    /** Runs the world for [ms]: packets of 4 samples every 20 ms, then the app's threads catch up. */
    fun run(ms: Long, each: () -> Unit = {}) {
        var left = ms
        while (left > 0) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            val now = SystemClock.elapsedRealtimeNanos()
            if (sending) packet(now)
            idleCapsule()
            shadowOf(Looper.getMainLooper()).idle()
            each()
            left -= 20
        }
    }

    private fun packet(now: Long) {
        val n = 4
        val b = ByteBuffer.allocate(Proto.HEADER + n * Proto.sampleSize(2)).order(ByteOrder.LITTLE_ENDIAN)
        b.put(Proto.PKT_VERSION.toByte()); b.put((2 or 4).toByte()); b.putShort(seq.toShort()); b.putInt(index.toInt())
        for (k in 0 until n) {
            val t = now - (n - 1 - k) * 1_000_000_000L / rateHz
            val ticks = ((t - startNs) * Proto.TICKS_PER_S / 1e9).toLong() + 1000
            val motor = t < motorUntilNs
            val ringing = !motor && t < ringUntilNs
            val f = fieldAt(t)
            b.putInt(ticks.toInt())
            b.putShort((if (motor) Proto.FLAG_MOTOR else 0).toShort())
            for (i in 0 until 6) {
                var v = f[i] + offset[i] + rnd.nextGaussian() * noiseUt
                if (motor) v += (if (i < 3) 4.0 else 1.0) + rnd.nextGaussian() * 3
                if (ringing) v += rnd.nextGaussian() * 1.5
                b.putShort(Math.round(v * 10).toShort())   // 1000 LSB/G: 1 LSB = 0.1 µT
            }
            val shake = if (motor || ringing) 2000.0 else 20.0
            for (i in 0 until 3) b.putShort((rnd.nextGaussian() * shake).toInt().toShort())
            for (i in 0 until 3) b.putShort(((if (i == 2) 4098 else 0) + rnd.nextGaussian() * shake).toInt().toShort())
        }
        index += n; seq = (seq + 1) and 0xFFFF
        app.capsule.testNotify(Proto.DATA, b.array(), now)
    }

    fun idleCapsule() = shadowOf(app.capsule.handler.looper).idle()

    private fun Random.nextGaussian(): Double {
        val u = nextDouble().coerceAtLeast(1e-12)
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * nextDouble())
    }
}
