package com.dhaval.echo.domain.ai

/**
 * What the user sees when they look at one of Echo's "brains".
 *
 * Three deliberate rules, which together are why this type exists rather than
 * the settings screen reading providers directly:
 *
 *  - **No model names in the contract.** The UI asks what the brain *is*, and
 *    gets back a display name. Whether that resolves to a bundled model, a
 *    downloaded one, or a cloud provider is the capability layer's business.
 *  - **No implementation vocabulary.** "Echo Core", not "on-device heuristics".
 *    A user asking "is Echo intelligent" is not helped by the word "rules", and
 *    "fallback" reads as broken when it is simply the free tier working.
 *  - **Never a hardcoded value.** Every field is derived from live state, so a
 *    key entered or a model installed is reflected without a UI change.
 */
data class BrainStatus(
    /** Which faculty this is: "Memory Brain", "Voice Brain". */
    val role: String,
    /** What is answering, in the user's language: "Echo Core", "Whisper", "Claude". */
    val engineName: String,
    /** Where it runs: "Built into Echo", "On this device", "Cloud". */
    val location: String,
    /** Size on disk, when there is one to report. */
    val sizeLabel: String? = null,
    /**
     * One line of context, shown under the row. Used to point at the upgrade
     * path without making the current state sound broken.
     */
    val detail: String? = null,
    /** True when this brain runs without a network. */
    val isOffline: Boolean = true
) {
    /** "Built into Echo · 2.1 GB" — the subtitle line. */
    val subtitle: String
        get() = listOfNotNull(location, sizeLabel).joinToString(" · ")
}
