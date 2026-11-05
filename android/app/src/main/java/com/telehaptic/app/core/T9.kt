package com.telehaptic.app.core

/**
 * T9 for English: keys 2-9 carry letters as on phone keypads (ITU E.161), words come from a
 * frequency list. 0 = space (accepts the word), 1 = full stop, * = next variant of the word,
 * # = erase.
 */
class T9(words: Sequence<Pair<String, Long>>) {
    private val byCode = HashMap<String, MutableList<Pair<String, Long>>>()
    private val byPrefix = HashMap<String, MutableList<Pair<String, Long>>>()

    init {
        for ((w, f) in words) {
            val code = code(w) ?: continue
            byCode.getOrPut(code) { ArrayList() }.add(w to f)
            for (len in 1..minOf(code.length - 1, 6)) byPrefix.getOrPut(code.substring(0, len)) { ArrayList() }.add(w to f)
        }
        for (l in byCode.values) l.sortByDescending { it.second }
        for ((k, l) in byPrefix) { l.sortByDescending { it.second }; if (l.size > 20) byPrefix[k] = l.subList(0, 20).toMutableList() }
    }

    /** Words for a digit string: exact matches by frequency; if none, the beginnings of longer words. */
    fun candidates(digits: String, max: Int = 6): List<String> {
        if (digits.isEmpty()) return emptyList()
        val exact = byCode[digits]?.map { it.first } ?: emptyList()
        if (exact.isNotEmpty()) return exact.take(max)
        val pre = (byPrefix[digits.take(6)] ?: emptyList()).filter { code(it.first)!!.startsWith(digits) }
        return pre.map { it.first.take(digits.length) }.distinct().take(max).ifEmpty { listOf(digits) }
    }

    companion object {
        private val LETTERS = mapOf(
            '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
            '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz",
        )
        private val DIGIT = HashMap<Char, Char>().apply { for ((d, s) in LETTERS) for (c in s) put(c, d) }

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

/** Typed text with the word being composed from digits. */
class Typing(private val t9: T9) {
    var text = StringBuilder(); private set
    var digits = ""; private set
    var variant = 0; private set

    fun candidates() = t9.candidates(digits)
    fun currentWord(): String = candidates().let { if (it.isEmpty()) "" else it[variant % it.size] }

    /** What the screen shows: accepted text plus the current word. */
    fun display() = text.toString() + currentWord()

    fun key(k: String) {
        when (k) {
            in "2".."9" -> { digits += k; variant = 0 }
            "0" -> { commit(); text.append(' ') }
            "1" -> { commit(); text.append('.') }
            "*" -> if (digits.isNotEmpty()) variant++
            "#" -> erase()
        }
    }

    private fun commit() {
        text.append(currentWord())
        digits = ""; variant = 0
    }

    fun erase() {
        if (digits.isNotEmpty()) { digits = digits.dropLast(1); variant = 0 }
        else if (text.isNotEmpty()) text.setLength(text.length - 1)
    }

    fun clear() { text = StringBuilder(); digits = ""; variant = 0 }

    fun choose(i: Int) { val c = candidates(); if (i in c.indices) { variant = i } }
}
