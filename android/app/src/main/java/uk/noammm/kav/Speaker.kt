package uk.noammm.kav

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import uk.noammm.kav.ui.Lang
import uk.noammm.kav.ui.T
import java.util.Locale

// Trip alerts said out loud, as Moovit's app does ("your bus is arriving"): the phone's own text-to-speech, in Kav's
// language, over music the way a navigation app talks. A phone with no voice for that language keeps to the sound.
internal object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun say(ctx: Context, text: String) {
        val engine = tts
        if (engine != null && ready) { speak(engine, text); return }
        pending = text
        if (engine == null) tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            val started = tts
            if (ready && started != null) pending?.let { speak(started, it) }
            pending = null
        }
    }

    private fun speak(engine: TextToSpeech, text: String) {
        val voice = if (T.lang == Lang.HE) Locale.forLanguageTag("he-IL") else Locale.UK
        val found = engine.setLanguage(voice)
        if (found == TextToSpeech.LANG_MISSING_DATA || found == TextToSpeech.LANG_NOT_SUPPORTED) return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.speak(text.replace('/', ','), TextToSpeech.QUEUE_FLUSH, null, "kav-alert")
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        pending = null
    }
}
