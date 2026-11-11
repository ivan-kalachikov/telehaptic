package com.telehaptic.app.core

import kotlin.math.ln

/**
 * T9 for English: keys 2-9 carry letters as on phone keypads (ITU E.161), words come from a
 * frequency list. 0 = space (accepts the word), 1 = full stop, * = next variant of the word,
 * # = erase.
 *
 * A press may be uncertain ("soft T9"): it is 8 probabilities, one per letter key. A word's
 * keys are compared with the presses, so the word list can overrule a press the model was
 * unsure about, or one it got wrong. A press that is certain is 1 for its key and 0 for the rest.
 */
class T9(words: Sequence<Pair<String, Long>>) {
    private class Entry(val word: String, val keys: ByteArray, val lnFreq: Double)

    /** Words by length, each list in the order of the word list. */
    private val byLength = ArrayList<ArrayList<Entry>>()

    init {
        for ((w, f) in words) {
            val code = code(w) ?: continue
            if (code.isEmpty()) continue
            while (byLength.size <= code.length) byLength.add(ArrayList())
            byLength[code.length].add(Entry(w, ByteArray(code.length) { (code[it] - '2').toByte() }, ln(maxOf(f, 1L).toDouble())))
        }
    }

    /** Words for a digit string, every digit taken as certain. */
    fun candidates(digits: String, max: Int = 6): List<String> =
        if (digits.any { it !in '2'..'9' }) listOf(digits) else decode(digits.map { certain(it) }, max)

    /**
     * Words for a series of presses, best first.
     *
     * Cost of a word = sum over the presses of ln p(most likely key) - ln p(the word's key):
     * 0 if the word agrees with every press, large if it contradicts a press the model was sure
     * of. A word fits if its cost is no more than the dearest single change of one press in this
     * series. So the word list may overrule one press per word, or a few doubtful ones, never more.
     *
     * - Fitting words of exactly this length, by ln(frequency) - cost.
     * - If there are none: the beginnings of longer words, the same way.
     * - If there are none either: the most likely keys as digits.
     */
    fun decode(presses: List<DoubleArray>, max: Int = 6): List<String> {
        val n = presses.size
        if (n == 0) return emptyList()
        val lp = Array(n) { i -> DoubleArray(KEYS) { k -> ln(presses[i][k]) } }   // ln 0 = -infinity: that key is ruled out
        val best = DoubleArray(n) { i -> lp[i].max() }
        var budget = 0.0
        for (i in 0 until n) for (k in 0 until KEYS) { val c = best[i] - lp[i][k]; if (c.isFinite() && c > budget) budget = c }
        budget += 1e-9

        /** Cost of the first n keys of [e], or infinity as soon as it is over the budget. */
        fun cost(e: Entry): Double {
            var c = 0.0
            for (i in 0 until n) { c += best[i] - lp[i][e.keys[i].toInt()]; if (!(c <= budget)) return Double.POSITIVE_INFINITY }
            return c
        }

        val found = LinkedHashMap<String, Double>()   // shown text -> score
        for (e in byLength.getOrNull(n) ?: emptyList()) { val c = cost(e); if (c.isFinite()) found[e.word] = e.lnFreq - c }
        if (found.isEmpty()) for (len in n + 1 until byLength.size) for (e in byLength[len]) {
            val c = cost(e)
            if (!c.isFinite()) continue
            val shown = e.word.substring(0, n)
            val score = e.lnFreq - c
            if (score > (found[shown] ?: Double.NEGATIVE_INFINITY)) found[shown] = score
        }
        if (found.isEmpty()) return listOf(digits(presses))
        return found.entries.sortedByDescending { it.value }.take(max).map { it.key }
    }

    companion object {
        private val LETTERS = mapOf(
            '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
            '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz",
        )
        private val DIGIT = HashMap<Char, Char>().apply { for ((d, s) in LETTERS) for (c in s) put(c, d) }

        /** Letter keys: 2..9. */
        const val KEYS = 8

        /** A press that is certainly the letter key [digit] ('2'..'9'). */
        fun certain(digit: Char) = DoubleArray(KEYS) { if (it == digit - '2') 1.0 else 0.0 }

        /** The most likely key of each press, as digits. */
        fun digits(presses: List<DoubleArray>) = presses.joinToString("") { p -> ('2' + p.indices.maxByOrNull { p[it] }!!).toString() }

        fun code(word: String): String? {
            val sb = StringBuilder()
            for (c in word.lowercase()) sb.append(DIGIT[c] ?: return null)
            return sb.toString()
        }

        fun parse(lines: Sequence<String>): Sequence<Pair<String, Long>> = lines.mapNotNull { l ->
            val sp = l.indexOf(' ')
            if (sp <= 0) null else l.substring(0, sp) to (l.substring(sp + 1).trim().toLongOrNull() ?: 0L)
        }
    }
}

/** Typed text with the word being composed from presses. */
class Typing(private val t9: T9) {
    var text = StringBuilder(); private set
    /** The presses of the word being composed: 8 probabilities each, see [T9.decode]. */
    private val presses = ArrayList<DoubleArray>()
    var variant = 0; private set
    private var found: List<String>? = null   // candidates for the current presses, until they change

    /** The most likely key of each press of the current word. */
    val digits get() = T9.digits(presses)

    fun candidates(): List<String> = found ?: t9.decode(presses).also { found = it }
    fun currentWord(): String = candidates().let { if (it.isEmpty()) "" else it[variant % it.size] }

    /** What the screen shows: accepted text plus the current word. */
    fun display() = text.toString() + currentWord()

    /**
     * [probs]: how likely each key is for this press, from the model; null = certain (a tap on
     * the screen). [errorRate]: how often the model's first choice was wrong on touches it had
     * not seen. That share of the probability is spread evenly over all 12 keys, so the model is
     * never trusted more than its own test allows. 0 = the key is taken as certain.
     */
    fun key(k: String, probs: Map<String, Double>? = null, errorRate: Double = 0.0) {
        when (k) {
            in "2".."9" -> {
                presses.add(
                    if (probs == null || errorRate <= 0.0) T9.certain(k[0])
                    else DoubleArray(T9.KEYS) { (1 - errorRate) * (probs[('2' + it).toString()] ?: 0.0) + errorRate / Keys.ALL.size })
                changed()
            }
            "0" -> { commit(); text.append(' ') }
            "1" -> { commit(); text.append('.') }
            "*" -> if (presses.isNotEmpty()) variant++
            "#" -> erase()
        }
    }

    private fun changed() { variant = 0; found = null }

    private fun commit() {
        text.append(currentWord())
        presses.clear(); changed()
    }

    /**
     * Erases one key press. Inside a word: its last press. After a space or a full stop: that
     * character. Right after a committed word: the word goes back into presses (minus its last
     * one, each taken as certain: it is the word on the screen that is being edited), so the
     * next keys continue it instead of starting a new word glued to it.
     */
    fun erase() {
        if (presses.isNotEmpty()) { presses.removeAt(presses.size - 1); changed(); return }
        if (text.isEmpty()) return
        val last = text[text.length - 1]
        val code = code(last.toString())
        if (code == null && !last.isDigit()) { text.setLength(text.length - 1); return }
        var start = text.length
        while (start > 0 && (code(text[start - 1].toString()) != null || text[start - 1].isDigit())) start--
        val word = text.substring(start)
        text.setLength(start)
        val wordDigits = if (word.all { it.isDigit() }) word else T9.code(word) ?: word
        for (d in wordDigits.dropLast(1)) if (d in '2'..'9') presses.add(T9.certain(d))
        changed()
        variant = candidates().indexOf(word.dropLast(1)).coerceAtLeast(0)
    }

    private fun code(s: String) = T9.code(s)?.takeIf { s.isNotEmpty() }

    fun clear() { text = StringBuilder(); presses.clear(); changed() }

    fun choose(i: Int) { val c = candidates(); if (i in c.indices) { variant = i } }
}
