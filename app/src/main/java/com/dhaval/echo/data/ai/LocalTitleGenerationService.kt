package com.dhaval.echo.data.ai

import com.dhaval.echo.domain.ai.TitleGenerationService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * On-device title, derived from the memory's own words — no cloud needed. Names
 * the entry after its opening thought (first sentence, trimmed to a few words)
 * so a memory is never a generic "Memory of the Day". For voice memories the
 * input is the Whisper transcript, so the name comes straight from what was
 * said. When a Claude key is present, the richer ClaudeTitleGenerationService is
 * used instead; this is the honest offline fallback.
 */
class LocalTitleGenerationService : TitleGenerationService {

    override fun generateTitle(text: String): Flow<String> = flow {
        emit(deriveTitle(text))
    }

    private fun deriveTitle(text: String): String {
        val clean = text.trim().replace(Regex("""\s+"""), " ")
        if (clean.isBlank()) return "Untitled"

        // Prefer the first sentence; fall back to the whole (short) text.
        val firstSentence = clean.split(Regex("""(?<=[.!?])\s""")).firstOrNull()?.trim().orEmpty()
            .ifBlank { clean }

        val words = firstSentence.split(" ")
        val short = if (words.size <= MAX_WORDS) firstSentence else words.take(MAX_WORDS).joinToString(" ")

        return short.trim()
            .trimEnd('.', ',', '!', '?', ';', ':', '-', '"', '\'')
            .take(MAX_CHARS)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .ifBlank { "Untitled" }
    }

    private companion object {
        const val MAX_WORDS = 8
        const val MAX_CHARS = 60
    }
}
