package com.dhaval.echo.domain.transcription

import kotlinx.coroutines.flow.SharedFlow

/** A moment in a live dictation session. */
sealed interface DictationEvent {
    /** The recognizer is listening. */
    data object Ready : DictationEvent
    /** Interim text for the utterance in progress — replaces the previous partial. */
    data class Partial(val text: String) : DictationEvent
    /** A finalized utterance — append this and start a fresh partial. */
    data class Final(val text: String) : DictationEvent
    /** The session ended (after [LiveDictation.stop] or a fatal error). */
    data object Ended : DictationEvent
    /** A fatal problem; the session is over. */
    data class Failed(val message: String) : DictationEvent
}

/**
 * Live, on-device speech-to-text for dictating into a text field — words stream in
 * as you speak. Backed by the platform recognizer; mic-only (no file), so it is a
 * separate concern from the file-based [SpeechToTextEngine] used for voice memos.
 */
interface LiveDictation {
    /** Whether the device has any speech recognizer available at all. */
    val isAvailable: Boolean

    /** Whether a fully on-device (private, offline) recognizer is available. */
    val isOnDeviceAvailable: Boolean

    /** Emits [DictationEvent]s for the active session. */
    val events: SharedFlow<DictationEvent>

    /** Begin continuous listening. [languageTag] is BCP-47 (e.g. "en-IN", "gu-IN") or null for the device default. */
    fun start(languageTag: String?)

    /** Stop after the current utterance finalizes (emits its [DictationEvent.Final] then [DictationEvent.Ended]). */
    fun stop()

    /** Tear down the recognizer immediately (no final result). */
    fun release()
}
