package com.arisu.companion.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.arisu.companion.settings.AppStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Optional voice mode: push-to-talk SpeechRecognizer + TextToSpeech.
 * Text chat works fine without it; RECORD_AUDIO is only used when the user
 * enables voice features (and grants the runtime permission first).
 */
class VoiceController(
    private val context: android.content.Context,
    private val store: AppStateStore,
) {
    companion object {
        private const val TAG = "VoiceController"
    }

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> get() = _listening

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> get() = _speaking

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var onHeard: ((String) -> Unit)? = null

    fun permissionGranted(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED && store.voiceEnabled.value

    /** Called from the Activity when the runtime permission flow completes. */
    fun maybeInit() {
        if (store.voiceEnabled.value && permissionGrantedState()) initTts()
    }

    private fun permissionGrantedState(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private fun initTts() {
        if (tts != null || !permissionGrantedState()) return
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                store.preferredLanguage.value.takeIf { it.isNotBlank() }?.let {
                    val forced = pickLocale(it)
                    tts?.language = forced
                }
            }
            Log.i(TAG, "TTS ready: ${'$'}ttsReady")
        }.also { engine ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _speaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _speaking.value = false
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _speaking.value = false
                }
            })
        }
    }

    private fun pickLocale(name: String): Locale = try {
        val (lang, country) = name.split("-").let { it[0] to it.getOrNull(1) }
        if (country != null) Locale(lang, country) else Locale(lang)
    } catch (_: Exception) {
        Locale.getDefault()
    }

    /** Available TTS voices for the settings picker (names only). */
    fun availableVoiceNames(): List<String> {
        val engine = tts ?: return emptyList()
        if (!ttsReady) return emptyList()
        return try {
            engine.voices.mapNotNull { v -> v.name.takeIf { it.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Starts listening. [onResult] receives the best transcript.
     */
    fun startListening(onResult: (String) -> Unit) {
        if (!permissionGrantedState()) {
            onResult("")
            return
        }
        onHeard = onResult
        _listening.value = true

        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                _listening.value = false
            }

            override fun onResults(results: Bundle?) {
                _listening.value = false
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (best.isNotBlank()) {
                    onHeard?.invoke(best)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            store.preferredLanguage.value.takeIf { it.isNotBlank() }?.let {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, pickLocale(it))
            }
        }
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            _listening.value = false
        }
    }

    fun stopListening() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        _listening.value = false
    }

    fun speak(text: String) {
        if (!store.voiceEnabled.value || text.isBlank()) return
        initTts()
        val engine = tts ?: return
        if (!ttsReady) return
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
        }
        store.ttsVoiceName.value.takeIf { it.isNotBlank() }?.let { name ->
            try {
                engine.voices.firstOrNull { it.name == name }?.let { engine.voice = it }
            } catch (_: Exception) {
            }
        }
        engine.setSpeechRate(store.ttsSpeed.value)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "arisu-tts-${'$'}{System.currentTimeMillis()}")
    }

    fun stopEverything() {
        stopListening()
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    fun release() {
        stopEverything()
        try {
            recognizer?.destroy()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        recognizer = null
        tts = null
        ttsReady = false
    }
}
