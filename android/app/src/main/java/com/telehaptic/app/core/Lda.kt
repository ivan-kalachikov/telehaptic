package com.telehaptic.app.core

import kotlin.math.exp
import kotlin.math.max

/**
 * Which numbers describe a touch. The capsule gives up to 6 (two sensors x 3 axes, uT);
 * the model may use all of them or a part. Chosen after the analysis of the first series.
 */
enum class FeatureSet(val code: String, val dims: Int) {
    BOTH("both", 6), DIFF("diff", 3), S1("s1", 3), S2("s2", 3);

    /** x = [m1x, m1y, m1z, m2x, m2y, m2z] in uT (with one sensor the last 3 are 0). */
    fun of(x: DoubleArray): DoubleArray = when (this) {
        BOTH -> x.copyOf(6)
        DIFF -> DoubleArray(3) { x[it] - x[3 + it] }
        S1 -> DoubleArray(3) { x[it] }
        S2 -> DoubleArray(3) { x[3 + it] }
    }

    companion object {
        fun byCode(c: String) = entries.firstOrNull { it.code == c } ?: BOTH
    }
}

/** A touch for training: the key and the field (uT, 6 numbers) on its plateau. */
data class Touch(val key: String, val x: DoubleArray, val group: String = "", val source: String = "")

/**
 * Linear discriminant analysis: one mean per key, one shared covariance shrunk towards a
 * scaled identity, equal priors. Same model as the phone stand's (LDA on the field at the touch).
 */
class Lda private constructor(
    val keys: List<String>,
    val features: FeatureSet,
    val means: Array<DoubleArray>,
    val cov: Array<DoubleArray>,
    private val inv: Array<DoubleArray>,
    val counts: IntArray,
) {
    data class Result(val key: String, val second: String, val posterior: Double, val secondPosterior: Double, val d2: Double, val accepted: Boolean)

    fun classify(x6: DoubleArray): Result {
        val x = features.of(x6)
        val d2 = DoubleArray(keys.size) { mahal(x, means[it]) }
        val order = d2.indices.sortedBy { d2[it] }
        val best = order[0]
        val second = order.getOrElse(1) { best }
        // posterior with equal priors: softmax(-d2 / 2)
        val m = d2[best]
        var sum = 0.0
        for (v in d2) sum += exp(-(v - m) / 2)
        val pBest = 1.0 / sum
        val pSecond = exp(-(d2[second] - m) / 2) / sum
        return Result(keys[best], keys[second], pBest, pSecond, d2[best], d2[best] <= rejectD2(features.dims))
    }

    private fun mahal(x: DoubleArray, mu: DoubleArray): Double {
        val n = x.size
        var s = 0.0
        for (i in 0 until n) {
            var r = 0.0
            for (j in 0 until n) r += inv[i][j] * (x[j] - mu[j])
            s += (x[i] - mu[i]) * r
        }
        return s
    }

    companion object {
        const val SHRINK = 0.1

        /** Chi-square 99.9% quantiles: farther than this from every key = "not a key". */
        fun rejectD2(dims: Int) = when (dims) { 3 -> 16.27; 6 -> 22.46; else -> 3.0 * dims + 6 }

        /** Null if fewer than two keys have data. */
        fun fit(touches: List<Touch>, features: FeatureSet = FeatureSet.BOTH): Lda? {
            val keys = Keys.ALL.filter { k -> touches.any { it.key == k } }
            if (keys.size < 2) return null
            val d = features.dims
            val xs = touches.map { features.of(it.x) }
            val means = Array(keys.size) { DoubleArray(d) }
            val counts = IntArray(keys.size)
            for ((t, x) in touches.zip(xs)) {
                val k = keys.indexOf(t.key)
                counts[k]++
                for (i in 0 until d) means[k][i] += x[i]
            }
            for (k in keys.indices) for (i in 0 until d) means[k][i] /= counts[k]
            val cov = Array(d) { DoubleArray(d) }
            var n = 0
            for ((t, x) in touches.zip(xs)) {
                val mu = means[keys.indexOf(t.key)]
                for (i in 0 until d) for (j in 0 until d) cov[i][j] += (x[i] - mu[i]) * (x[j] - mu[j])
                n++
            }
            val dof = max(1, n - keys.size)
            for (i in 0 until d) for (j in 0 until d) cov[i][j] /= dof
            var tr = 0.0
            for (i in 0 until d) tr += cov[i][i]
            val scale = max(tr / d, 0.25)  // floor 0.25 uT^2 (~ noise 0.5 uT) for tiny training sets
            for (i in 0 until d) for (j in 0 until d) cov[i][j] = (1 - SHRINK) * cov[i][j] + if (i == j) SHRINK * scale else 0.0
            val inv = invert(cov) ?: return null
            return Lda(keys, features, means, cov, inv, counts)
        }

        fun restore(keys: List<String>, features: FeatureSet, means: Array<DoubleArray>, cov: Array<DoubleArray>, counts: IntArray): Lda? {
            val inv = invert(cov) ?: return null
            return Lda(keys, features, means, cov, inv, counts)
        }

        /** Gauss-Jordan inverse with partial pivoting; null if singular. */
        fun invert(a: Array<DoubleArray>): Array<DoubleArray>? {
            val n = a.size
            val m = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) a[i][j] else if (j - n == i) 1.0 else 0.0 } }
            for (c in 0 until n) {
                var p = c
                for (r in c + 1 until n) if (kotlin.math.abs(m[r][c]) > kotlin.math.abs(m[p][c])) p = r
                if (kotlin.math.abs(m[p][c]) < 1e-12) return null
                val tmp = m[p]; m[p] = m[c]; m[c] = tmp
                val pv = m[c][c]
                for (j in 0 until 2 * n) m[c][j] /= pv
                for (r in 0 until n) if (r != c) {
                    val f = m[r][c]
                    if (f != 0.0) for (j in 0 until 2 * n) m[r][j] -= f * m[c][j]
                }
            }
            return Array(n) { i -> DoubleArray(n) { j -> m[i][n + j] } }
        }
    }
}

/** Honest checks of a model on touches it did not see. */
object Evaluation {
    data class Report(val correct: Int, val total: Int, val confusions: List<Triple<String, String, Int>>, val wrongKeys: List<String>) {
        val share get() = if (total > 0) correct.toDouble() / total else 0.0
    }

    /**
     * Each group (block) in turn is left out of training and tested. Only touches whose
     * group is in [testGroups] are tested (e.g. the quick taps), all others always train.
     */
    fun leaveGroupOut(touches: List<Touch>, features: FeatureSet, testGroups: Set<String>): Report {
        val pairs = HashMap<Pair<String, String>, Int>()
        var correct = 0; var total = 0
        val wrong = HashMap<String, Int>()
        for (g in testGroups) {
            val train = touches.filter { it.group != g }
            val test = touches.filter { it.group == g }
            val m = Lda.fit(train, features) ?: continue
            for (t in test) {
                val r = m.classify(t.x)
                total++
                if (r.key == t.key) correct++ else {
                    val p = if (t.key < r.key) t.key to r.key else r.key to t.key
                    pairs[p] = (pairs[p] ?: 0) + 1
                    wrong[t.key] = (wrong[t.key] ?: 0) + 1
                }
            }
        }
        return Report(correct, total,
            pairs.entries.sortedByDescending { it.value }.map { Triple(it.key.first, it.key.second, it.value) },
            wrong.entries.sortedByDescending { it.value }.map { it.key })
    }

    /** An existing model on new touches, no refit. */
    fun on(model: Lda, touches: List<Touch>): Report {
        val pairs = HashMap<Pair<String, String>, Int>()
        val wrong = HashMap<String, Int>()
        var correct = 0
        for (t in touches) {
            val r = model.classify(t.x)
            if (r.key == t.key) correct++ else {
                val p = if (t.key < r.key) t.key to r.key else r.key to t.key
                pairs[p] = (pairs[p] ?: 0) + 1
                wrong[t.key] = (wrong[t.key] ?: 0) + 1
            }
        }
        return Report(correct, touches.size,
            pairs.entries.sortedByDescending { it.value }.map { Triple(it.key.first, it.key.second, it.value) },
            wrong.entries.sortedByDescending { it.value }.map { it.key })
    }

    /**
     * Old touches move by one common shift so that, for the keys touched again, they line up
     * with the new ones (median over keys of new mean - old mean). A change of the capsule's
     * position or of the Earth's contribution moves all keys alike; the shape stays.
     */
    fun shift(old: List<Touch>, new: List<Touch>): DoubleArray {
        val diffs = ArrayList<DoubleArray>()
        for (k in new.map { it.key }.distinct()) {
            val o = old.filter { it.key == k }
            val n = new.filter { it.key == k }
            if (o.isEmpty() || n.isEmpty()) continue
            diffs.add(DoubleArray(6) { i -> n.map { it.x[i] }.average() - o.map { it.x[i] }.average() })
        }
        if (diffs.isEmpty()) return DoubleArray(6)
        return DoubleArray(6) { i -> median(diffs.map { it[i] }) }
    }

    fun applyShift(t: List<Touch>, s: DoubleArray) = t.map { it.copy(x = DoubleArray(6) { i -> it.x[i] + s[i] }) }

    /** Retraining: leave-one-out over the new touches, with all shifted old ones always in training. */
    fun afterRetrain(old: List<Touch>, new: List<Touch>, features: FeatureSet): Report {
        val pairs = HashMap<Pair<String, String>, Int>()
        val wrong = HashMap<String, Int>()
        var correct = 0
        for (i in new.indices) {
            // The left-out touch takes no part in training, the shift included.
            val rest = new.filterIndexed { j, _ -> j != i }
            val m = Lda.fit(applyShift(old, shift(old, rest)) + rest, features) ?: continue
            val t = new[i]
            val r = m.classify(t.x)
            if (r.key == t.key) correct++ else {
                val p = if (t.key < r.key) t.key to r.key else r.key to t.key
                pairs[p] = (pairs[p] ?: 0) + 1
                wrong[t.key] = (wrong[t.key] ?: 0) + 1
            }
        }
        return Report(correct, new.size,
            pairs.entries.sortedByDescending { it.value }.map { Triple(it.key.first, it.key.second, it.value) },
            wrong.entries.sortedByDescending { it.value }.map { it.key })
    }

    fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
