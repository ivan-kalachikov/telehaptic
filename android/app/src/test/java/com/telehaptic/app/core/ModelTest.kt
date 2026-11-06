package com.telehaptic.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ModelTest {
    private val rnd = Random(7)
    private fun gauss() = rnd.nextDouble().let { u -> kotlin.math.sqrt(-2 * kotlin.math.ln(1 - u)) * cos(2 * Math.PI * rnd.nextDouble()) }

    /** Synthetic key centres 12 uT apart in sensor-difference space, both sensors see them. */
    private fun centre(k: String): DoubleArray {
        val i = Keys.ALL.indexOf(k)
        val r = Keys.row(k); val c = Keys.col(k)
        return doubleArrayOf(30.0 + 12 * c, -10.0 + 12 * r, 40.0 + 3 * i, 20.0 + 4 * c, -5.0 + 5 * r, 35.0)
    }

    private fun touches(perKey: Int, sd: Double, group: (Int) -> String, shift: DoubleArray = DoubleArray(6)): List<Touch> =
        (0 until perKey).flatMap { rep ->
            Keys.ALL.map { k -> Touch(k, DoubleArray(6) { centre(k)[it] + shift[it] + sd * gauss() }, group(rep)) }
        }

    @Test fun ldaSeparatesKeys() {
        val train = touches(4, 2.0, { "b$it" })
        val m = Lda.fit(train)!!
        val test = touches(2, 2.0, { "t" })
        val r = Evaluation.on(m, test)
        assertTrue("accuracy ${r.share}", r.share > 0.9)
        val far = m.classify(DoubleArray(6) { 500.0 })
        assertFalse(far.accepted)
    }

    @Test fun leaveGroupOutCountsOnlyTestGroups() {
        val t = touches(2, 2.0, { "hold$it" }) + touches(2, 2.0, { "tap$it" })
        val r = Evaluation.leaveGroupOut(t, FeatureSet.BOTH, setOf("tap0", "tap1"))
        assertEquals(24, r.total)
        assertTrue(r.share > 0.8)
    }

    @Test fun shiftRestoresAfterRewear() {
        val old = touches(4, 1.5, { "b$it" })
        val s = doubleArrayOf(9.0, -7.0, 5.0, 8.0, -6.0, 6.0)   // the capsule sits a bit differently
        val new = touches(1, 1.5, { "r" }, s)
        val before = Evaluation.on(Lda.fit(old)!!, new)
        val after = Evaluation.afterRetrain(old, new, FeatureSet.BOTH)
        assertTrue("before ${before.share} after ${after.share}", after.share > before.share)
        val est = Evaluation.shift(old, new)
        for (i in 0 until 6) assertEquals(s[i], est[i], 2.0)
    }

    @Test fun invertIdentityTimesTwo() {
        val inv = Lda.invert(Array(3) { i -> DoubleArray(3) { j -> if (i == j) 2.0 else 0.0 } })!!
        assertEquals(0.5, inv[1][1], 1e-12)
        assertEquals(0.0, inv[0][1], 1e-12)
    }

    @Test fun segmenterFindsTapsAndIgnoresHandTurns() {
        val seg = Segmenter()
        seg.setRate(100.0)
        val dt = 10_000_000L
        var t = 0L
        val events = ArrayList<Segmenter.Event>()
        val earth = doubleArrayOf(30.0, -20.0, 25.0)
        fun sample(magnet: DoubleArray, earthAngle: Double, gyro: Double) {
            // Earth rotates with the hand and is the same at both sensors; the magnet differs per sensor.
            val e = doubleArrayOf(earth[0] * cos(earthAngle) - earth[1] * sin(earthAngle), earth[0] * sin(earthAngle) + earth[1] * cos(earthAngle), earth[2])
            val x = DoubleArray(6) { i -> e[i % 3] + magnet[i] + 0.4 * gauss() }
            seg.add(t, x, twoSensors = true, gyroDps = gyro)?.let { events.add(it) }
            t += dt
        }
        val none = DoubleArray(6)
        repeat(100) { sample(none, 0.0, 0.0) }                      // rest, 1 s
        // Hand turns by 40 degrees over 1 s: big change of the absolute field, none in the difference.
        repeat(100) { i -> sample(none, Math.toRadians(40.0 * i / 100), 40.0) }
        assertEquals(0, events.size)
        val keys = listOf(doubleArrayOf(20.0, 5.0, -8.0, 4.0, 1.0, -2.0), doubleArrayOf(-6.0, 14.0, 3.0, -1.0, 3.0, 0.0))
        for (k in keys) {
            repeat(6) { i -> sample(DoubleArray(6) { k[it] * (i + 1) / 6.0 }, Math.toRadians(40.0), 5.0) }  // 60 ms rise
            repeat(20) { sample(k, Math.toRadians(40.0), 2.0) }                                              // 200 ms plateau
            repeat(6) { i -> sample(DoubleArray(6) { k[it] * (5 - i) / 6.0 }, Math.toRadians(40.0), 5.0) }  // release
            repeat(40) { sample(none, Math.toRadians(40.0), 0.0) }
        }
        assertEquals(2, events.size)
        // A slow slide (0.5 uT per sample) keeps every window still but is not a touch until it settles.
        val before = events.size
        repeat(120) { i -> sample(DoubleArray(6) { j -> if (j == 0) 0.5 * i else 0.0 }, Math.toRadians(40.0), 0.0) }
        assertEquals(before, events.size)
        repeat(30) { sample(DoubleArray(6) { j -> if (j == 0) 60.0 else 0.0 }, Math.toRadians(40.0), 0.0) }
        assertEquals(before + 1, events.size)
        assertEquals(60.0, events.last().y[0], 1.5)
        // The detected field is the plateau, not the edge.
        val ev = events[0]
        assertNotNull(ev)
        assertEquals(20.0 - 4.0, ev.y[0], 1.5)
    }

    @Test fun t9() {
        val t9 = T9(sequenceOf("hello" to 100L, "good" to 90L, "home" to 80L, "gone" to 30L, "work" to 50L))
        assertEquals("43556", T9.code("hello"))     // h4 e3 l5 l5 o6
        assertEquals(listOf("good", "home", "gone"), t9.candidates("4663"))
        assertEquals("go", t9.candidates("46")[0])     // beginning of "good"
        val typing = Typing(t9)
        "43556".forEach { typing.key(it.toString()) }
        typing.key("0")
        "4663".forEach { typing.key(it.toString()) }
        typing.key("*")
        assertEquals("hello home", typing.display())
        typing.key("#")
        assertEquals("hello goo", typing.display())    // "466" = beginning of "good"
        typing.key("#"); typing.key("#"); typing.key("#")
        assertEquals("hello ", typing.display())        // the digits are gone, the space stays
        typing.key("#")
        assertEquals("hello", typing.display())
    }

    @Test fun t9FindsBeginningsOfLongWords() {
        val t9 = T9(sequenceOf("understanding" to 100L, "under" to 50L))
        val d = T9.code("understa")!!
        assertEquals(listOf("understa"), t9.candidates(d))
    }
}
