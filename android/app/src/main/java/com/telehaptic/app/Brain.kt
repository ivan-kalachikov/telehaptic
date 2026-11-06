package com.telehaptic.app

import android.content.Context
import com.telehaptic.app.core.Evaluation
import com.telehaptic.app.core.FeatureSet
import com.telehaptic.app.core.Lda
import com.telehaptic.app.core.Segmenter
import com.telehaptic.app.core.Touch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The model and the touches it was trained on, stored in files/model/.
 * touches.csv keeps every training touch (6 numbers in uT), so the model can be rebuilt
 * with another feature set after the analysis.
 */
class Brain(ctx: Context, private val engine: Engine, private val log: AppLog) {
    data class Summary(
        val trained: Boolean = false,
        val date: String = "",
        val correct: Int = 0,
        val total: Int = 0,
        val confusions: List<Triple<String, String, Int>> = emptyList(),
        val weakKeys: List<String> = emptyList(),
        val touches: Int = 0,
        val features: String = "both",
        val savedMs: Long = 0,           // when the model was last changed (wall clock)
    )

    private val dir = File(ctx.filesDir, "model").apply { mkdirs() }
    private val touchFile = File(dir, "touches.csv")
    private val modelFile = File(dir, "model.json")
    private val _summary = MutableStateFlow(Summary())
    val summary: StateFlow<Summary> = _summary
    @Volatile var features = FeatureSet.BOTH; private set
    @Volatile var rest: DoubleArray? = null; private set
    @Volatile var segParams = Segmenter.Params(); private set
    /** Key centres as the detector sees them; a fresh rest level must not look like one. */
    @Volatile private var keyY: List<DoubleArray> = emptyList()

    init {
        try { load() } catch (e: Exception) { log.w("brain", "load", e) }
    }

    val model get() = engine.model

    /** A damaged line (cut off by a crash) is skipped, not fatal. */
    fun touches(): List<Touch> = if (!touchFile.exists()) emptyList() else touchFile.readLines().drop(1).mapNotNull { l ->
        val f = l.split(',')
        if (f.size < 10) return@mapNotNull null
        val x = DoubleArray(6) { f[4 + it].toDoubleOrNull() ?: return@mapNotNull null }
        Touch(f[3], x, f[1], f[2], f[0])
    }

    /** All touches, written to a temporary file and renamed: a crash never leaves half a file. */
    private fun writeAll(list: List<Touch>) {
        val sb = StringBuilder("session,group,source,key,x1,x2,x3,x4,x5,x6\n")
        for (t in list) sb.append(t.session).append(',').append(t.group).append(',').append(t.source).append(',').append(t.key)
            .append(',').append(t.x.joinToString(",") { "%.3f".format(Locale.US, it) }).append('\n')
        atomicWrite(touchFile, sb.toString())
    }

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    /** First training: replaces everything. Returns the honest report (held-out quick taps). */
    fun train(session: String, list: List<Touch>, restY: DoubleArray?): Evaluation.Report {
        val tapGroups = list.filter { it.source == "tap" }.map { it.group }.toSet()
        val report = Evaluation.leaveGroupOut(list, features, tapGroups.ifEmpty { list.map { it.group }.toSet() })
        writeAll(list.map { it.copy(session = session) })
        dropBackup()
        rebuild(restY, report)
        return report
    }

    /** Retraining: old touches move by a common shift to line up with the new ones. */
    /** Re-fit result: kept = the new model replaced the old one. */
    data class Refit(val before: Evaluation.Report, val after: Evaluation.Report, val kept: Boolean)

    private val touchBak = File(dir, "touches.bak.csv")
    private val modelBak = File(dir, "model.bak.json")
    val canUndo get() = touchBak.exists()

    /**
     * Re-fit with new quick taps. [full] = all keys were practised; only then the home score and
     * the weak keys change. If the new model does worse on the new taps, the old one is kept.
     */
    fun retrain(session: String, new: List<Touch>, full: Boolean): Refit {
        val old = touches()
        val m = model
        val before = if (m != null) Evaluation.on(m, new) else Evaluation.Report(0, new.size, emptyList(), emptyList())
        val after = Evaluation.afterRetrain(old, new, features)
        if (after.share < before.share) {
            log.i("brain", "re-fit worse (${after.correct}/${after.total} < ${before.correct}/${before.total}), old model kept")
            return Refit(before, after, kept = false)
        }
        touchFile.copyTo(touchBak, overwrite = true)
        if (modelFile.exists()) modelFile.copyTo(modelBak, overwrite = true)
        val s = Evaluation.shift(old, new)
        val shifted = Evaluation.applyShift(old, s)
        log.i("brain", "retrain: shift ${s.joinToString { "%.1f".format(Locale.US, it) }}, before ${before.correct}/${before.total}, after ${after.correct}/${after.total}")
        writeAll(shifted + new.map { it.copy(session = session) })
        rest = rest?.let { r -> DoubleArray(3) { i -> r[i] + if (engine.twoSensors) s[i] - s[3 + i] else s[i] } }
        rebuild(rest, if (full) after else null)
        return Refit(before, after, kept = true)
    }

    /** Puts back the model from before the last re-fit, exactly as it was saved (survives an app restart). Capsule thread. */
    fun undoRetrain() {
        if (!touchBak.exists()) return
        touchBak.copyTo(touchFile, overwrite = true)
        if (modelBak.exists()) modelBak.copyTo(modelFile, overwrite = true)
        dropBackup()
        load()
        log.i("brain", "re-fit undone")
    }

    /** Corrections from typing, after the person confirmed them. Runs on the capsule thread like the rest of Brain. */
    fun addCorrections(list: List<Touch>) {
        if (list.isEmpty()) return
        engine.post {
            writeAll(touches() + list.map { it.copy(session = "typing") })
            dropBackup()   // the undo would throw the corrections away too
            rebuild(rest, null)
        }
    }

    /** For screenshots only. */
    fun showForPreview(s: Summary) { _summary.value = s }

    /** Deletes the model and its touches, backups included. Runs on the capsule thread like the rest of Brain. */
    fun forget() = engine.post {
        touchFile.delete(); modelFile.delete(); dropBackup()
        engine.model = null
        rest = null; segParams = Segmenter.Params(); keyY = emptyList()
        _summary.value = Summary()
    }

    private fun dropBackup() { touchBak.delete(); modelBak.delete() }

    private fun rebuild(restY: DoubleArray?, report: Evaluation.Report?) {
        val all = touches()
        val m = Lda.fit(all, features)
        engine.model = m
        rest = restY
        val ky = m?.let { lda -> lda.keys.map { k -> engine.segmenter.yOf(meanOf(all, k), engine.twoSensors) } } ?: emptyList()
        keyY = ky
        segParams = if (restY != null) Segmenter.Params.fromTraining(restY, ky) else Segmenter.Params()
        val old = _summary.value
        val date = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())
        _summary.value = Summary(
            trained = m != null, date = date,
            correct = report?.correct ?: old.correct, total = report?.total ?: old.total,
            confusions = report?.confusions ?: old.confusions, weakKeys = report?.wrongKeys ?: old.weakKeys,
            touches = all.size, features = features.code, savedMs = System.currentTimeMillis(),
        )
        save()
        log.i("brain", "model: ${all.size} touches, keys ${m?.keys?.size ?: 0}, onUt ${"%.1f".format(Locale.US, segParams.onUt)}")
    }

    private fun meanOf(all: List<Touch>, k: String): DoubleArray {
        val l = all.filter { it.key == k }
        return DoubleArray(6) { i -> l.map { it.x[i] }.average() }
    }

    /** Applies the stored rest and thresholds to the detector (before taps or typing). Capsule thread. */
    fun armSegmenter() = engine.configure(segParams, rest)

    /**
     * Like [armSegmenter], but the rest level is measured again from the first still moment:
     * after re-wearing, the stored one may be off by more than the detector's threshold.
     */
    fun armSegmenterFreshRest() = engine.configure(segParams, null, keyY)

    /** Thresholds as text for the recording's detector event. */
    fun detectorText(p: Segmenter.Params = segParams, r: DoubleArray? = rest): String =
        "on_ut=%.2f;stable_ut=%.2f;window_ms=%d;hold_ms=%d;gyro_max_dps=%.0f;rest=%s".format(
            Locale.US, p.onUt, p.stableUt, p.windowMs, p.holdMs, p.gyroMaxDps, r?.joinToString(";") { "%.2f".format(Locale.US, it) } ?: "")

    private fun save() {
        val s = _summary.value
        val j = JSONObject()
            .put("features", features.code)
            .put("date", s.date).put("correct", s.correct).put("total", s.total)
            .put("weak", JSONArray(s.weakKeys))
            .put("confusions", JSONArray(s.confusions.map { JSONArray(listOf(it.first, it.second, it.third)) }))
            .put("rest", rest?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
            .put("on_ut", segParams.onUt)
            .put("saved_ms", s.savedMs)
        atomicWrite(modelFile, j.toString(2))
    }

    private fun load() {
        if (!modelFile.exists()) return
        val j = JSONObject(modelFile.readText())
        features = FeatureSet.byCode(j.optString("features", "both"))
        rest = j.optJSONArray("rest")?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
        segParams = Segmenter.Params(onUt = j.optDouble("on_ut", 6.0))
        val all = touches()
        engine.model = Lda.fit(all, features)
        val conf = j.optJSONArray("confusions")
        _summary.value = Summary(
            trained = engine.model != null, date = j.optString("date"), correct = j.optInt("correct"), total = j.optInt("total"),
            confusions = (0 until (conf?.length() ?: 0)).map { i -> conf!!.getJSONArray(i).let { Triple(it.getString(0), it.getString(1), it.getInt(2)) } },
            weakKeys = j.optJSONArray("weak")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            touches = all.size, features = features.code, savedMs = j.optLong("saved_ms", modelFile.lastModified()),
        )
        keyY = engine.model?.let { lda -> lda.keys.map { k -> engine.segmenter.yOf(meanOf(all, k), all.any { t -> t.x[3] != 0.0 }) } } ?: emptyList()
    }
}
