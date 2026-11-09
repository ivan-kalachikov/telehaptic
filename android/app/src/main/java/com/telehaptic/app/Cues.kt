package com.telehaptic.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Sounds: one beep = touch and hold, two beeps = release. spoken key names if a voice is available. */
class Cues(ctx: Context, private val log: AppLog) {
    private val tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (e: Exception) { null }
    private var tts: TextToSpeech? = null
    private val _voice = kotlinx.coroutines.flow.MutableStateFlow("starting")
    /** "starting", "ok", "no_english" or "error": the home screen warns when there is no voice. */
    val voice: kotlinx.coroutines.flow.StateFlow<String> = _voice
    val ttsStatus get() = _voice.value
    @Volatile private var alertUntil = 0L
    /** When the last alert was spoken (uptime ms): training repeats a key name an alert may have covered. */
    @Volatile var lastAlertUptime = 0L
        private set

    init {
        tts = TextToSpeech(ctx) { st ->
            _voice.value = if (st != TextToSpeech.SUCCESS) "error" else {
                val r = tts?.setLanguage(Locale.US)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) "no_english" else "ok"
            }
            log.i("cues", "tts $ttsStatus")
        }
    }

    fun press() { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150) }
    fun release() { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250) }
    fun end() { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 400) }

    private val vibrator = ctx.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator

    /** Something changed that the person, not looking at the phone, must notice: phone vibration and speech. */
    fun alert(text: String) {
        try { vibrator?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1)) } catch (_: Exception) {}
        // A distinct sound too: the phone may be too far away to feel it vibrate.
        tone?.startTone(ToneGenerator.TONE_PROP_NACK, 300)
        if (ttsStatus == "ok") tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alert")
        alertUntil = android.os.SystemClock.uptimeMillis() + 3000
        lastAlertUptime = android.os.SystemClock.uptimeMillis()
    }

    /** Something to know but not urgent (battery low): spoken after whatever is being said, nothing cut. */
    fun notice(text: String) {
        if (ttsStatus == "ok") tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "notice")
    }

    /** Checks the voice again (after the person installed one in the system settings). */
    fun recheck() {
        val t = tts ?: return
        if (_voice.value == "ok" || _voice.value == "starting") return
        try {
            val r = t.setLanguage(Locale.US)
            _voice.value = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) "no_english" else if (r >= 0) "ok" else _voice.value
            log.i("cues", "tts recheck ${_voice.value}")
        } catch (e: Exception) { log.w("cues", "tts recheck", e) }
    }

    private val audio = ctx.getSystemService(AudioManager::class.java)

    /** Media volume as a share of its maximum (cues and speech use the media stream). */
    fun volumeShare(): Double = try {
        audio.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    } catch (_: Exception) { 1.0 }

    fun raiseVolume() {
        try { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) } catch (_: Exception) {}
    }

    /** Cues replace each other (a new key name cuts the old one), but never cut an alert just spoken. */
    fun say(text: String) {
        val mode = if (android.os.SystemClock.uptimeMillis() < alertUntil) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        if (ttsStatus == "ok") tts?.speak(text, mode, null, "cue")
    }

    fun sayKey(key: String) = say(keyName(key))

    companion object {
        fun keyName(key: String) = NAMES[key] ?: key

        private val NAMES = mapOf(
            "1" to "one", "2" to "two", "3" to "three", "4" to "four", "5" to "five", "6" to "six",
            "7" to "seven", "8" to "eight", "9" to "nine", "*" to "star", "0" to "zero", "#" to "hash",
        )
    }
}
