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
        //
        // The caretaking verbs (take, bring, drop, pick up, collect, teach…)
        // were the gap that made this analyzer miss most family notes: "I need
        // to take Prabir for swimming" names a person as plainly as "call Raj"
        // does, but only the second kind of sentence used to be recognised.
        Regex("""\b(?i:call(?:ed|ing)?|meet(?:ing)?|met|with|tell|told|ask(?:ed)?|email(?:ed)?|text(?:ed)?|message(?:d)?|visit(?:ed)?|thank(?:ed)?|saw|see|from|remind|tak(?:e|ing)|took|bring(?:ing)?|brought|drop(?:ping|ped)?|pick(?:ing|ed)?\s+up|collect(?:ed|ing)?|help(?:ed|ing)?|teach(?:ing)?|taught|show(?:ed|ing)?|send|sent|give|gave|join(?:ed|ing)?|invit(?:e|ed|ing))\s+([A-Z][a-z]{1,})\b"""),
        // 1b. doing something *for* someone — "buy goggles for Prabir".
        //     Gated on a transfer verb so it can't swallow "for Christmas".
        Regex("""\b(?i:buy|bought|get|got|book(?:ed)?|order(?:ed)?|bring|brought|send|sent|make|made|pack(?:ed)?|arrange(?:d)?)\b[^.!?\n]{0,40}?\bfor\s+([A-Z][a-z]{1,})\b"""),
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

// ── Specialist: Activities ────────────────────────────────────────────

/**
 * Finds things the user or someone they know *does*, from a lexicon of
 * recurring life activities.
 *
 * Lexicon rather than pattern-matching because an activity is only useful when
 * it recurs under the same name: "swimming", "went swimming" and "Prabir's
 * swimming" must all become the one Swimming entity, or the graph fills up with
 * near-duplicates that never group. A fixed vocabulary guarantees that at the
 * cost of missing activities nobody listed — which a model handles when one is
 * installed.
 */
class LocalActivityAnalyzer @Inject constructor() : MemoryAnalyzer {
    override val kinds = setOf(EvidenceKind.ACTIVITY)

    /** Canonical name → the surface forms that mean it. */
    private val lexicon = mapOf(
        "Swimming" to listOf("swimming", "swim class", "swim lesson", "swim practice"),
        "Cricket" to listOf("cricket", "cricket practice", "cricket match"),
        "Football" to listOf("football", "soccer"),
        "Tennis" to listOf("tennis"),
        "Badminton" to listOf("badminton"),
        "Gym" to listOf("gym", "workout", "working out", "weight training"),
        "Yoga" to listOf("yoga"),
        "Running" to listOf("running", "jog", "jogging", "a run", "marathon"),
        "Cycling" to listOf("cycling", "bike ride", "cycle ride"),
        "Walking" to listOf("a walk", "walking", "hike", "hiking", "trek"),
        "Dance" to listOf("dance class", "dancing", "dance practice"),
        "Music practice" to listOf("piano", "guitar", "violin", "music class", "music lesson"),
        "School" to listOf("school", "parents evening", "parent teacher"),
        "Tuition" to listOf("tuition", "tutor", "coaching class"),
        "Exam" to listOf("exam", "test paper", "board exam"),
        "Meeting" to listOf("meeting", "standup", "stand-up", "review call", "sync"),
        "Interview" to listOf("interview"),
        "Travel" to listOf("flight", "train", "road trip", "travelling", "traveling"),
        "Shopping" to listOf("shopping", "grocery", "groceries"),
        "Cooking" to listOf("cooking", "making dinner", "meal prep"),
        "Doctor appointment" to listOf("doctor", "clinic", "check-up", "checkup", "physio"),
        "Dentist appointment" to listOf("dentist", "dental"),
        "Birthday" to listOf("birthday"),
        "Wedding" to listOf("wedding"),
        "Party" to listOf("party", "get-together", "gathering"),
        "Movie" to listOf("movie", "cinema", "film")
    )

    override suspend fun analyze(content: NormalizedContent): List<Evidence> {
        val lower = content.text.lowercase()
        return lexicon.mapNotNull { (activity, cues) ->
            // Longest cue first so "swim lesson" wins over "swim".
            val cue = cues.sortedByDescending { it.length }
                .firstOrNull { Regex("""\b${Regex.escape(it)}""").containsMatchIn(lower) }
                ?: return@mapNotNull null
            Evidence(
                kind = EvidenceKind.ACTIVITY,
                value = activity,
                evidenceText = content.text.sentenceContaining(cue),
                confidence = 0.6f
            )
        }.take(3)
    }
}

// ── Specialist: Objects ───────────────────────────────────────────────

/**
 * Finds concrete things the memory is about — what to buy, bring, or find.
 *
 * Works from a lexicon of head nouns plus up to two words of modifier, so
 * "swimming glasses" and "school uniform" survive as whole phrases rather than
 * collapsing to "glasses" and "uniform". The modifier is what makes an object
 * worth retrieving by later.
 *
 * Note what this cannot do: the user's own example, "swimming glasses", is
 * almost certainly goggles. Rules will faithfully record the words that were
 * said; working out what was *meant* needs a model, and that is exactly the
 * line between this floor and the reasoning capability.
 */
class LocalObjectAnalyzer @Inject constructor() : MemoryAnalyzer {
    override val kinds = setOf(EvidenceKind.OBJECT)

    private val headNouns = listOf(
        "glasses", "goggles", "spectacles", "shoes", "uniform", "kit", "bag", "costume",
        "passport", "visa", "ticket", "tickets", "boarding pass", "licence", "license",
        "gift", "present", "cake", "card", "flowers",
        "medicine", "tablets", "prescription", "report", "reports",
        "keys", "charger", "cable", "laptop", "phone", "camera", "headphones",
        "book", "books", "notebook", "stationery", "supplies",
        "documents", "papers", "form", "forms", "certificate", "invoice", "receipt"
    )

    private val pattern = Regex(
        """\b((?:[a-z]+\s+){0,2}(?:${headNouns.joinToString("|") { Regex.escape(it) }}))\b""",
        RegexOption.IGNORE_CASE
    )

    /** Words that turn up before a noun but are not part of its name. */
    private val modifierStoplist = setOf(
        "the", "a", "an", "my", "his", "her", "their", "our", "your", "its",
        "some", "any", "this", "that", "these", "those", "new", "old",
        "to", "for", "of", "and", "or", "with", "buy", "get", "bring", "take",
        "need", "want", "find", "pick", "up", "collect", "his", "is", "was"
    )

    override suspend fun analyze(content: NormalizedContent): List<Evidence> =
        pattern.findAll(content.text)
            .map { match ->
                match.groupValues[1].trim()
                    .split(Regex("""\s+"""))
                    .dropWhile { it.lowercase() in modifierStoplist }
                    .joinToString(" ")
            }
            .filter { it.isNotBlank() }
            .map { phrase -> phrase.replaceFirstChar { it.uppercase() } }
            .distinctBy { it.lowercase() }
            .take(4)
            .map { phrase ->
                Evidence(
                    kind = EvidenceKind.OBJECT,
                    value = phrase,
                    evidenceText = content.text.sentenceContaining(phrase),
                    confidence = 0.55f
                )
            }
            .toList()
}

// ── Specialist: Topics ────────────────────────────────────────────────

/**
 * Finds recurring subjects a memory discusses, from a lexicon of the themes a
 * personal diary actually returns to.
 *
 * This closes a real gap: TOPIC had no on-device analyzer at all, so a user
 * with no model got no topics — and therefore no auto-Collections and thin
 * Worlds, both of which are built from topic entities.
 */
class LocalTopicAnalyzer @Inject constructor() : MemoryAnalyzer {
    override val kinds = setOf(EvidenceKind.TOPIC)

    private val lexicon = mapOf(
        "Health" to listOf("health", "blood pressure", "sugar level", "diet", "symptoms", "recovery"),
        "Fitness" to listOf("fitness", "weight loss", "training plan", "steps"),
        "Finances" to listOf("budget", "salary", "loan", "emi", "insurance", "investment", "tax", "savings"),
        "Education" to listOf("admission", "syllabus", "homework", "results", "scholarship", "fees"),
        "Career" to listOf("promotion", "appraisal", "resignation", "job offer", "new role"),
        "Home" to listOf("renovation", "repair", "plumber", "electrician", "rent", "landlord", "moving house"),
        "Vehicle" to listOf("car service", "insurance renewal", "puncture", "petrol", "bike service"),
        "Travel plans" to listOf("itinerary", "booking", "hotel", "visa application", "packing"),
        "Relationships" to listOf("argument", "apology", "misunderstanding", "catch up", "reconcile"),
        "Parenting" to listOf("parenting", "school run", "bedtime", "screen time", "milestone")
    )

    override suspend fun analyze(content: NormalizedContent): List<Evidence> {
        val lower = content.text.lowercase()
        return lexicon.mapNotNull { (topic, cues) ->
            val cue = cues.firstOrNull { Regex("""\b${Regex.escape(it)}""").containsMatchIn(lower) }
                ?: return@mapNotNull null
            Evidence(
                kind = EvidenceKind.TOPIC,
                value = topic,
                evidenceText = content.text.sentenceContaining(cue),
                confidence = 0.5f
            )
        }.take(3)
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
 *
 * The anchor decides *when*; the sentence around it is what the user actually
 * gets shown, because a reminder titled "tomorrow" says nothing on its own.
 */
class LocalReminderAnalyzer @Inject constructor() : MemoryAnalyzer {
    override val kinds = setOf(EvidenceKind.REMINDER)

    override suspend fun analyze(content: NormalizedContent): List<Evidence> {
        val hint = DateHintParser.firstHint(content.text, content.capturedAt) ?: return emptyList()
        val sentence = content.text.sentenceContaining(hint.phrase)
        return listOf(
            Evidence(
                kind = EvidenceKind.REMINDER,
                // "Dentist tomorrow at 4pm" is a usable reminder; "tomorrow" is not.
                // Fall back to the phrase only when the sentence can't be recovered.
                value = sentence ?: hint.phrase,
                evidenceText = sentence,
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

    // Values match ExtractorRegistry.MOODS so a memory read by rules and one
    // read by a model land on the same feeling names — otherwise the same mood
    // would split into two entities depending on what was installed that week.
    private val lexicon = mapOf(
        "Happy" to listOf("happy", "glad", "wonderful", "delighted", "joy"),
        "Excited" to listOf("excited", "thrilled", "can't wait", "amazing"),
        "Motivated" to listOf("motivated", "determined", "focused", "inspired", "inspiring"),
        "Grateful" to listOf("grateful", "thankful", "blessed"),
        "Proud" to listOf("proud", "so pleased with", "did really well"),
        "Hopeful" to listOf("hopeful", "fingers crossed", "looking forward"),
        "Calm" to listOf("calm", "peaceful", "relaxed", "at ease"),
        "Reflective" to listOf("thinking about", "looking back", "makes me wonder"),
        "Frustrated" to listOf("frustrated", "annoyed", "angry", "fed up"),
        "Anxious" to listOf("worried", "anxious", "nervous", "stressed"),
        "Sad" to listOf("sad", "upset", "heartbroken", "miss him", "miss her"),
        "Disappointed" to listOf("disappointed", "let down", "gutted"),
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
