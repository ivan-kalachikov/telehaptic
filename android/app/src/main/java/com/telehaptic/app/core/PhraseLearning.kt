package com.telehaptic.app.core

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Learning keys from typed phrases. In a typing test the phrase is known but not which touch
 * meant which of its keys: touches get missed, extra ones come. Forced alignment finds the most
 * likely pairing in phrase order (a touch may be extra, a key may have no touch), the model is
 * refitted on it, and again (expectation-maximisation, [ROUNDS] times).
 *
 * Measured on the first two typing tests (10 phrases, 155 touches; analysis in the PR): the model
 * learned from 6 phrases read 71% of the keys of phrases it had not seen, against 23% for the
 * model from the training before. Starting from the old model instead of an even split of each
 * phrase gave 53%: a wrong model leads the first alignment astray.
 */
object PhraseLearning {
    /** One typed phrase: the keys it needed and every touch found while typing it (6 numbers, uT). */
    data class Phrase(val keys: List<String>, val xs: List<DoubleArray>)

    /** Rounds of align + refit. */
    const val ROUNDS = 4

    /** ln of the chance of an extra touch, and of a key with no touch (both ~15% in the first tests: 15 of 155, 24 of 164). */
    val SKIP = ln(0.15)

    /** Keys for a phrase: letters as T9 keys, * and # as themselves, 0 between words, 1 (full stop) at the end. Null if a letter has no key. */
    fun keysOf(phrase: String): List<String>? {
        val out = ArrayList<String>()
        for ((i, w) in phrase.trim().split(Regex("\\s+")).withIndex()) {
            if (i > 0) out.add("0")
            // "***" and "###": the erase and next keys themselves (the test's last item)
            if (w.all { it == '*' || it == '#' }) { for (c in w) out.add(c.toString()); continue }
            for (c in T9.code(w) ?: return null) out.add(c.toString())
        }
        out.add("1")
        return out
    }

    /** First guess: the touches spread evenly over the phrase's keys. */
    fun evenSplit(p: Phrase): List<String?> {
        val n = p.xs.size; val m = p.keys.size
        if (n == 0) return emptyList()
        if (n == m || n == 1) return List(n) { p.keys[if (n == 1) 0 else it] }
        return List(n) { p.keys[(it * (m - 1).toDouble() / (n - 1)).roundToInt()] }
    }

    /**
     * Most likely pairing of touches with keys in order. [logp] per touch: ln P(key | touch).
     * Returns the key of each touch, or null for an extra touch.
     */
    fun align(logp: List<Map<String, Double>>, keys: List<String>): List<String?> {
        val n = logp.size; val m = keys.size
        val s = Array(n + 1) { DoubleArray(m + 1) }
        val back = Array(n + 1) { IntArray(m + 1) }    // 0 match, 1 extra touch, 2 key without touch
        for (i in 1..n) { s[i][0] = i * SKIP; back[i][0] = 1 }
        for (j in 1..m) { s[0][j] = j * SKIP; back[0][j] = 2 }
        for (i in 1..n) for (j in 1..m) {
            val match = s[i - 1][j - 1] + (logp[i - 1][keys[j - 1]] ?: -1e9)
            val extra = s[i - 1][j] + SKIP
            val miss = s[i][j - 1] + SKIP
            if (match >= extra && match >= miss) { s[i][j] = match; back[i][j] = 0 }
            else if (extra >= miss) { s[i][j] = extra; back[i][j] = 1 }
            else { s[i][j] = miss; back[i][j] = 2 }
        }
        val out = arrayOfNulls<String>(n)
        var i = n; var j = m
        while (i > 0 && j > 0) when (back[i][j]) {
            0 -> { out[i - 1] = keys[j - 1]; i--; j-- }
            1 -> i--
            else -> j--
        }
        return out.toList()
    }

    fun logProbs(model: Lda, x: DoubleArray): Map<String, Double> =
        model.classify(x).probs.mapValues { ln(max(it.value, 1e-300)) }

    private fun touches(phrases: List<Phrase>, labels: List<List<String?>>, session: String) =
        phrases.indices.flatMap { pi -> phrases[pi].xs.indices.mapNotNull { ti ->
            labels[pi][ti]?.let { Touch(it, phrases[pi].xs[ti], "phrase$pi", "typing", session) }
        } }

    /** Touches labelled by alignment with their phrases. Empty if no model could be fitted. */
    fun learn(phrases: List<Phrase>, features: FeatureSet, session: String = ""): List<Touch> {
        var labels = phrases.map { evenSplit(it) }
        repeat(ROUNDS) {
            val m = Lda.fit(touches(phrases, labels, session), features) ?: return emptyList()
            labels = phrases.map { p -> align(p.xs.map { logProbs(m, it) }, p.keys) }
        }
        return touches(phrases, labels, session)
    }

    /** How many of [keys] a read key sequence got right, by edit distance (as the typing-test analysis counts). */
    fun matches(read: List<String>, keys: List<String>): Int {
        val n = read.size; val m = keys.size
        val c = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) c[i][0] = i
        for (j in 0..m) c[0][j] = j
        for (i in 1..n) for (j in 1..m)
            c[i][j] = minOf(c[i - 1][j] + 1, c[i][j - 1] + 1, c[i - 1][j - 1] + if (read[i - 1] == keys[j - 1]) 0 else 1)
        var i = n; var j = m; var same = 0
        while (i > 0 && j > 0) {
            val sub = if (read[i - 1] == keys[j - 1]) 0 else 1
            when (c[i][j]) {
                c[i - 1][j - 1] + sub -> { if (sub == 0) same++; i--; j-- }
                c[i - 1][j] + 1 -> i--
                else -> j--
            }
        }
        return same
    }

    /** Keys right and keys needed, phrases read by a model. */
    data class Score(val right: Int, val needed: Int) {
        val share get() = if (needed > 0) right.toDouble() / needed else 0.0
    }

    fun read(model: Lda?, phrases: List<Phrase>): Score =
        Score(phrases.sumOf { p -> if (model == null) 0 else matches(p.xs.map { model.classify(it).key }, p.keys) }, phrases.sumOf { it.keys.size })

    /** Each phrase read by a model learned from the others only: what the learned model does on phrases it has not seen. */
    fun leaveOnePhraseOut(phrases: List<Phrase>, features: FeatureSet): Score {
        var right = 0
        for (i in phrases.indices) {
            val m = Lda.fit(learn(phrases.filterIndexed { j, _ -> j != i }, features), features) ?: continue
            right += read(m, listOf(phrases[i])).right
        }
        return Score(right, phrases.sumOf { it.keys.size })
    }
}
