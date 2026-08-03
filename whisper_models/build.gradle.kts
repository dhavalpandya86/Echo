plugins {
    alias(libs.plugins.android.asset.pack)
}

/**
 * Whisper-base int8 (encoder + both decoders) plus its mel filters and vocab —
 * ~173 MB, used to transcribe voice memos entirely on-device.
 *
 * install-time: transcription is core to capture, so the model must be present
 * the first time someone records. Delivered this way the files are reachable
 * through the ordinary AssetManager, so WhisperTranscriptionEngine keeps opening
 * "whisper/..." exactly as before.
 */
assetPack {
    packName.set("whisper_models")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
