package com.dhaval.echo.data.understanding

import android.util.Log
import com.dhaval.echo.domain.understanding.Evidence
import com.dhaval.echo.domain.understanding.EvidenceKind
import com.dhaval.echo.domain.understanding.MemoryAnalyzer
import com.dhaval.echo.domain.understanding.NormalizedContent
import java.time.format.DateTimeFormatter

/**
 * The extraction prompt + user-message shape, shared by every cloud provider's
 * evidence analyzer (Claude / OpenAI / Gemini) so they extract the same board in
 * the same JSON contract. The [ClaudeEvidenceParser] fans the JSON out per kind.
 */
internal object EvidenceExtraction {

    fun userMessage(content: NormalizedContent): String = buildString {
        append("Memory captured at ")
        append(content.capturedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        append(" (local time).\n---\n")
        append(content.text)
    }

    val SYSTEM_PROMPT = """
        You are the extraction engine inside Echo, a personal memory app. Read the one memory the
        user captured and extract structured facts about it.

        Return ONLY a JSON object — no prose, no markdown fences — of exactly this shape:
        {"evidence":[{"kind":"<KIND>","value":"<text>","quote":"<verbatim span>","confidence":<0..1>,"due":"<ISO-8601, optional>"}]}

        KIND is one of: PERSON, PROJECT, TOPIC, PLACE, ORG, PRODUCT, TASK, REMINDER, MOOD, DECISION.

        Rules:
        - PERSON/PROJECT/PLACE/ORG/PRODUCT/TOPIC: use the entity's canonical name as `value`
          (e.g. "Raj", "Oceanis"). Emit one object per distinct entity. Extract every named person,
          place, and project you find, even in a short caption ("my kid Prabir and his friend Avyan
          at school" → PERSON Prabir, PERSON Avyan, PLACE school).
        - TASK: something the user intends to do; `value` is an imperative phrase
          ("Call Raj about the Oceanis logo").
        - REMINDER: a time-anchored prompt. TASK and REMINDER may include `due`.
        - MOOD: a single word for an emotion the user actually expresses ("Excited"). Never
          invent a neutral or default mood when none is stated.
        - DECISION: a choice the user states they have made.
        - `quote` must be a verbatim span copied from the memory. `confidence` is how sure you are.
        - Resolve relative dates ("tomorrow", "next week") against the capture time the user gives.
        - Omit any kind the memory does not contain. An empty {"evidence":[]} is valid and honest.
    """.trimIndent()
}

/**
 * Cloud evidence extraction for OpenAI / Gemini (MU-1), mirroring
 * [ClaudeMemoryAnalyzer]: one structured-output call per memory, parsed by the
 * shared [ClaudeEvidenceParser]. [extract] folds the system prompt in the way its
 * provider wants and returns the raw JSON. On any failure it degrades loudly to
 * the on-device heuristics ([fallback]) — a weaker but real board, never a faked one.
 *
 * This is what lets a cloud-key user get reliable people/places/projects (and so
 * populated Worlds) instead of the sparse output of the local name heuristics.
 */
class CloudMemoryAnalyzer(
    private val fallback: List<MemoryAnalyzer>,
    private val extract: suspend (userMessage: String) -> String
) : MemoryAnalyzer {

    override val kinds: Set<EvidenceKind> = EvidenceKind.values().toSet()

    override suspend fun analyze(content: NormalizedContent): List<Evidence> {
        return try {
            val raw = extract(EvidenceExtraction.userMessage(content))
            ClaudeEvidenceParser.parse(raw, content.capturedAt).also {
                Log.d(TAG, "Cloud extracted ${it.size} evidence items for ${content.memoryId}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cloud analysis failed for ${content.memoryId} — degrading to on-device", e)
            fallback.flatMap { analyzer ->
                runCatching { analyzer.analyze(content) }
                    .onFailure { Log.e(TAG, "Fallback ${analyzer::class.simpleName} also failed", it) }
                    .getOrDefault(emptyList())
            }
        }
    }

    private companion object { const val TAG = "CloudMemoryAnalyzer" }
}
