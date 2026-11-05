package com.telehaptic.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.telehaptic.app.core.Kind
import com.telehaptic.app.core.Packet
import com.telehaptic.app.core.PlanSettings
import com.telehaptic.app.core.Posture
import com.telehaptic.app.core.Proto
import com.telehaptic.app.core.RecordFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What is being recorded; goes into meta.json. */
data class RecordingSpec(
    val kind: Kind,
    val posture: Posture?,
    val rewear: Boolean,
    val magnet: String,
    val distanceMm: Int,
    val note: String,
    val seed: Long,
    val sequence: List<String>,
    val plan: PlanSettings,
)

/** Summary shown after a recording. */
data class RecordingResult(
    val file: File?,
    val error: String?,
    val aborted: Boolean,
    val durationS: Double,
    val samples: Long,
    val lost: Long,
    val saturated: Long,
    val stale: Long,
    val i2cErrors: Long,
    val disconnects: Int,
) {
    val lossShare get() = if (samples + lost > 0) lost.toDouble() / (samples + lost) else 0.0
}

/**
 * Writes one recording: samples.csv, events.csv, packets.csv, sync.csv, info.csv, meta.json,
 * zipped into files/recordings/<id>.zip. All file work runs on the capsule thread, the same
 * thread that delivers the stream, so nothing needs locking.
 */
class Recorder(private val ctx: Context, private val capsule: Capsule, private val log: AppLog) : Capsule.Listener {
    val dir = File(ctx.filesDir, "recordings").apply { mkdirs() }
    private val h = capsule.handler
    private val main = Handler(Looper.getMainLooper())

    private var open = false
    private var spec: RecordingSpec? = null
    private var id = ""
    private var work: File? = null
    private var samples: BufferedWriter? = null
    private var events: BufferedWriter? = null
    private var packets: BufferedWriter? = null
    private var syncW: BufferedWriter? = null
    private var infoW: BufferedWriter? = null
    private var nMags = 0
    private val sb = StringBuilder(160)

    private var startNs = 0L
    private var startWall = ""
    private var startCapsule = JSONObject()
    private var count = 0L
    private var skipped = 0L
    private var lost = 0L
    private var expected = -1L
    private var saturated = 0L
    private val satPerAxis = LongArray(9)
    private var stale = 0L
    private var i2c = 0L
    private var motorSamples = 0L
    private var disconnects = 0
    private var minBattery = Int.MAX_VALUE
    private var error: String? = null

    init {
        capsule.addListener(this)
    }

    val isOpen get() = open

    fun start(s: RecordingSpec, onStarted: (String) -> Unit) {
        val now = SystemClock.elapsedRealtimeNanos()
        val date = Date()
        h.post {
            spec = s
            id = RecordFormat.name(s.kind, s.posture, s.rewear, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(date))
            startWall = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(date)
            startNs = now
            val w = File(ctx.filesDir, "rec/$id").apply { deleteRecursively(); mkdirs() }
            work = w
            nMags = capsule.info.mags.size
            count = 0; skipped = 0; lost = 0; expected = -1; saturated = 0; satPerAxis.fill(0)
            stale = 0; i2c = 0; motorSamples = 0; disconnects = 0; minBattery = Int.MAX_VALUE; error = null
            startCapsule = capsuleJson()
            try {
                samples = writer(File(w, "samples.csv"), RecordFormat.samplesHeader(nMags))
                events = writer(File(w, "events.csv"), RecordFormat.EVENTS_HEADER)
                packets = writer(File(w, "packets.csv"), RecordFormat.PACKETS_HEADER)
                syncW = writer(File(w, "sync.csv"), RecordFormat.SYNC_HEADER)
                infoW = writer(File(w, "info.csv"), RecordFormat.INFO_HEADER)
                open = true
                log.i("rec", "start $id, magnetometers $nMags")
            } catch (e: Exception) {
                error = "Не удалось начать запись: $e"
                log.w("rec", "start", e)
            }
            main.post { onStarted(id) }
        }
    }

    /** Time is taken at the call, the write happens on the capsule thread. */
    fun event(name: String, trial: Int = -1, key: String = "") {
        val t = SystemClock.elapsedRealtimeNanos()
        h.post {
            if (!open) return@post
            write(events, "$t,$name,$trial,$key\n")
        }
    }

    private fun writer(f: File, header: String) = BufferedWriter(FileWriter(f), 1 shl 16).apply { write(header); write("\n") }

    private fun write(w: BufferedWriter?, s: String) {
        try { w?.write(s) } catch (e: Exception) { if (error == null) error = "Ошибка записи: $e" }
    }

    // ------------------------------------------------------------ stream (capsule thread)

    override fun onPacket(p: Packet, rxNs: Long, tNs: LongArray) {
        if (!open) return
        write(packets, "$rxNs,${p.seq},${p.firstIndex},${p.samples.size},${p.samples.last().ticks}\n")
        if (expected >= 0 && p.firstIndex > expected) lost += p.firstIndex - expected
        expected = p.firstIndex + p.samples.size
        if (p.nMags != nMags) { skipped += p.samples.size; return }   // layout changed (rescan) mid-recording
        for ((k, s) in p.samples.withIndex()) {
            RecordFormat.sampleLine(s, tNs[k], sb)
            write(samples, sb.toString())
            count++
            val satBits = s.flags and 0x1FF
            if (satBits != 0) {
                saturated++
                for (b in 0 until 9) if (satBits and (1 shl b) != 0) satPerAxis[b]++
            }
            if (s.flags and Proto.FLAG_STALE != 0) stale++
            if (s.flags and Proto.FLAG_I2C_ERR != 0) i2c++
            if (s.motor()) motorSamples++
        }
    }

    override fun onInfo(rxNs: Long, line: String) {
        if (!open) return
        write(infoW, "$rxNs,${RecordFormat.csvQuote(line)}\n")
        if (line.startsWith("BAT ")) line.substringAfter("mv=").toIntOrNull()?.let { if (it in 1 until minBattery) minBattery = it }
    }

    override fun onPong(sentNs: Long, rxNs: Long, token: Long, ticks: Long) {
        if (!open) return
        write(syncW, "$sentNs,$rxNs,$token,$ticks\n")
    }

    override fun onLink(connected: Boolean) {
        if (!open) return
        val t = SystemClock.elapsedRealtimeNanos()
        write(events, "$t,${if (connected) "link_up" else "link_down"},-1,\n")
        if (!connected) { disconnects++; expected = -1 }
    }

    // ------------------------------------------------------------ finish

    fun finish(aborted: Boolean, done: (RecordingResult) -> Unit) {
        val endNs = SystemClock.elapsedRealtimeNanos()
        h.post {
            val s = spec
            val w = work
            var file: File? = null
            if (open && s != null && w != null) {
                open = false
                for (x in listOf(samples, events, packets, syncW, infoW)) try { x?.close() } catch (_: Exception) {}
                try {
                    File(w, "meta.json").writeText(meta(s, aborted, endNs).toString(2))
                    val z = File(dir, id + (if (aborted) "_ABORTED" else "") + ".zip")
                    zip(w, z)
                    w.deleteRecursively()
                    file = z
                    log.i("rec", "saved ${z.name}, ${z.length()} bytes, samples $count, lost $lost")
                } catch (e: Exception) {
                    error = "Не удалось сохранить запись: $e"
                    log.w("rec", "save", e)
                }
            }
            val r = RecordingResult(file, error, aborted, (endNs - startNs) / 1e9, count, lost, saturated, stale, i2c, disconnects)
            main.post { done(r) }
        }
    }

    private fun capsuleJson(): JSONObject {
        val i = capsule.info
        val j = JSONObject()
        j.put("firmware", i.firmware)
        j.put("boot_reason", i.bootReason)
        j.put("mux_present", i.muxPresent ?: JSONObject.NULL)
        j.put("scan", JSONArray(i.scan))
        j.put("config_line", i.lastConfigLine)
        j.put("rate_hz", i.rateHz)
        j.put("range_gauss", i.rangeG)
        j.put("osr", i.osr)
        j.put("imu", i.imu)
        val mags = JSONArray()
        for (m in i.mags) mags.put(JSONObject().put("index", m.index).put("chip", m.chip).put("where", m.where).put("lsb_per_gauss", m.lsbPerGauss))
        j.put("magnetometers", mags)
        j.put("battery_mv", i.batteryMv)
        j.put("stats_line", i.stats)
        return j
    }

    private fun meta(s: RecordingSpec, aborted: Boolean, endNs: Long): JSONObject {
        val m = JSONObject()
        m.put("format", "telehaptic/1")
        m.put("app_version", BuildConfig.VERSION_NAME)
        m.put("id", id)
        m.put("kind", s.kind.code)
        m.put("posture", s.posture?.code ?: JSONObject.NULL)
        m.put("rewear", s.rewear)
        m.put("magnet", if (s.kind == Kind.CALIBRATION || s.kind == Kind.NO_MAGNET) "none" else s.magnet)
        m.put("magnet_selected", s.magnet)
        m.put("sensor_distance_mm", if (s.distanceMm > 0) s.distanceMm else JSONObject.NULL)
        m.put("note", s.note)
        m.put("aborted", aborted)
        m.put("start_wall_time", startWall)
        m.put("start_elapsed_ns", startNs)
        m.put("end_elapsed_ns", endNs)
        m.put("seed", s.seed)
        m.put("sequence", JSONArray(s.sequence))
        val p = s.plan
        m.put("settings", JSONObject()
            .put("lead_s", p.leadMs / 1000.0).put("hold_s", p.holdMs / 1000.0).put("rest_s", p.restMs / 1000.0)
            .put("blocks", p.blocks).put("still_s", p.stillMs / 1000.0).put("rotate_s", p.rotateMs / 1000.0)
            .put("motor_pulses", p.motorPulses).put("motor_pulse_ms", p.motorPulseMs))
        m.put("capsule_at_start", startCapsule)
        m.put("capsule_at_end", capsuleJson())
        m.put("imu_scale", JSONObject().put("acc_mg_per_lsb", Proto.ACC_MG_PER_LSB).put("gyr_mdps_per_lsb", Proto.GYR_MDPS_PER_LSB))
        val (ready, resid, drift) = capsule.syncSnapshot()
        m.put("time_sync", JSONObject()
            .put("method", "lower envelope of (phone receive time - capsule time), 1 s bins, linear fit over 20 s; t_ns in samples.csv uses the fit current at that moment; packets.csv and sync.csv allow a refit")
            .put("ready_at_end", ready).put("residual_ms_at_end", resid / 1e6).put("drift_ppm_at_end", drift))
        m.put("stats", JSONObject()
            .put("samples", count).put("lost", lost).put("skipped_layout_change", skipped)
            .put("saturated_samples", saturated).put("saturated_per_axis", JSONArray(satPerAxis.toList()))
            .put("stale_samples", stale).put("i2c_error_samples", i2c).put("motor_samples", motorSamples)
            .put("disconnects", disconnects).put("min_battery_mv", if (minBattery == Int.MAX_VALUE) JSONObject.NULL else minBattery))
        m.put("phone", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT))
        m.put("tts", App.get().cues.ttsStatus)
        m.put("write_error", error ?: "")
        m.put("columns", "samples.csv: idx = capsule sample index, t_xiao = capsule clock (1/32768 s), t_ns = phone clock " +
            "(SystemClock.elapsedRealtimeNanos) via time_sync; m<i>x..z raw magnetometer units (uT = raw * 100 / lsb_per_gauss), " +
            "gx..gz raw gyro (x gyr_mdps_per_lsb), ax..az raw accel (x acc_mg_per_lsb), flags as in docs/protocol.md. " +
            "events.csv, packets.csv (t_rx_ns = phone receive time), sync.csv (ping), info.csv (capsule text lines): phone clock.")
        return m
    }

    private fun zip(src: File, dst: File) {
        ZipOutputStream(FileOutputStream(dst)).use { z ->
            for (f in src.listFiles()!!.sortedBy { it.name }) {
                z.putNextEntry(ZipEntry(f.name))
                FileInputStream(f).use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }
}
