package com.dhaval.echo.data.transcription

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.dhaval.echo.domain.transcription.SpeechToTextEngine
import com.dhaval.echo.domain.transcription.TranscriptionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume

private const val TAG = "AndroidSTTEngine"

/**
 * Implementation of [SpeechToTextEngine] using Android's system [SpeechRecognizer].
 */
class AndroidSpeechToTextEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : SpeechToTextEngine {

    override suspend fun transcribe(
        audioPath: String,
        languageHint: String?
    ): TranscriptionResult = withContext(Dispatchers.Main) {
        val startTime = System.currentTimeMillis()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return@withContext TranscriptionResult(
                transcript = "",
                providerName = "Android Offline",
                success = false,
                errorMessage = "Speech recognition unavailable on this device"
            )
        }

        suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                languageHint?.let {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, it)
                }
            }

            val listener = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Engine busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                        else -> "Recognition error: $error"
                    }
                    
                    val processingTime = System.currentTimeMillis() - startTime
                    Log.e(TAG, "Transcription failed: $message (Time: ${processingTime}ms)")
                    
                    val result = TranscriptionResult(
                        transcript = "",
                        processingTime = processingTime,
                        providerName = "Android Offline",
                        success = false,
                        errorMessage = message
                    )
                    
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                    recognizer.destroy()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val transcript = matches?.firstOrNull() ?: ""
                    val processingTime = System.currentTimeMillis() - startTime
                    
                    Log.d(TAG, "Transcription complete. Length: ${transcript.length}, Time: ${processingTime}ms")
                    
                    val result = TranscriptionResult(
                        transcript = transcript,
                        processingTime = processingTime,
                        providerName = "Android Offline",
                        success = true
                    )
                    
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                    recognizer.destroy()
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            }

            recognizer.setRecognitionListener(listener)
            
            // Note: Standard SpeechRecognizer typically listens to microphone.
            // For file transcription, a more robust implementation (e.g. Whisper) 
            // would be used in AI-LOCAL-02.
            recognizer.startListening(intent)

            continuation.invokeOnCancellation {
                recognizer.cancel()
                recognizer.destroy()
            }
        }
    }
}
