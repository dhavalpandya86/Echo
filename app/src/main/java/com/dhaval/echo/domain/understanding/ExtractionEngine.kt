package com.dhaval.echo.domain.understanding

/**
 * Something that can answer an [ExtractorSpec]'s question.
 *
 * Deliberately says nothing about *what* answers it. An engine may be a model
 * the user downloaded, a cloud provider they keyed, or a set of rules that ship
 * in the APK. Extractors ask for a [Capability]; the [ExtractionEngineProvider]
 * decides what serves it today.
 *
 * That indirection is the whole design. The best on-device model changes every
 * few months, and Echo is meant to hold someone's memories for years — so the
 * pipeline names capabilities, never models. Swapping a reasoning model is a
 * change in one provider, not in 22 extractors.
 */
interface ExtractionEngine {

    /** What this engine can serve. */
    val capability: Capability

    /**
     * What actually answered, for the run row: "qwen3-4b-instruct-q4",
     * "heuristic", "cloud:claude". Recorded so a thin result is explainable
     * rather than mysterious — the difference between "the model said nothing"
     * and "no model was installed" matters when a user asks why.
     */
    val engineId: String

    /**
     * Whether this engine can run right now. False when its model is not
     * downloaded, the device is out of memory, or no API key is configured.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Answer the question. Returns the engine's raw response for the caller to
     * parse — engines do not know about [Evidence].
     *
     * Throwing is how an engine reports it could not answer; the runner records
     * the failure against that one extractor and carries on with the rest.
     */
    suspend fun answer(spec: ExtractorSpec, ctx: ExtractionContext): String
}

/**
 * Resolves a capability to whatever serves it on this device, for this user,
 * right now.
 *
 * Resolved fresh per memory rather than cached, so installing a model pack or
 * pasting an API key takes effect on the next memory instead of the next launch.
 */
interface ExtractionEngineProvider {

    /**
     * The best available engine for a capability, or null when nothing can serve
     * it. Null is a normal answer — a phone with no model pack installed simply
     * cannot reason, and the runner degrades to the heuristic floor or records
     * the question as skipped.
     */
    suspend fun engineFor(capability: Capability): ExtractionEngine?

    /** The always-available floor: rules and lexicons, no download required. */
    fun heuristicEngine(): ExtractionEngine?
}
