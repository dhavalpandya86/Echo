package com.dhaval.echo.data.understanding

import com.dhaval.echo.data.db.EntityType
import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.domain.understanding.Evidence
import com.dhaval.echo.domain.understanding.EvidenceKind
import com.dhaval.echo.domain.understanding.MemoryAnalyzer
import com.dhaval.echo.domain.understanding.NormalizedContent
import javax.inject.Inject

/**
 * On-device heuristic analyzers — the fallback brains when no AI provider key
 * is configured (hybrid decision). They are deliberately conservative: precision
 * over recall, low confidence values, and silence over guessing. The Claude
 * analyzer suite (MU-1) supersedes their quality; these keep the engine honest
 * and functional fully offline.
 *
 * Per the architecture, each analyzer is independent: none reads another's
 * output. Shared *utilities* (sentence splitting, date parsing) are not shared
 * conclusions.
 */

private val SENTENCE_SPLIT = Regex("""(?<=[.!?\n])\s+""")

private fun sentencesOf(text: String): List<String> =
    text.split(SENTENCE_SPLIT).map { it.trim() }.filter { it.isNotBlank() }

private fun String.sentenceContaining(needle: String): String? =
    sentencesOf(this).firstOrNull { it.contains(needle, ignoreCase = true) }?.take(160)

/** Words that look like names but never are. */
private val CAPITALIZED_STOPLIST = setOf(
    "i", "i'm", "i'll", "i've", "the", "a", "an", "and", "but", "or", "so",
    "today", "tomorrow", "tonight", "yesterday",
    "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    "january", "february", "march", "april", "may", "june", "july",
    "august", "september", "october", "november", "december",
    "ok", "okay", "hello", "hey", "also", "then", "now", "very"
)

// ── Specialist: People ────────────────────────────────────────────────

/**
 * Finds people via interaction context: a capitalized token right after a verb
 * or preposition that implies a person ("call Raj", "meeting with Prabir").
 * Bare capitalized words without that context are skipped — too noisy to
 * claim as people at heuristic confidence.
 */
class LocalPersonAnalyzer @Inject constructor() : MemoryAnalyzer {
    override val kinds = setOf(EvidenceKind.PERSON)

    private val pattern = Regex(
        """\b(call(?:ed|ing)?|meet(?:ing)?|met|with|tell|told|ask(?:ed)?|email(?:ed)?|text(?:ed)?|message(?:d)?|visit(?:ed)?|thank(?:ed)?|from|remind)\s+([A-Z][a-z]{2,})\b"""
    )

    override suspend fun analyze(content: NormalizedContent): List<Evidence> =
        pattern.findAll(content.text)
            .map { it.groupValues[2] }
            .filter { it.lowercase() !in CAPITALIZED_STOPLIST }
            .distinctBy { it.lowercase() }
            .map { name ->
                Evidence(
                    kind = EvidenceKind.PERSON,
                    value = name,
                    evidenceText = content.text.sentenceContaining(name),
                    confidence = 0.6f
                )
            }
            .toList()
}

// ── Specialist: Projects ──────────────────────────────────────────────

/**
 * Finds projects via possessive context: a capitalized token attached to a
 * project-ish noun ("the Oceanis logo", "Earth24 launch") or explicit framing
 * ("project Oceanis", "for Oceanis").
 */
class LocalProjectAnalyzer @Inject constructor() : MemoryAnalyzer {
    override val kinds = setOf(EvidenceKind.PROJECT)

    private val projectNouns =
        "logo|brand(?:ing)?|website|app|launch|deal|order|shipment|packaging|project|export|campaign|proposal|pitch|design"

    private val patterns = listOf(
        Regex("""\b([A-Z][A-Za-z0-9]{2,})\s+(?:$projectNouns)\b"""),
        Regex("""\b(?:project|for)\s+([A-Z][A-Za-z0-9]{2,})\b""")
    )

    override suspend fun analyze(content: NormalizedContent): List<Evidence> =
        patterns.flatMap { p -> p.findAll(content.text).map { it.groupValues[1] } }
            .filter { it.lowercase() !in CAPITALIZED_STOPLIST }
            .distinctBy { it.lowercase() }
            .map { name ->
                Evidence(
                    kind = EvidenceKind.PROJECT,
                    value = name,
                    evidenceText = content.text.sentenceContaining(name),
                    confidence = 0.55f
                )
            }
}

// ── Specialist: Tasks ─────────────────────────────────────────────────

/**
 * Finds obligations: "need to X", "have to X", "must X", "remember to X",
 * "don't forget to X". The clause becomes the task; a date hint in the same
 * sentence becomes its due date.
 */
class LocalTaskAnalyzer @Inject constructor() : MemoryAnalyzer {
    override val kinds = setOf(EvidenceKind.TASK)

    private val pattern = Regex(
        """\b(?:need to|have to|has to|must|remember to|don't forget to|should)\s+([^.!?\n]{3,80})""",
        RegexOption.IGNORE_CASE
    )

    override suspend fun analyze(content: NormalizedContent): List<Evidence> =
        pattern.findAll(content.text)
            .map { it.groupValues[1].trim().trimEnd(',', ';') }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(5)
            .map { clause ->
                val sentence = content.text.sentenceContaining(clause)
                Evidence(
                    kind = EvidenceKind.TASK,
                    value = clause.replaceFirstChar { it.uppercase() },
                    evidenceText = sentence,
                    confidence = 0.65f,
                    dueAtMillis = sentence
                        ?.let { DateHintParser.firstHint(it, content.capturedAt)?.atMillis }
                )
            }
            .toList()
}

// ── Specialist: Reminders (dates) ─────────────────────────────────────

/**
 * Finds time anchors: "tomorrow", "next monday", "in 3 days" — resolved
 * against the capture time into an actual instant.
 */
class LocalReminderAnalyzer @Inject constructor() : MemoryAnalyzer {
    override val kinds = setOf(EvidenceKind.REMINDER)

    override suspend fun analyze(content: NormalizedContent): List<Evidence> {
        val hint = DateHintParser.firstHint(content.text, content.capturedAt) ?: return emptyList()
        return listOf(
            Evidence(
                kind = EvidenceKind.REMINDER,
                value = hint.phrase,
                evidenceText = content.text.sentenceContaining(hint.phrase),
                confidence = 0.7f,
                dueAtMillis = hint.atMillis
            )
        )
    }
}

// ── Specialist: Mood ──────────────────────────────────────────────────

/**
 * Lexicon-based mood detection. Emits nothing when no signal exists — an
 * absent mood is more honest than a fabricated "Neutral".
 */
class LocalMoodAnalyzer @Inject constructor() : MemoryAnalyzer {
    override val kinds = setOf(EvidenceKind.MOOD)

    private val lexicon = mapOf(
        "Happy" to listOf("happy", "glad", "wonderful", "delighted", "joy"),
        "Excited" to listOf("excited", "thrilled", "can't wait", "amazing"),
        "Inspired" to listOf("inspired", "inspiring", "sparked an idea"),
        "Motivated" to listOf("motivated", "determined", "focused"),
        "Grateful" to listOf("grateful", "thankful", "blessed"),
        "Frustrated" to listOf("frustrated", "annoyed", "angry", "fed up"),
        "Anxious" to listOf("worried", "anxious", "nervous", "stressed"),
        "Sad" to listOf("sad", "upset", "heartbroken", "miss him", "miss her"),
        "Tired" to listOf("tired", "exhausted", "drained"),
        "Confused" to listOf("confused", "unsure", "torn", "don't know what")
    )

    override suspend fun analyze(content: NormalizedContent): List<Evidence> {
        val lower = content.text.lowercase()
        return lexicon.mapNotNull { (mood, cues) ->
            val cue = cues.firstOrNull { it in lower } ?: return@mapNotNull null
            Evidence(
                kind = EvidenceKind.MOOD,
                value = mood,
                evidenceText = content.text.sentenceContaining(cue),
                confidence = 0.5f
            )
        }.take(2)
    }
}

// ── Specialist: Known entities (graph recall) ─────────────────────────

/**
 * Matches the memory's text against entities the graph already knows, by name
 * or alias. This is what makes the second "Raj" memory link to the *same* Raj
 * — and it is intentionally the only analyzer with graph awareness: knowing
 * existing entities is not knowing other analyzers' output.
 */
class KnownEntityAnalyzer @Inject constructor(
    private val understandingDao: UnderstandingDao
) : MemoryAnalyzer {
    override val kinds = setOf(
        EvidenceKind.PERSON, EvidenceKind.PROJECT, EvidenceKind.TOPIC,
        EvidenceKind.PLACE, EvidenceKind.ORG, EvidenceKind.PRODUCT
    )

    private val typeToKind = mapOf(
        EntityType.PERSON to EvidenceKind.PERSON,
        EntityType.PROJECT to EvidenceKind.PROJECT,
        EntityType.TOPIC to EvidenceKind.TOPIC,
        EntityType.PLACE to EvidenceKind.PLACE,
        EntityType.ORG to EvidenceKind.ORG,
        EntityType.PRODUCT to EvidenceKind.PRODUCT
    )

    override suspend fun analyze(content: NormalizedContent): List<Evidence> {
        val lower = content.text.lowercase()
        val results = mutableListOf<Evidence>()

        for ((type, kind) in typeToKind) {
            for (entity in understandingDao.getEntitiesByType(content.userId, type)) {
                val surfaceForms = (entity.aliases + entity.name).filter { it.length >= 3 }
                val matched = surfaceForms.firstOrNull { form ->
                    Regex("""\b${Regex.escape(form.lowercase())}\b""").containsMatchIn(lower)
                } ?: continue
                results += Evidence(
                    kind = kind,
                    value = entity.name,   // canonical, not the alias — identity wins
                    evidenceText = content.text.sentenceContaining(matched),
                    confidence = 0.85f
                )
            }
        }
        return results.distinctBy { it.kind to it.value.lowercase() }
    }
}
