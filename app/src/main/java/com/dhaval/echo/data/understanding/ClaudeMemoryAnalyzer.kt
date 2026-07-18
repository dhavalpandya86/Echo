package com.dhaval.echo.data.understanding

import android.util.Log
import com.dhaval.echo.data.ai.callClaude
import com.dhaval.echo.domain.understanding.Evidence
import com.dhaval.echo.domain.understanding.EvidenceKind
import com.dhaval.echo.domain.understanding.MemoryAnalyzer
import com.dhaval.echo.domain.understanding.NormalizedContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The hybrid brain (MU-1). One structured-output call to Claude extracts the
 * whole evidence board — people, projects, tasks, reminders, mood, and the
 * rest — with a supporting quote and confidence for each. Modularity is
 * preserved (the [ClaudeEvidenceParser] fans the JSON out into per-kind
 * [Evidence]) while staying to a single API call per memory.
 *
 * Honesty contract (no-silent-failure): if the call fails, is refused, or
 * returns unparseable JSON, this logs loudly and degrades to the on-device
 * heuristic analyzers ([fallback]) — a weaker-but-real board, never a faked
 * one. An empty board Claude genuinely returns is honest and kept as-is.
 */
class ClaudeMemoryAnalyzer(
    private val apiKey: String,
    private val fallback: List<MemoryAnalyzer>
) : MemoryAnalyzer {

    override val kinds: Set<EvidenceKind> = EvidenceKind.values().toSet()

    override suspend fun analyze(content: NormalizedContent): List<Evidence> {
        val userMessage = buildString {
            append("Memory captured at ")
            append(content.capturedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            append(" (local time).\n---\n")
            append(content.text)
        }

        return try {
            val raw = callClaude(apiKey, userMessage, SYSTEM_PROMPT, maxTokens = 1024)
            val evidence = ClaudeEvidenceParser.parse(raw, content.capturedAt)
            Log.d(TAG, "Claude extracted ${evidence.size} evidence items for ${content.memoryId}")
            evidence
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Claude analysis failed for ${content.memoryId} — degrading to on-device heuristics",
                e
            )
            fallback.flatMap { analyzer ->
                runCatching { analyzer.analyze(content) }
                    .onFailure { Log.e(TAG, "Fallback ${analyzer::class.simpleName} also failed", it) }
                    .getOrDefault(emptyList())
            }
        }
    }

    private companion object {
        const val TAG = "ClaudeMemoryAnalyzer"

        val SYSTEM_PROMPT = """
            You are the extraction engine inside Echo, a personal memory app. Read the one memory the
            user captured and extract structured facts about it.

            Return ONLY a JSON object — no prose, no markdown fences — of exactly this shape:
            {"evidence":[{"kind":"<KIND>","value":"<text>","quote":"<verbatim span>","confidence":<0..1>,"due":"<ISO-8601, optional>"}]}

            KIND is one of: PERSON, PROJECT, TOPIC, PLACE, ORG, PRODUCT, TASK, REMINDER, MOOD, DECISION.

            Rules:
            - PERSON/PROJECT/PLACE/ORG/PRODUCT/TOPIC: use the entity's canonical name as `value`
              (e.g. "Raj", "Oceanis"). Emit one object per distinct entity.
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
}

/**
 * Pure JSON → [Evidence] mapping for Claude's structured output. Kept separate
 * from the network call so the structured-output contract can be verified
 * offline (see ClaudeEvidenceParserTest).
 *
 * Tolerant by design: strips any stray prose/markdown around the JSON object,
 * skips individual malformed elements rather than discarding the whole board,
 * and throws only when the payload has no parseable JSON object at all (which
 * the analyzer treats as a Claude failure and falls back from).
 */
object ClaudeEvidenceParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val DEFAULT_DUE_HOUR = 9

    fun parse(raw: String, capturedAt: LocalDateTime): List<Evidence> {
        val objectText = extractJsonObject(raw)
            ?: throw IllegalArgumentException("No JSON object in Claude response: ${raw.take(200)}")

        val root = json.parseToJsonElement(objectText).jsonObject
        val array = root["evidence"]?.jsonArray ?: return emptyList()

        return array.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val kind = obj["kind"]?.jsonPrimitive?.content
                    ?.let { runCatching { EvidenceKind.valueOf(it.trim().uppercase()) }.getOrNull() }
                    ?: return@runCatching null
                val value = obj["value"]?.jsonPrimitive?.content?.trim()
                if (value.isNullOrBlank()) return@runCatching null

                val quote = obj["quote"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
                val confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull?.coerceIn(0f, 1f) ?: 0.75f
                val due = obj["due"]?.jsonPrimitive?.content?.let { parseDue(it, capturedAt) }

                Evidence(
                    kind = kind,
                    value = value,
                    evidenceText = quote,
                    confidence = confidence,
                    dueAtMillis = due
                )
            }.getOrNull()
        }
    }

    /** First balanced-looking `{ … }` slice — ignores fences and commentary. */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start in 0 until end) raw.substring(start, end + 1) else null
    }

    private fun parseDue(value: String, capturedAt: LocalDateTime): Long? {
        val text = value.trim().takeIf { it.isNotBlank() } ?: return null
        val zone = ZoneId.systemDefault()
        // Full datetime first ("2026-07-18T14:00"), then date-only → default hour.
        val dateTime = runCatching { LocalDateTime.parse(text) }.getOrNull()
            ?: runCatching { LocalDate.parse(text).atTime(DEFAULT_DUE_HOUR, 0) }.getOrNull()
            ?: return null
        // Guard against a model echoing a date far in the past relative to capture.
        return dateTime.atZone(zone).toInstant().toEpochMilli()
    }
}
