package com.telehaptic.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class CoreTest {
    /** Builds a packet exactly as firmware/telehaptic/telehaptic.ino does. */
    private fun packet(seq: Int, first: Long, n: Int, samples: List<Triple<Long, Int, IntArray>>): ByteArray {
        val size = Proto.HEADER + samples.size * Proto.sampleSize(n)
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(1).put((n or 4).toByte()).putShort(seq.toShort()).putInt(first.toInt())
        for ((t, flags, values) in samples) {
            bb.putInt(t.toInt()).putShort(flags.toShort())
            for (v in values) bb.putShort(v.toShort())
        }
        return bb.array()
    }

    @Test fun parsesPacket() {
        val v1 = intArrayOf(-30000, 1, 2, 3, 4, 5, 10, 11, 12, 20, 21, -22)  // 2 mags + gyr + acc
        val v2 = IntArray(12) { it * 100 }
        val b = packet(7, 0xFFFFFFFEL, 2, listOf(Triple(0xFFFFFFF0L, Proto.FLAG_MOTOR or 1, v1), Triple(5L, 0, v2)))
        assertEquals(8 + 2 * 30, b.size)
        val p = PacketParser.parse(b)!!
        assertEquals(7, p.seq)
        assertEquals(2, p.nMags)
        assertTrue(p.imu)
        assertEquals(2, p.samples.size)
        val s = p.samples[0]
        assertEquals(0xFFFFFFFEL, s.index)
        assertEquals(0xFFFFFFF0L, s.ticks)
        assertTrue(s.motor())
        assertEquals(-30000, s.mag[0])
        assertEquals(5, s.mag[5])
        assertEquals(10, s.gyr[0])
        assertEquals(-22, s.acc[2])
        assertEquals(0xFFFFFFFFL, p.samples[1].index)
        assertEquals(1100, p.samples[1].acc[2])
        assertEquals(600, p.samples[1].gyr[0])
    }

    @Test fun rejectsBadPackets() {
        assertNull(PacketParser.parse(ByteArray(3)))
        val b = packet(0, 0, 2, listOf(Triple(0L, 0, IntArray(12))))
        assertNull(PacketParser.parse(b.copyOf(b.size - 1)))
        b[0] = 2
        assertNull(PacketParser.parse(b))
    }

    @Test fun commands() {
        assertEquals(listOf(0x01, 100, 0), Proto.cmdRate(100).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x10, 0xD0, 0x07), Proto.cmdMotor(5000).map { it.toInt() and 0xFF })  // clamped to 2000
        assertEquals(listOf(0x30, 0x78, 0x56, 0x34, 0x12), Proto.cmdPing(0x12345678).map { it.toInt() and 0xFF })
    }

    @Test fun infoLines() {
        val c = CapsuleInfo()
        listOf(
            "FW 0.1.3", "BOOT reason=power_on", "SCAN begin mux=yes",
            "SCAN ch0 0x2C QMC5883P mag1", "SCAN ch1 EMPTY HINT_PULLUPS", "SCAN ch2 EMPTY HINT_PULLUPS",
            "SCAN end mags=1",
            "CFG rate=100 range=30 osr=8/8 n=1 imu=1 mag1=QMC5883P@ch0,1000",
            "BAT mv=3987",
        ).forEach { c.apply(it) }
        assertEquals("0.1.3", c.firmware)
        assertEquals(true, c.muxPresent)
        assertTrue(c.scanDone)
        assertEquals(1, c.mags.size)
        assertEquals("ch0", c.mags[0].where)
        assertEquals(1000.0, c.mags[0].lsbPerGauss, 0.0)
        assertEquals(10.0, c.mags[0].uT(100), 1e-9)   // 100 units at 1000 LSB/G = 0.1 G = 10 uT
        assertEquals(3987, c.batteryMv)
        val problems = c.problems()
        assertEquals(1, problems.size)                // ch2 is the spare, not a problem
        assertTrue(problems[0].contains("Канал 1"))
        assertTrue(problems[0].contains("подтяжки"))
        assertEquals(123L to 456L, c.apply("PONG 123 456"))
        assertNull(c.apply("MOTOR on idx=5 t=6 ms=40"))
    }

    @Test fun scanTexts() {
        assertTrue(ScanText.describe("SCAN BUS_STUCK sda=0 scl=1").contains("SDA"))
        assertTrue(ScanText.describe("SCAN ch3 OTHER 0x48").contains("0x48"))
        assertNotNull(ScanText.problem("SCAN main 0x1E HMC5883L UNSUPPORTED"))
        assertNull(ScanText.problem("SCAN ch0 0x2C QMC5883P mag1"))
    }

    @Test fun sequenceHasNoRepeats() {
        val rnd = Random(1)
        repeat(200) {
            val s = Keys.sequence(4, rnd)
            assertEquals(48, s.size)
            for (b in 0 until 4) assertEquals(Keys.ALL.toSet(), s.subList(12 * b, 12 * b + 12).toSet())
            for (i in 1 until s.size) assertFalse("repeat at $i", s[i] == s[i - 1])
        }
        assertEquals("мизинец", Keys.finger("#"))
        assertEquals("фаланга у кончика", Keys.phalanx("#"))
        assertEquals("фаланга у ладони", Keys.phalanx("4"))
    }

    @Test fun plans() {
        val st = PlanSettings()
        val keys = Plans.keys(Keys.sequence(4, Random(2)), st)
        assertEquals(1 + 48 * 3 + 1, keys.size)
        assertEquals(6000L, keys.first { it.event == "press" }.atMs)
        assertEquals(6000L + 48 * 4500, keys.last().atMs)
        val cal = Plans.calibration(st)
        assertEquals(5, cal.count { it.event == "motor" })
        assertTrue(cal.filter { it.event == "motor" }.all { it.atMs in 6000L until 16000L })
        assertEquals(56000L, cal.last().atMs)
    }

    @Test fun timeSyncFollowsOffsetAndDrift() {
        val ts = TimeSync()
        val rnd = Random(3)
        val offsetNs = 5_000_000_000.0
        val ppm = 30.0
        // 30 s of packets every 30 ms; delays 2..17 ms, ~10% go out at once (2 ms).
        var t = 0.0
        while (t < 30.0) {
            val ticks = (t * 32768).toLong()
            val trueNs = offsetNs + t * 1e9 * (1 + ppm * 1e-6)
            val delay = if (rnd.nextDouble() < 0.1) 2e6 else 2e6 + rnd.nextDouble() * 15e6
            ts.add((trueNs + delay).toLong(), ticks)
            t += 0.03
        }
        val ticks = (25.0 * 32768).toLong()
        val expected = offsetNs + 25.0 * 1e9 * (1 + ppm * 1e-6) + 2e6   // constant delay stays in
        val err = ts.toPhoneNs(ticks) - expected
        assertTrue("error ${err / 1e6} ms", kotlin.math.abs(err) < 1e6)
        assertEquals(ppm, ts.driftPpm, 10.0)
    }

    @Test fun streamStats() {
        val s = StreamStats()
        assertTrue(s.onPacket(0, 7, 100))
        assertTrue(s.onPacket(7, 7, 200))
        assertTrue(s.onPacket(21, 7, 300))      // 7 lost
        assertEquals(7L, s.lost)
        assertEquals(21L, s.received)
        assertFalse(s.onPacket(0, 7, 10))       // capsule restarted
        assertEquals(1, s.restarts)
        s.newConnection()
        assertTrue(s.onPacket(500, 7, 50000))   // gap across a reconnect is not a loss
        assertEquals(7L, s.lost)
    }

    @Test fun recordNames() {
        assertEquals("KEYS_table_rewear_20261111-153012", RecordFormat.name(Kind.KEYS, Posture.TABLE, true, "20261111-153012"))
        assertEquals("CAL_20261111-153012", RecordFormat.name(Kind.CALIBRATION, Posture.TABLE, true, "20261111-153012"))
        assertEquals("idx,t_xiao,t_ns,m1x,m1y,m1z,m2x,m2y,m2z,gx,gy,gz,ax,ay,az,flags", RecordFormat.samplesHeader(2))
    }
}
