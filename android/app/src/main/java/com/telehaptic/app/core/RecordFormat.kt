package com.telehaptic.app.core

/** CSV layout of samples.csv. Values stay raw; scales are in meta.json. */
object RecordFormat {
    fun samplesHeader(nMags: Int): String {
        val sb = StringBuilder("idx,t_xiao,t_ns")
        for (i in 1..nMags) sb.append(",m${i}x,m${i}y,m${i}z")
        sb.append(",gx,gy,gz,ax,ay,az,flags")
        return sb.toString()
    }

    fun sampleLine(s: Sample, tNs: Long, sb: StringBuilder) {
        sb.setLength(0)
        sb.append(s.index).append(',').append(s.ticks).append(',').append(tNs)
        for (v in s.mag) sb.append(',').append(v)
        for (v in s.gyr) sb.append(',').append(v)
        for (v in s.acc) sb.append(',').append(v)
        sb.append(',').append(s.flags).append('\n')
    }

    const val EVENTS_HEADER = "t_ns,event,trial,key"
    const val PACKETS_HEADER = "t_rx_ns,seq,first_idx,n,last_ticks"
    const val SYNC_HEADER = "t_send_ns,t_recv_ns,token,t_xiao"
    const val INFO_HEADER = "t_rx_ns,line"

    /** File name: KIND[_posture][_rewear]_YYYYMMDD-HHMMSS. */
    fun name(kind: Kind, posture: Posture?, rewear: Boolean, stamp: String): String {
        val sb = StringBuilder(kind.code)
        if (posture != null && kind != Kind.CALIBRATION) sb.append('_').append(posture.code)
        if (rewear && kind != Kind.CALIBRATION) sb.append("_rewear")
        sb.append('_').append(stamp)
        return sb.toString()
    }

    fun csvQuote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
}
