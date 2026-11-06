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
data class Touch(val key: String, val x: DoubleArray, val group: String = "", val source: String = "", val session: String = "")

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
    /** Degrees of freedom of the pooled covariance: touches minus keys. */
    val dof: Int,
) {
    /** Per key: farther than this from the key's centre = "not this key" (see [rejectD2]). */
    private val reject = DoubleArray(keys.size) { rejectD2(features.dims, counts[it], dof) }

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
        return Result(keys[best], keys[second], pBest, pSecond, d2[best], d2[best] <= reject[best])
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

        const val REJECT_P = 0.999

        /**
         * The distance a real touch of a key exceeds only 0.1% of the time, when the key's centre
         * comes from [n] touches and the covariance from [dof] degrees of freedom. With few
         * touches both are uncertain, so a new touch lands farther than the chi-square rule says
         * (with 4 touches per key that rule rejected about 3% of real touches in the end-to-end
         * test). Prediction region of a multivariate normal with estimated mean and covariance:
         * d2 ~ (1 + 1/n) * dof * p / (dof - p + 1) * F(p, dof - p + 1).
         */
        fun rejectD2(p: Int, n: Int, dof: Int): Double {
            val m = dof - p + 1
            if (m < 1 || n < 1) return Double.POSITIVE_INFINITY   // too little data to reject anything
            return (1.0 + 1.0 / n) * dof * p / m * Stat.fQuantile(REJECT_P, p.toDouble(), m.toDouble())
        }

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
            return Lda(keys, features, means, cov, inv, counts, dof)
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
    /** A common shift is estimated only from at least this many re-touched keys. */
    const val MIN_SHIFT_KEYS = 6

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
        // With only a few keys the median is mostly those keys' own error, not a common move of
        // the capsule: shifting all the other keys by it would spoil them. No shift then.
        if (diffs.size < MIN_SHIFT_KEYS) return DoubleArray(6)
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

/** The few distribution functions the model needs. */
object Stat {
    /** Quantile of the F distribution with (d1, d2) degrees of freedom, by bisection on its CDF. */
    fun fQuantile(prob: Double, d1: Double, d2: Double): Double {
        var lo = 0.0; var hi = 1.0
        while (fCdf(hi, d1, d2) < prob && hi < 1e7) hi *= 2
        repeat(100) { val mid = (lo + hi) / 2; if (fCdf(mid, d1, d2) < prob) lo = mid else hi = mid }
        return (lo + hi) / 2
    }

    fun fCdf(f: Double, d1: Double, d2: Double): Double = if (f <= 0) 0.0 else betaReg(d1 * f / (d1 * f + d2), d1 / 2, d2 / 2)

    /** Regularized incomplete beta I_x(a, b): continued fraction (Numerical Recipes, betacf). */
    fun betaReg(x: Double, a: Double, b: Double): Double {
        if (x <= 0) return 0.0
        if (x >= 1) return 1.0
        val lnFront = lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * kotlin.math.ln(x) + b * kotlin.math.ln(1 - x)
        return if (x < (a + 1) / (a + b + 2)) kotlin.math.exp(lnFront) * betaCf(x, a, b) / a
        else 1 - kotlin.math.exp(lnFront) * betaCf(1 - x, b, a) / b
    }

    private fun betaCf(x: Double, a: Double, b: Double): Double {
        val tiny = 1e-300
        var c = 1.0
        var d = 1 - (a + b) * x / (a + 1)
        if (kotlin.math.abs(d) < tiny) d = tiny
        d = 1 / d
        var h = d
        for (m in 1..300) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2))
            d = 1 + aa * d; if (kotlin.math.abs(d) < tiny) d = tiny
            c = 1 + aa / c; if (kotlin.math.abs(c) < tiny) c = tiny
            d = 1 / d; h *= d * c
            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1))
            d = 1 + aa * d; if (kotlin.math.abs(d) < tiny) d = tiny
            c = 1 + aa / c; if (kotlin.math.abs(c) < tiny) c = tiny
            d = 1 / d
            val del = d * c
            h *= del
            if (kotlin.math.abs(del - 1) < 1e-12) break
        }
        return h
    }

    /** Lanczos approximation (g = 7, 9 terms). */
    fun lnGamma(z: Double): Double {
        val g = doubleArrayOf(0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7)
        if (z < 0.5) return kotlin.math.ln(Math.PI / kotlin.math.abs(kotlin.math.sin(Math.PI * z))) - lnGamma(1 - z)
        val x = z - 1
        var a = g[0]
        val t = x + 7.5
        for (i in 1 until 9) a += g[i] / (x + i)
        return 0.5 * kotlin.math.ln(2 * Math.PI) + (x + 0.5) * kotlin.math.ln(t) - t + kotlin.math.ln(a)
    }
}
