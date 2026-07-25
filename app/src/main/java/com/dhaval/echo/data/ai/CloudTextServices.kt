package com.dhaval.echo.data.ai

import android.util.Log
import com.dhaval.echo.domain.ai.NarrativeService
import com.dhaval.echo.domain.ai.SummaryService
import com.dhaval.echo.domain.ai.TranscriptionResult
import com.dhaval.echo.domain.ai.TranscriptionService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * A provider-bound text completion. `RealAIManager` supplies one per provider
 * (Claude / OpenAI / Gemini), folding the API key and system prompt in the way
 * each API expects; the services below stay provider-agnostic and just send a
 * prompt with a token budget. Same shape as the vision describer's `send` lambda.
 */
fun interface CloudTextCompleter {
    suspend fun complete(prompt: String, maxTokens: Int): String
}

/** Echo's voice — shared across every cloud text feature. */
internal const val ECHO_SYSTEM_PROMPT =
    "You are Echo, a personal memory companion. You speak to the user directly, warmly, and " +
        "plainly — like a thoughtful friend who has read their diary, not a report generator. " +
        "Ground everything in the memories you are given; never invent people, places, or events " +
        "that aren't there. If there's little to go on, say so briefly rather than padding."

/**
 * Real, LLM-written summaries. Falls *backwards* to the on-device extractive
 * summary on any failure, so a network error or a spent API balance degrades to
 * a plain excerpt rather than a crash or an ugly "[AI unavailable]" marker.
 */
class CloudSummaryService(
    private val fallback: SummaryService,
    private val complete: CloudTextCompleter
) : SummaryService {

    override fun summarize(text: String): Flow<String> = flow {
        if (text.isBlank()) { emit(""); return@flow }

        val prompt = "Summarise this diary entry in 2–3 sentences, in your own warm voice, as if " +
            "reminding the user what it was about. Capture the key thoughts, feelings, and any " +
            "decisions. Do not add a preamble.\n\nEntry:\n${text.take(4000)}"

        val result = runCatching { complete.complete(prompt, 260) }.getOrElse {
            Log.w(TAG, "cloud summary failed; using on-device summary", it)
            null
        }

        if (!result.isNullOrBlank()) emit(result.trim())
        else emitAll(fallback.summarize(text)) // honest local fallback
    }

    private companion object { const val TAG = "CloudSummary" }
}

/**
 * Cloud transcription with an on-device fallback. When a key is present, audio is
 * transcribed by a multilingual cloud model that detects the spoken language
 * itself — so Gujarati (and other scripts on-device Whisper mishears) come back
 * correct. Any failure degrades to the on-device engine rather than losing the
 * recording.
 */
class CloudTranscriptionService(
    private val fallback: TranscriptionService,
    private val cloudTranscribe: suspend (File) -> String
) : TranscriptionService {

    override fun transcribe(audioPath: String): Flow<TranscriptionResult> = flow {
        val text = runCatching { cloudTranscribe(File(audioPath)) }.getOrElse {
            Log.w(TAG, "cloud transcription failed; using on-device", it)
            null
        }
        if (!text.isNullOrBlank()) emit(TranscriptionResult(text = text.trim(), isFinal = true))
        else emitAll(fallback.transcribe(audioPath))
    }

    private companion object { const val TAG = "CloudTranscription" }
}

/**
 * The shared engine behind the narrative features (Remember answer, Reflect
 * analysis, Story narration, "how was my week"). Given a set of memories and an
 * instruction, returns a paragraph in Echo's voice, or null on failure so the
 * caller can fall back to its on-device output.
 */
class CloudNarrativeService(private val complete: CloudTextCompleter) : NarrativeService {

    /**
     * @param instruction what to produce (e.g. "Answer the question below…").
     * @param memoriesBlock the relevant memories, already formatted as text.
     */
    override suspend fun narrate(instruction: String, memoriesBlock: String, maxTokens: Int): String? {
        if (memoriesBlock.isBlank()) return null
        val prompt = buildString {
            appendLine(instruction)
            appendLine()
            appendLine("Here are the relevant memories:")
            appendLine(memoriesBlock.take(12000))
        }
        return runCatching { complete.complete(prompt, maxTokens) }
            .getOrElse {
                Log.w(TAG, "cloud narrative failed", it)
                null
            }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private companion object { const val TAG = "CloudNarrative" }
}
