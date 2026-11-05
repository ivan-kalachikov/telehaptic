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
    )

    private val dir = File(ctx.filesDir, "model").apply { mkdirs() }
    private val touchFile = File(dir, "touches.csv")
    private val modelFile = File(dir, "model.json")
    private val _summary = MutableStateFlow(Summary())
    val summary: StateFlow<Summary> = _summary
    var features = FeatureSet.BOTH; private set
    var rest: DoubleArray? = null; private set
    var segParams = Segmenter.Params(); private set

    init {
        try { load() } catch (e: Exception) { log.w("brain", "load", e) }
    }

    val model get() = engine.model

    fun touches(): List<Touch> = if (!touchFile.exists()) emptyList() else touchFile.readLines().drop(1).mapNotNull { l ->
        val f = l.split(',')
        if (f.size < 10) null else Touch(f[3], DoubleArray(6) { f[4 + it].toDouble() }, f[1], f[2])
    }

    private fun writeTouches(session: String, list: List<Touch>, append: Boolean) {
        val header = "session,group,source,key,x1,x2,x3,x4,x5,x6\n"
        val sb = StringBuilder()
        if (!append || !touchFile.exists()) sb.append(header)
        for (t in list) sb.append(session).append(',').append(t.group).append(',').append(t.source).append(',').append(t.key)
            .append(',').append(t.x.joinToString(",") { "%.3f".format(Locale.US, it) }).append('\n')
        if (append && touchFile.exists()) touchFile.appendText(sb.toString()) else touchFile.writeText(sb.toString())
    }

    /** First training: replaces everything. Returns the honest report (held-out quick taps). */
    fun train(session: String, list: List<Touch>, restY: DoubleArray?): Evaluation.Report {
        val tapGroups = list.filter { it.source == "tap" }.map { it.group }.toSet()
        val report = Evaluation.leaveGroupOut(list, features, tapGroups.ifEmpty { list.map { it.group }.toSet() })
        writeTouches(session, list, append = false)
        rebuild(restY, report)
        return report
    }

    /** Retraining: old touches move by a common shift to line up with the new ones. */
    fun retrain(session: String, new: List<Touch>): Pair<Evaluation.Report, Evaluation.Report> {
        val old = touches()
        val m = model
        val before = if (m != null) Evaluation.on(m, new) else Evaluation.Report(0, new.size, emptyList(), emptyList())
        val after = Evaluation.afterRetrain(old, new, features)
        val s = Evaluation.shift(old, new)
        val shifted = Evaluation.applyShift(old, s)
        log.i("brain", "retrain: shift ${s.joinToString { "%.1f".format(Locale.US, it) }}, before ${before.correct}/${before.total}, after ${after.correct}/${after.total}")
        writeTouches("", shifted.map { it.copy(group = it.group, source = it.source) }, append = false)
        writeTouches(session, new, append = true)
        rest = rest?.let { r -> DoubleArray(3) { i -> r[i] + if (engine.twoSensors) s[i] - s[3 + i] else s[i] } }
        rebuild(rest, after)
        return before to after
    }

    /** Corrections from typing, after the person confirmed them. */
    fun addCorrections(list: List<Touch>) {
        if (list.isEmpty()) return
        writeTouches("typing", list, append = true)
        rebuild(rest, null)
    }

    /** For screenshots only. */
    fun showForPreview(s: Summary) { _summary.value = s }

    fun forget() {
        touchFile.delete(); modelFile.delete()
        engine.model = null
        _summary.value = Summary()
    }

    private fun rebuild(restY: DoubleArray?, report: Evaluation.Report?) {
        val all = touches()
        val m = Lda.fit(all, features)
        engine.model = m
        rest = restY
        val keyY = m?.let { lda -> lda.keys.map { k -> engine.segmenter.yOf(meanOf(all, k), engine.twoSensors) } } ?: emptyList()
        segParams = if (restY != null) Segmenter.Params.fromTraining(restY, keyY) else Segmenter.Params()
        val old = _summary.value
        val date = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date())
        _summary.value = Summary(
            trained = m != null, date = date,
            correct = report?.correct ?: old.correct, total = report?.total ?: old.total,
            confusions = report?.confusions ?: old.confusions, weakKeys = report?.wrongKeys ?: old.weakKeys,
            touches = all.size, features = features.code,
        )
        save()
        log.i("brain", "model: ${all.size} touches, keys ${m?.keys?.size ?: 0}, onUt ${"%.1f".format(Locale.US, segParams.onUt)}")
    }

    private fun meanOf(all: List<Touch>, k: String): DoubleArray {
        val l = all.filter { it.key == k }
        return DoubleArray(6) { i -> l.map { it.x[i] }.average() }
    }

    /** Applies the stored rest and thresholds to the detector (before taps or typing). */
    fun armSegmenter() {
        engine.segmenter.p = segParams
        engine.resetSegmenter()
        rest?.let { engine.segmenter.setRest(it) }
    }

    private fun save() {
        val s = _summary.value
        val j = JSONObject()
            .put("features", features.code)
            .put("date", s.date).put("correct", s.correct).put("total", s.total)
            .put("weak", JSONArray(s.weakKeys))
            .put("confusions", JSONArray(s.confusions.map { JSONArray(listOf(it.first, it.second, it.third)) }))
            .put("rest", rest?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
            .put("on_ut", segParams.onUt)
        modelFile.writeText(j.toString(2))
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
            touches = all.size, features = features.code,
        )
    }
}
