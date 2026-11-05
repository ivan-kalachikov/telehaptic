package com.telehaptic.app.core

import kotlin.random.Random

/** Keypad: finger = row (index, middle, ring, little), phalanx = column from the palm to the tip. */
object Keys {
    val ALL = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
    private val FINGERS = listOf("index", "middle", "ring", "little")
    private val PHALANGES = listOf("phalanx near the palm", "middle phalanx", "phalanx at the tip")

    private val LETTERS = listOf(".", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz", "next", "space", "erase")

    /** What a key does in typing: its T9 letters, or its function. */
    fun letters(key: String) = LETTERS.getOrElse(ALL.indexOf(key)) { "" }

    fun row(key: String) = ALL.indexOf(key) / 3
    fun col(key: String) = ALL.indexOf(key) % 3
    fun finger(key: String) = FINGERS.getOrElse(row(key)) { "" }
    fun phalanx(key: String) = PHALANGES.getOrElse(col(key)) { "" }

    /**
     * [blocks] blocks of 12 keys, each block all keys in random order. Within a block keys
     * never repeat; across the block boundary the first key differs from the previous last one.
     */
    fun sequence(blocks: Int, rnd: Random): List<String> {
        val out = ArrayList<String>(blocks * 12)
        repeat(blocks) {
            var block: List<String>
            do { block = ALL.shuffled(rnd) } while (out.isNotEmpty() && block[0] == out.last())
            out.addAll(block)
        }
        return out
    }
}

/** What a recording is. Names go into file names and meta.json. */
enum class Kind(val code: String) { CALIBRATION("CAL"), KEYS("KEYS"), NO_MAGNET("NOMAG"), TRAIN("TRAIN"), RETRAIN("RETRAIN") }
enum class Posture(val code: String, val title: String) {
    TABLE("table", "Hand on the table, on its edge"),
    DOWN("down", "Arm hanging along the body"),
}

/** One cue of a recording, at a time from the start. */
data class Step(val atMs: Long, val event: String, val trial: Int = -1, val key: String = "", val motorMs: Int = 0)

/** Timings. Holds and rests as on the phone stand (2.5 s / 2 s). */
data class PlanSettings(
    val leadMs: Long = 6000,
    val holdMs: Long = 2500,
    val restMs: Long = 2000,
    val blocks: Int = 4,
    val stillMs: Long = 10000,
    val rotateMs: Long = 40000,
    val motorPulses: Int = 5,
    val motorPulseMs: Int = 300,
)

object Plans {
    /**
     * Keys / no-magnet: trial i is announced shortly after its rest starts, "press" starts
     * the hold, "release" starts the rest.
     */
    fun keys(seq: List<String>, s: PlanSettings): List<Step> {
        val out = ArrayList<Step>()
        val announceDelay = minOf(300L, s.restMs / 4)
        out.add(Step(0, "start"))
        for ((i, k) in seq.withIndex()) {
            val press = s.leadMs + i * (s.holdMs + s.restMs)
            out.add(Step(press - s.restMs + announceDelay, "announce", i, k))
            out.add(Step(press, "press", i, k))
            out.add(Step(press + s.holdMs, "release", i, k))
        }
        out.add(Step(s.leadMs + seq.size * (s.holdMs + s.restMs), "end"))
        return out
    }

    /**
     * Calibration without the magnet: still hand with motor pulses evenly spaced inside the
     * still part (to measure the motor's own field), then slow rotation in all directions.
     */
    fun calibration(s: PlanSettings): List<Step> {
        val out = ArrayList<Step>()
        out.add(Step(0, "start"))
        out.add(Step(s.leadMs, "still_start"))
        val gap = s.stillMs / (s.motorPulses + 1)
        for (p in 1..s.motorPulses) out.add(Step(s.leadMs + p * gap, "motor", p - 1, motorMs = s.motorPulseMs))
        val rot = s.leadMs + s.stillMs
        out.add(Step(rot, "calib_start"))
        out.add(Step(rot + s.rotateMs, "calib_end"))
        out.add(Step(rot + s.rotateMs, "end"))
        return out
    }
}
