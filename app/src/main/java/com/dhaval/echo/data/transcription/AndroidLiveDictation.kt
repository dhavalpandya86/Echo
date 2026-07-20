package com.dhaval.echo.data.transcription

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.dhaval.echo.domain.transcription.DictationEvent
import com.dhaval.echo.domain.transcription.LiveDictation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live dictation via the platform [SpeechRecognizer]. Streams partial results as
 * the user speaks and re-arms itself across pauses so a whole thought can be
 * dictated continuously. Prefers the on-device recognizer (private, offline);
 * otherwise falls back to the platform default.
 *
 * All recognizer calls are marshalled to the main thread, as the API requires.
 */
@Singleton
class AndroidLiveDictation @Inject constructor(
    @ApplicationContext private val context: Context
) : LiveDictation {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var languageTag: String? = null

    /** True between stop() and the final result — so we finish the utterance then end. */
    private var stopping = false

    /** Consecutive errors with no speech in between — a safety valve against tight loops. */
    private var errorStreak = 0

    private val _events = MutableSharedFlow<DictationEvent>(
        replay = 0, extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<DictationEvent> = _events.asSharedFlow()

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    override val isOnDeviceAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun start(languageTag: String?) {
        this.languageTag = languageTag
        main.post {
            stopping = false
            errorStreak = 0
            recognizer?.destroy()
            recognizer = createRecognizer().also { it.setRecognitionListener(listener) }
            beginListening()
        }
    }

    override fun stop() {
        main.post {
            stopping = true
            runCatching { recognizer?.stopListening() }
        }
    }

    override fun release() {
        main.post {
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }

    private fun createRecognizer(): SpeechRecognizer =
        if (isOnDeviceAvailable) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

    private fun beginListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Keep audio on-device (private).
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Hold one utterance across short thinking-pauses so we don't pay the
            // recognizer warm-up + first-partial latency again after every pause.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        runCatching { recognizer?.startListening(intent) }
            .onFailure { Log.w(TAG, "startListening failed", it) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { _events.tryEmit(DictationEvent.Ready) }

        override fun onBeginningOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { errorStreak = 0; _events.tryEmit(DictationEvent.Partial(it)) }
        }

        override fun onResults(results: Bundle?) {
            firstResult(results)?.let { errorStreak = 0; _events.tryEmit(DictationEvent.Final(it)) }
            if (stopping) end() else beginListening() // continuous
        }

        override fun onError(error: Int) {
            if (stopping) { end(); return }
            // Fatal — no point retrying:
            val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                error == SpeechRecognizer.ERROR_NETWORK ||
                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
            when {
                fatal -> { _events.tryEmit(DictationEvent.Failed(errorText(error))); releaseInternal() }
                // Everything else during an active session (silence, no-match, timeout,
                // recognizer-busy, client) is expected between phrases — re-arm.
                errorStreak++ >= MAX_ERROR_STREAK -> {
                    _events.tryEmit(DictationEvent.Failed("Live dictation stopped hearing you. Try again."))
                    releaseInternal()
                }
                else -> main.postDelayed({ if (!stopping) beginListening() }, RESTART_DELAY_MS)
            }
        }

        override fun onEndOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun end() {
        _events.tryEmit(DictationEvent.Ended)
        releaseInternal()
    }

    private fun releaseInternal() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Dictation needs a network for this language."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "That language isn't available for live dictation on this device."
        else -> "Live dictation isn't available right now."
    }

    private companion object {
        const val TAG = "LiveDictation"
        const val RESTART_DELAY_MS = 60L
        const val MAX_ERROR_STREAK = 20   // ~ many silent re-arms before giving up
    }
}
