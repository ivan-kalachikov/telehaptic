package com.telehaptic.app.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** BLE formats of the capsule. Same as firmware/telehaptic/protocol.h and docs/protocol.md. */
object Proto {
    const val SERVICE = "7e1a0001-5c2d-4b8e-9f3a-1d6c2b8e4a10"
    const val DATA = "7e1a0002-5c2d-4b8e-9f3a-1d6c2b8e4a10"
    const val CTRL = "7e1a0003-5c2d-4b8e-9f3a-1d6c2b8e4a10"
    const val INFO = "7e1a0004-5c2d-4b8e-9f3a-1d6c2b8e4a10"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    const val PKT_VERSION = 1
    const val HEADER = 8
    const val TICKS_PER_S = 32768.0

    const val FLAG_MOTOR = 1 shl 9
    const val FLAG_OVFL = 1 shl 10
    const val FLAG_STALE = 1 shl 11
    const val FLAG_CONFIG = 1 shl 12
    const val FLAG_I2C_ERR = 1 shl 13
    fun satBit(mag: Int, axis: Int) = 1 shl (3 * mag + axis)

    // IMU scales (firmware imu.h, from the Seeed LSM6DS3 library's calcAccel / calcGyro).
    const val ACC_MG_PER_LSB = 0.244
    const val GYR_MDPS_PER_LSB = 17.5

    fun sampleSize(nMags: Int) = 18 + 6 * nMags

    private fun u16(op: Int, v: Int) = byteArrayOf(op.toByte(), (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    fun cmdRate(hz: Int) = u16(0x01, hz)
    fun cmdRange(gauss: Int) = byteArrayOf(0x02, gauss.toByte())
    fun cmdOsr(a: Int, b: Int) = byteArrayOf(0x03, a.toByte(), b.toByte())
    fun cmdMotor(ms: Int) = u16(0x10, ms.coerceIn(1, 2000))
    fun cmdRescan() = byteArrayOf(0x20)
    fun cmdStatus() = byteArrayOf(0x21)
    fun cmdPing(token: Long): ByteArray {
        val t = token and 0xFFFFFFFFL
        return byteArrayOf(0x30, t.toByte(), (t shr 8).toByte(), (t shr 16).toByte(), (t shr 24).toByte())
    }
}

/** One sample as sent by the capsule; magnetometer and IMU values in raw units. */
class Sample(
    val index: Long,       // u32 from the capsule
    val ticks: Long,       // u32 XIAO clock, 1/32768 s
    val flags: Int,
    val mag: IntArray,     // 3 * nMags: x, y, z of mag1, then mag2 ...
    val gyr: IntArray,
    val acc: IntArray,
) {
    val nMags get() = mag.size / 3
    fun motor() = flags and Proto.FLAG_MOTOR != 0
}

class Packet(val seq: Int, val firstIndex: Long, val nMags: Int, val imu: Boolean, val samples: List<Sample>)

object PacketParser {
    /** Null if the packet is malformed or of an unknown version. */
    fun parse(b: ByteArray): Packet? {
        if (b.size < Proto.HEADER) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val version = bb.get().toInt() and 0xFF
        if (version != Proto.PKT_VERSION) return null
        val layout = bb.get().toInt() and 0xFF
        val n = layout and 0x3
        val imu = layout and 0x4 != 0
        val seq = bb.short.toInt() and 0xFFFF
        val first = bb.int.toLong() and 0xFFFFFFFFL
        val size = Proto.sampleSize(n)
        val body = b.size - Proto.HEADER
        if (body % size != 0) return null
        val count = body / size
        val list = ArrayList<Sample>(count)
        for (k in 0 until count) {
            val t = bb.int.toLong() and 0xFFFFFFFFL
            val flags = bb.short.toInt() and 0xFFFF
            val mag = IntArray(3 * n) { bb.short.toInt() }
            val gyr = IntArray(3) { bb.short.toInt() }
            val acc = IntArray(3) { bb.short.toInt() }
            list.add(Sample((first + k) and 0xFFFFFFFFL, t, flags, mag, gyr, acc))
        }
        return Packet(seq, first, n, imu, list)
    }
}

/** Magnetometer description from the capsule's CFG line. */
data class MagInfo(val index: Int, val chip: String, val where: String, val lsbPerGauss: Double) {
    /** Raw units to microtesla. */
    fun uT(raw: Int) = raw * 100.0 / lsbPerGauss
}
