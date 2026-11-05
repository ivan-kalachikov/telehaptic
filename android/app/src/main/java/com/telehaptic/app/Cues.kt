package com.telehaptic.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Sounds: one beep = touch and hold, two beeps = release. Russian voice for keys if available. */
class Cues(ctx: Context, private val log: AppLog) {
    private val tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (e: Exception) { null }
    private var tts: TextToSpeech? = null
    @Volatile var ttsStatus = "starting"
        private set

    init {
        tts = TextToSpeech(ctx) { st ->
            ttsStatus = if (st != TextToSpeech.SUCCESS) "error" else {
                val r = tts?.setLanguage(Locale.forLanguageTag("ru-RU"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) "no_russian" else "ok"
            }
            log.i("cues", "tts $ttsStatus")
        }
    }

    fun press() { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150) }
    fun release() { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250) }
    fun end() { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 400) }

    fun say(text: String) {
        if (ttsStatus == "ok") tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue")
    }

    fun sayKey(key: String) = say(NAMES[key] ?: key)

    companion object {
        private val NAMES = mapOf(
            "1" to "один", "2" to "два", "3" to "три", "4" to "четыре", "5" to "пять", "6" to "шесть",
            "7" to "семь", "8" to "восемь", "9" to "девять", "*" to "звёздочка", "0" to "ноль", "#" to "решётка",
        )
    }
}
