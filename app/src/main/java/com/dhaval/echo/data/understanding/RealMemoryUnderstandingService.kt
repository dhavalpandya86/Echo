package com.dhaval.echo.data.understanding

import android.util.Log
import com.dhaval.echo.domain.understanding.EntityResolver
import com.dhaval.echo.domain.understanding.Evidence
import com.dhaval.echo.domain.understanding.MemoryAnalyzer
import com.dhaval.echo.domain.understanding.MemoryUnderstandingService
import com.dhaval.echo.domain.understanding.NormalizedContent
import javax.inject.Inject

/**
 * Stage 3→5 orchestrator: run every analyzer, collect the evidence board,
 * hand it to the resolver.
 *
 * Analyzer independence is enforced here: each runs in its own try/catch, so
 * one specialist failing loudly never silences the others — the standing
 * no-silent-failure rule.
 */
class RealMemoryUnderstandingService @Inject constructor(
    private val analyzers: Set<@JvmSuppressWildcards MemoryAnalyzer>,
    private val resolver: EntityResolver
) : MemoryUnderstandingService {

    override suspend fun understand(content: NormalizedContent) {
        if (content.text.isBlank()) {
            Log.d(TAG, "No text for memory ${content.memoryId}; nothing to analyze")
            return
        }

        val board = mutableListOf<Evidence>()
        for (analyzer in analyzers) {
            runCatching { analyzer.analyze(content) }
                .onSuccess { board += it }
                .onFailure {
                    Log.e(TAG, "${analyzer::class.simpleName} failed for ${content.memoryId}", it)
                }
        }

        Log.d(TAG, "Evidence board for ${content.memoryId}: " +
            board.groupingBy { it.kind }.eachCount().toString())

        resolver.resolve(content, board)
    }

    private companion object {
        const val TAG = "MemoryUnderstanding"
    }
}
