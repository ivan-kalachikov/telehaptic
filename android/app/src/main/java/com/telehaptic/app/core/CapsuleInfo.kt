package com.telehaptic.app.core

/**
 * State assembled from the capsule's INFO text lines (docs/protocol.md, "Строки INFO").
 * Not thread-safe; owned by one thread.
 */
class CapsuleInfo {
    var firmware = ""
    var bootReason = ""
    var muxPresent: Boolean? = null
    val scan = ArrayList<String>()           // raw SCAN lines of the last scan
    var scanDone = false
    var rateHz = 0
    var rangeG = 0
    var osr = ""
    var imu = false
    var mags: List<MagInfo> = emptyList()
    var batteryMv = 0
    var stats = ""
    var lastConfigLine = ""

    /** Returns a parsed PONG (token, ticks) if the line is one, else null. */
    fun apply(line: String): Pair<Long, Long>? {
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val kv = parts.drop(1).mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }.toMap()
        when (parts[0]) {
            "FW" -> firmware = parts.getOrElse(1) { "" }
            "BOOT" -> bootReason = kv["reason"] ?: ""
            "SCAN" -> {
                if (parts.getOrNull(1) == "begin") {
                    scan.clear()
                    scanDone = false
                    muxPresent = kv["mux"] == "yes"
                }
                scan.add(line.trim())
                if (parts.getOrNull(1) == "end") scanDone = true
            }
            "CFG" -> {
                lastConfigLine = line.trim()
                rateHz = kv["rate"]?.toIntOrNull() ?: rateHz
                rangeG = kv["range"]?.toIntOrNull() ?: rangeG
                osr = kv["osr"] ?: osr
                imu = kv["imu"] == "1"
                val n = kv["n"]?.toIntOrNull() ?: 0
                mags = (1..n).mapNotNull { i ->
                    val v = kv["mag$i"] ?: return@mapNotNull null   // QMC5883P@ch0,1000
                    val at = v.indexOf('@')
                    val comma = v.lastIndexOf(',')
                    if (at < 0 || comma < at) return@mapNotNull null
                    MagInfo(i, v.substring(0, at), v.substring(at + 1, comma), v.substring(comma + 1).toDoubleOrNull() ?: return@mapNotNull null)
                }
            }
            "BAT" -> batteryMv = kv["mv"]?.toIntOrNull() ?: batteryMv
            "STATS" -> stats = line.trim()
            "PONG" -> {
                val token = parts.getOrNull(1)?.toLongOrNull() ?: return null
                val ticks = parts.getOrNull(2)?.toLongOrNull() ?: return null
                return token to ticks
            }
        }
        return null
    }

    /** Problems a person should see, in plain Russian, with what to do. */
    fun problems(): List<String> {
        val out = ArrayList<String>()
        for (l in scan) ScanText.problem(l)?.let { out.add(it) }
        if (scanDone && mags.isEmpty() && out.isEmpty()) out.add("Капсула не нашла ни одного магнитометра. Откройте Диагностику.")
        if (!imu && lastConfigLine.isNotEmpty()) out.add("Встроенный акселерометр и гироскоп не ответили.")
        return out
    }
}

/** Human text for SCAN lines. */
object ScanText {
    private fun place(where: String) = when {
        where == "main" -> "Основная шина"
        where.startsWith("ch") -> "Канал ${where.removePrefix("ch")}"
        else -> where
    }

    private fun wires(where: String): String {
        val ch = where.removePrefix("ch")
        return if (where.startsWith("ch")) "провода SD$ch/SC$ch" else "провода D4 (SDA) и D5 (SCL)"
    }

    /** One line for the scan list in Diagnostics. */
    fun describe(line: String): String {
        val p = line.split(' ').filter { it.isNotEmpty() }
        if (p.size < 2) return line
        val where = p[1]
        return when {
            where == "begin" -> if (line.contains("mux=yes")) "Мультиплексор найден" else "Мультиплексор не ответил, ищу датчики прямо на шине"
            where == "end" -> "Найдено магнитометров: ${line.substringAfter("mags=", "0")}"
            where == "BUS_STUCK" -> "Шина I2C не работает: " + stuckWhich(line) + ". Проверьте провода D4/D5, замыкание на GND и подтяжки."
            p.size >= 4 && p[2].startsWith("0x") && (p[3] == "QMC5883P" || p[3] == "QMC5883L") ->
                "${place(where)}: ${p[3]}, датчик ${p.getOrNull(4)?.removePrefix("mag") ?: "?"}" +
                    (if (line.contains("NOTE")) " (идентификатор необычный, но работаю)" else "")
            p.getOrNull(3) == "HMC5883L" -> "${place(where)}: HMC5883L, этот чип прошивка не поддерживает"
            p.getOrNull(3) == "UNKNOWN" -> "${place(where)}: неизвестный чип по адресу ${p[2]}"
            p.getOrNull(2) == "OTHER" -> "${place(where)}: магнитометра нет, отвечают адреса ${p.drop(3).joinToString(" ")}"
            p.getOrNull(2) == "EMPTY" -> "${place(where)}: датчик не отвечает. Проверьте ${wires(where)} и подтяжки 4,7 кОм к 3,3 В"
            p.getOrNull(2) == "MUX_ERROR" -> "${place(where)}: мультиплексор не переключился"
            else -> line
        }
    }

    private fun stuckWhich(line: String): String {
        val sda = line.contains("sda=0")
        val scl = line.contains("scl=0")
        return when {
            sda && scl -> "обе линии SDA и SCL прижаты к земле"
            sda -> "линия SDA прижата к земле"
            scl -> "линия SCL прижата к земле"
            else -> "линии не в покое"
        }
    }

    /** Only lines that mean something is wrong; null otherwise. */
    fun problem(line: String): String? {
        val p = line.split(' ').filter { it.isNotEmpty() }
        val where = p.getOrNull(1) ?: return null
        if (where == "BUS_STUCK") return describe(line)
        if (p.getOrNull(2) == "EMPTY" && where != "ch2") return describe(line)  // ch2 is the spare
        if (p.getOrNull(3) == "HMC5883L" || p.getOrNull(3) == "UNKNOWN" || p.getOrNull(2) == "MUX_ERROR") return describe(line)
        return null
    }
}
