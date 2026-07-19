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
    // pronouns & fillers that can trail a case-insensitive trigger ("with Me")
    "me", "my", "we", "us", "you", "your", "he", "she", "they", "them", "him",
    "her", "his", "it", "this", "that", "these", "those",
    // ubiquitous common nouns that aren't a specific place/person
    "home", "work", "here", "there", "everyone", "someone", "people",
    "today", "tomorrow", "tonight", "yesterday",
    "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    "january", "february", "march", "april", "may", "june", "july",
    "august", "september", "october", "november", "december",
    "ok", "okay", "hello", "hey", "also", "then", "now", "very"
)

// ── Specialist: People ────────────────────────────────────────────────

/**
 * Finds people via three kinds of context, because real notes name people in
 * more than one way:
 *  1. Interaction — a capitalized name after a person-verb/preposition
 *     ("call Raj", "meeting with Prabir", "from Meera").
 *  2. Relationship — a name after a kinship/role word ("my brother Sam",
 *     "our friend Meera", "boss Priya").
 *  3. Subject — a name doing something human ("Raj said", "Meera came over",
 *     "Sam and I").
 * Bare capitalized words with none of that context are still skipped — too noisy
 * to claim as people at heuristic confidence. The corrections loop lets the user
 * archive or merge the occasional miss, so a little more recall is safe now.
 */
class LocalPersonAnalyzer @Inject constructor() : MemoryAnalyzer {
    override val kinds = setOf(EvidenceKind.PERSON)

    // Group 1 holds the name in every pattern. Trigger words are case-insensitive
    // (they often start a sentence, capitalized) but names must be capitalized.
    private val patterns = listOf(
        // 1. interaction verb / preposition → name
        Regex("""\b(?i:call(?:ed|ing)?|meet(?:ing)?|met|with|tell|told|ask(?:ed)?|email(?:ed)?|text(?:ed)?|message(?:d)?|visit(?:ed)?|thank(?:ed)?|saw|see|from|remind)\s+([A-Z][a-z]{1,})\b"""),
        // 2. relationship word → name
        Regex("""\b(?i:my|our|his|her|their)\s+(?i:friend|brother|sister|mom|mother|dad|father|son|daughter|kid|wife|husband|boss|colleague|coworker|cousin|uncle|aunt|partner|boyfriend|girlfriend|neighbou?r|teammate|manager|mentor)s?\s+([A-Z][a-z]{1,})\b"""),
        // 3. name in subject position doing something human
        Regex("""\b([A-Z][a-z]{2,})\s+(?i:said|says|told|called|texted|messaged|emailed|came|come|asked|mentioned|thinks|feels|wants|and I)\b"""),
        // 4. a full "First Last" name, gated by an introduction cue so it doesn't
        //    swallow two-word places ("this is Komal Patel", "with me Priya Shah")
        Regex("""\b(?i:this is|i am|i'm|named|call me|with me|me|and)\s+([A-Z][a-z]+\s+[A-Z][a-z]+)\b""")
    )

    override suspend fun analyze(content: NormalizedContent): List<Evidence> =
        patterns.asSequence()
            .flatMap { p -> p.findAll(content.text).map { it.groupValues[1] } }
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

// ── Specialist: Places ────────────────────────────────────────────────

/**
 * Finds places via movement/location context: a capitalized place name after a
 * travel verb or spatial preposition ("went to Goa", "in Ahmedabad", "trip to
 * New Delhi", "back from Mumbai"). Captures up to two capitalized words so
 * "New Delhi" stays one place. There was no fresh place extractor before — places
 * only surfaced once already known — so informal location mentions were invisible.
 */
class LocalPlaceAnalyzer @Inject constructor() : MemoryAnalyzer {
    override val kinds = setOf(EvidenceKind.PLACE)

    private val patterns = listOf(
        // preposition / movement verb → place (triggers case-insensitive)
        Regex("""\b(?i:went to|going to|go to|back to|back from|flew to|drove to|drive to|travel(?:l?ed|ling)? to|trip to|visit(?:ed|ing)? to?|arrived (?:in|at)|in|at|near|around|from)\s+([A-Z][A-Za-z]{2,}(?:\s+[A-Z][A-Za-z]+)?)\b"""),
        // place written before a travel noun ("the Goa trip", "Manali vacation")
        Regex("""\b([A-Z][A-Za-z]{2,})\s+(?i:trip|vacation|holiday|getaway|visit|tour)\b""")
    )

    override suspend fun analyze(content: NormalizedContent): List<Evidence> =
        patterns.asSequence()
            .flatMap { p -> p.findAll(content.text).map { it.groupValues[1] } }
            .map { it.trim() }
            .filter { place -> place.split(" ").none { it.lowercase() in CAPITALIZED_STOPLIST } }
            .distinctBy { it.lowercase() }
            .map { place ->
                Evidence(
                    kind = EvidenceKind.PLACE,
                    value = place,
                    evidenceText = content.text.sentenceContaining(place),
                    confidence = 0.5f
                )
            }
            .toList()
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
