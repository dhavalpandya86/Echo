plugins {
    alias(libs.plugins.android.asset.pack)
}

/**
 * multilingual-e5-small (int8) and the ONNX tokenizer that feeds it — ~118 MB,
 * the semantic backbone of Remember and the entity graph.
 *
 * install-time for the same reason as the Whisper pack: every saved memory is
 * embedded straight away by EmbeddingWorker, so the model cannot be optional.
 * Asset paths stay "embeddings/...", unchanged for OnDeviceEmbeddingEngine and
 * OnnxTextTokenizer.
 */
assetPack {
    packName.set("embedding_models")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
