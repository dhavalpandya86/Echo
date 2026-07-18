package com.dhaval.echo.transcription

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhaval.echo.data.transcription.whisper.WhisperConfig
import com.dhaval.echo.data.transcription.whisper.WhisperMel
import com.dhaval.echo.data.transcription.whisper.WhisperTranscriptionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * MU-2 on-device verification.
 *
 *  - The Kotlin log-mel must match the Python WhisperFeatureExtractor that the
 *    ONNX models were built against (bit-close, not exact — naive DFT vs FFT).
 *  - The full int8 chain must load under the app's ORT 1.20 and transcribe a
 *    known clip — this is also the real device-compat check for the models.
 *
 * References (ref_pcm16k.f32, ref_input_features.f32, ref_transcript.txt) are
 * dumped from the exact Python pipeline and shipped as androidTest assets.
 */
@RunWith(AndroidJUnit4::class)
class WhisperEngineTest {

    private val appCtx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context

    private fun readF32(assetCtx: android.content.Context, path: String): FloatArray {
        val bytes = assetCtx.assets.open(path).use { it.readBytes() }
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    @Test
    fun log_mel_matches_python_reference() {
        val cfg = WhisperConfig.load(appCtx)
        val mel = WhisperMel.load(appCtx, cfg)
        val pcm = readF32(testCtx, "whisper/ref_pcm16k.f32")
        val ref = readF32(testCtx, "whisper/ref_input_features.f32")   // 80*3000

        val got = mel.logMel(pcm)
        assertTrue("mel size", got.size == ref.size)

        var maxDiff = 0f; var sum = 0f
        for (i in ref.indices) {
            val d = abs(got[i] - ref[i]); if (d > maxDiff) maxDiff = d; sum += d
        }
        val meanDiff = sum / ref.size
        android.util.Log.i("WhisperEngineTest", "mel maxDiff=$maxDiff meanDiff=$meanDiff")
        assertTrue("mel mean abs diff too high: $meanDiff", meanDiff < 1e-2f)
        assertTrue("mel max abs diff too high: $maxDiff", maxDiff < 0.1f)
    }

    @Test
    fun transcribes_real_m4a_via_full_path() = runBlocking {
        // Encode the reference PCM to AAC/m4a with the device's own codec — the
        // same container the app's recorder produces — then run the public
        // transcribe(path), exercising WhisperAudio (MediaExtractor+MediaCodec).
        val pcm = readF32(testCtx, "whisper/ref_pcm16k.f32")
        val m4a = java.io.File(appCtx.cacheDir, "whisper_test_${System.nanoTime()}.m4a")
        encodePcmToAac(pcm, m4a)

        val engine = WhisperTranscriptionEngine(appCtx)
        val result = engine.transcribe(m4a.absolutePath, null)
        m4a.delete()
        android.util.Log.i("WhisperEngineTest", "m4a transcript='${result.transcript}' ok=${result.success}")

        assertTrue("transcription failed: ${result.errorMessage}", result.success)
        val t = result.transcript.lowercase()
        assertTrue("expected 'quick brown fox' in: $t", t.contains("quick brown fox"))
        assertTrue("expected 'raj' in: $t", t.contains("raj"))
    }

    /** Minimal AAC-in-mp4 encoder for the round-trip test. */
    private fun encodePcmToAac(pcm: FloatArray, out: java.io.File) {
        val sr = 16000
        val format = android.media.MediaFormat.createAudioFormat(
            android.media.MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1
        ).apply {
            setInteger(android.media.MediaFormat.KEY_AAC_PROFILE,
                android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 64000)
        }
        val codec = android.media.MediaCodec.createEncoderByType(
            android.media.MediaFormat.MIMETYPE_AUDIO_AAC
        )
        codec.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = android.media.MediaMuxer(out.absolutePath,
            android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1; var muxerStarted = false

        // interleaved 16-bit LE PCM bytes
        val pcmBytes = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (f in pcm) {
            val s = (f.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            pcmBytes.putShort(s)
        }
        pcmBytes.flip()

        val info = android.media.MediaCodec.BufferInfo()
        var inputDone = false
        var presentationUs = 0L
        while (true) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    if (pcmBytes.remaining() > 0) {
                        val buf = codec.getInputBuffer(inIdx)!!; buf.clear()
                        val chunk = minOf(buf.capacity(), pcmBytes.remaining())
                        val slice = ByteArray(chunk); pcmBytes.get(slice); buf.put(slice)
                        codec.queueInputBuffer(inIdx, 0, chunk, presentationUs, 0)
                        presentationUs += (chunk / 2) * 1_000_000L / sr
                    } else {
                        codec.queueInputBuffer(inIdx, 0, 0, presentationUs,
                            android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true
            } else if (outIdx >= 0) {
                val encoded = codec.getOutputBuffer(outIdx)!!
                if (info.size > 0 && muxerStarted &&
                    info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    encoded.position(info.offset); encoded.limit(info.offset + info.size)
                    muxer.writeSampleData(trackIndex, encoded, info)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop(); codec.release(); muxer.stop(); muxer.release()
    }

    @Test
    fun transcribes_known_clip_on_device() = runBlocking {
        val engine = WhisperTranscriptionEngine(appCtx)
        val pcm = readF32(testCtx, "whisper/ref_pcm16k.f32")

        val result = engine.transcribeSamples(pcm)
        android.util.Log.i("WhisperEngineTest", "transcript='${result.transcript}' ok=${result.success} ms=${result.processingTime}")

        assertTrue("transcription failed: ${result.errorMessage}", result.success)
        val t = result.transcript.lowercase()
        assertTrue("expected 'quick brown fox' in: $t", t.contains("quick brown fox"))
        assertTrue("expected 'raj' in: $t", t.contains("raj"))
        assertTrue("expected 'lazy dog' in: $t", t.contains("lazy dog"))
    }
}
