package com.dhaval.echo.domain.understanding

import java.time.LocalDateTime

/**
 * The Memory Understanding Engine's core contracts.
 *
 * Pipeline (all internal — the user only adds a memory and sees output):
 *
 *   capture → NORMALIZE (per-source extractors) → [NormalizedContent]
 *           → ANALYZE  (independent [MemoryAnalyzer]s) → [Evidence] board
 *           → RESOLVE  ([EntityResolver]: evidence → entity graph links)
 *           → EXPAND   (graph traversal; inferred links — MU-4)
 *
 * Analyzers are independent by contract: none reads another's output, and a
 * failure in one never blocks the rest. Every conclusion carries evidence text
 * and a confidence — nothing is "magic".
 */

/** How a memory was captured. One memory may have several. */
enum class SourceKind { VOICE, TEXT, PHOTO, VIDEO }

/**
 * Stage-2 canonical representation. After this point the pipeline no longer
 * cares whether content came from voice, text, photo, or video.
 */
data class NormalizedContent(
    val memoryId: String,
    val userId: String,
    /** Merged text: transcript + written content (+ OCR in MU-3). */
    val text: String,
    val sourceKinds: Set<SourceKind>,
    val capturedAt: LocalDateTime,
    /** Image labels / scene objects (MU-3+). */
    val visualObjects: List<String> = emptyList()
)

/** What kind of fact a piece of evidence claims. */
enum class EvidenceKind {
    PERSON, PROJECT, TOPIC, PLACE, ORG, PRODUCT,   // entity-forming → resolver
    TASK, REMINDER, MOOD, DECISION                 // per-memory claims → evidence board
}

/**
 * One fact extracted from a memory, with its supporting quote and confidence.
 */
data class Evidence(
    val kind: EvidenceKind,
    /** The fact itself: "Raj", "Oceanis", "Call Raj about the logo". */
    val value: String,
    /** The text span that supports this conclusion, when available. */
    val evidenceText: String?,
    /** 0..1. Heuristic analyzers must stay conservative here. */
    val confidence: Float,
    /** Resolved due date for TASK/REMINDER, epoch millis. */
    val dueAtMillis: Long? = null
)

/**
 * One specialist at the table. Knows nothing about the other analyzers.
 */
interface MemoryAnalyzer {
    /** What this analyzer can claim. Used for logging and routing. */
    val kinds: Set<EvidenceKind>

    /** Extract evidence. Must not throw for ordinary content; return empty instead. */
    suspend fun analyze(content: NormalizedContent): List<Evidence>
}

/**
 * Stage 5: turns entity-forming evidence into graph nodes + typed links,
 * matching against entities the graph already knows (identity, not tagging).
 */
interface EntityResolver {
    /**
     * Persists the evidence board for a memory: entity evidence becomes
     * entities + memory→entity links (match-or-create, never duplicate);
     * claim evidence becomes extracted items. Idempotent per memory —
     * re-processing replaces that memory's previous conclusions.
     */
    suspend fun resolve(content: NormalizedContent, evidence: List<Evidence>)
}

/**
 * Orchestrates ANALYZE + RESOLVE for one memory. The worker calls only this.
 */
interface MemoryUnderstandingService {
    suspend fun understand(content: NormalizedContent)
}
