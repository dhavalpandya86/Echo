package com.dhaval.echo.domain.embeddings

/**
 * Result of an embedding generation request.
 */
data class EmbeddingResult(
    val vector: FloatArray,
    val dimensions: Int,
    val modelVersion: String,
    val inferenceTime: Long,
    val success: Boolean = true,
    val errorMessage: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmbeddingResult
        if (!vector.contentEquals(other.vector)) return false
        if (dimensions != other.dimensions) return false
        if (modelVersion != other.modelVersion) return false
        return true
    }

    override fun hashCode(): Int {
        var result = vector.contentHashCode()
        result = 31 * result + dimensions
        result = 31 * result + modelVersion.hashCode()
        return result
    }
}
