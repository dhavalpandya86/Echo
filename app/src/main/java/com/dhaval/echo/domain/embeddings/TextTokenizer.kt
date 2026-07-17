package com.dhaval.echo.domain.embeddings

/**
 * Turns text into the token ids multilingual-e5-small expects.
 *
 * The model is an XLM-RoBERTa SentencePiece (Unigram) tokenizer over a 250k
 * vocabulary; its assets ship in `assets/embeddings/` (tokenizer.json,
 * sentencepiece.bpe.model, special_tokens_map.json, tokenizer_config.json).
 *
 * This is an interface rather than a function on the engine because the
 * implementation is a dependency decision (see AI-LOCAL-03): the XLM-R
 * normalizer relies on a `precompiled_charsmap` blob that is impractical to
 * reimplement by hand, so a real implementation needs either a tokenizer
 * library or a tokenizer baked into an ONNX graph via onnxruntime-extensions.
 */
interface TextTokenizer {

    /**
     * @param text Text to encode, already carrying its e5 prefix.
     * @param maxTokens Truncation limit (the model's max_position_embeddings).
     * @throws TokenizerUnavailableException if no real tokenizer is wired up.
     */
    fun encode(text: String, maxTokens: Int): Encoding

    /**
     * @property ids Token ids, including the model's special tokens.
     * @property attentionMask 1 for real tokens, 0 for padding — parallel to [ids].
     */
    data class Encoding(
        val ids: LongArray,
        val attentionMask: LongArray
    ) {
        init {
            require(ids.size == attentionMask.size) {
                "ids (${ids.size}) and attentionMask (${attentionMask.size}) must align"
            }
        }

        // Arrays need structural equals/hashCode to behave in a data class.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Encoding) return false
            return ids.contentEquals(other.ids) &&
                attentionMask.contentEquals(other.attentionMask)
        }

        override fun hashCode(): Int =
            31 * ids.contentHashCode() + attentionMask.contentHashCode()
    }
}

/**
 * Thrown when embeddings are requested before a real tokenizer exists.
 *
 * Deliberately loud. The previous placeholder hashed whitespace-split words
 * into arbitrary ids, which a real model happily turns into a finite,
 * well-shaped, and completely meaningless 384-d vector — semantic search would
 * appear to work while returning noise. Failing is recoverable; silently
 * storing junk embeddings is not.
 */
class TokenizerUnavailableException(message: String) : IllegalStateException(message)
