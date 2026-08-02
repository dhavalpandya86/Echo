package com.dhaval.echo.data.understanding

import android.util.Log
import com.dhaval.echo.domain.ai.AIManager
import com.dhaval.echo.domain.understanding.Capability
import com.dhaval.echo.domain.understanding.ExtractionContext
import com.dhaval.echo.domain.understanding.ExtractionEngine
import com.dhaval.echo.domain.understanding.ExtractionEngineProvider
import com.dhaval.echo.domain.understanding.ExtractorSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serves a capability from the user's configured cloud provider.
 *
 * One call per question, which is the same shape the on-device path uses — so
 * the two stay directly comparable, and a user who moves between them sees the
 * same board rather than a different pipeline.
 */
class CloudExtractionEngine(
    override val capability: Capability,
    private val providerId: String,
    private val complete: suspend (prompt: String, maxTokens: Int) -> String
) : ExtractionEngine {

    override val engineId: String = "cloud:$providerId"

    override suspend fun isAvailable(): Boolean = true

    override suspend fun answer(spec: ExtractorSpec, ctx: ExtractionContext): String =
        complete(ExtractorPrompt.build(spec, ctx), maxTokensFor(spec))

    /**
     * Narrow questions need narrow answers. Capping tightly keeps a model from
     * padding a one-word facet into a paragraph, and keeps cost proportional to
     * the question rather than to the memory.
     */
    private fun maxTokensFor(spec: ExtractorSpec): Int = when (spec.outputShape) {
        com.dhaval.echo.domain.understanding.OutputShape.SINGLE_CHOICE -> 128
        com.dhaval.echo.domain.understanding.OutputShape.TEXT -> 512
        else -> 768
    }
}

/**
 * Decides what serves each capability on this device, right now.
 *
 * Resolution order per capability:
 *  1. An on-device model the user installed — private, offline, free.
 *  2. Their configured cloud provider, if they keyed one.
 *  3. Nothing, and the caller degrades to the heuristic floor.
 *
 * On-device is preferred over cloud deliberately: a diary is the most private
 * thing on someone's phone, so if the device can answer the question it should,
 * and the network should not see the memory at all.
 *
 * No model name appears here. On-device engines register themselves through
 * [onDeviceEngines], so adding or replacing a model is a change in one binding
 * rather than in the pipeline.
 */
@Singleton
class RealExtractionEngineProvider @Inject constructor(
    private val aiManager: AIManager,
    private val onDeviceEngines: Set<@JvmSuppressWildcards ExtractionEngine>
) : ExtractionEngineProvider {

    override suspend fun engineFor(capability: Capability): ExtractionEngine? {
        onDeviceEngines
            .filter { it.capability == capability }
            .forEach { engine ->
                val available = runCatching { engine.isAvailable() }
                    .onFailure { Log.w(TAG, "${engine.engineId} availability check failed", it) }
                    .getOrDefault(false)
                if (available) return engine
            }

        return cloudEngine(capability)
    }

    override fun heuristicEngine(): ExtractionEngine? = null

    /**
     * The cloud fallback. Classification is served by the same text model as
     * reasoning — a remote provider has no separate classifier, and a
     * single-choice question is just a very constrained completion.
     */
    private fun cloudEngine(capability: Capability): ExtractionEngine? {
        if (capability != Capability.REASONING && capability != Capability.CLASSIFICATION) return null
        val completer = aiManager.getExtractionCompleter() ?: return null
        val providerId = aiManager.currentProvider.value.id

        return CloudExtractionEngine(capability, providerId) { prompt, maxTokens ->
            completer.complete(prompt, maxTokens)
        }
    }

    private companion object { const val TAG = "EngineProvider" }
}
