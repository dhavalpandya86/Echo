package com.dhaval.echo.data.transcription.whisper

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an encoded audio file (the app records AAC/m4a) into mono 16 kHz
 * float PCM in [-1, 1] — the input Whisper's front end expects. Uses
 * MediaExtractor + MediaCodec so it works on any codec the device supports,
 * then downmixes to mono and linearly resamples to 16 kHz.
 */
object WhisperAudio {

    private const val TARGET_SR = 16000

    fun decodeToMono16k(path: String): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); return FloatArray(0) }

        val format = extractor.getTrackFormat(trackIndex)
        extractor.selectTrack(trackIndex)
        val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcm = ArrayList<Short>(srcSampleRate * 4)
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outIndex >= 0) {
                if (bufferInfo.size > 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (shorts.hasRemaining()) pcm.add(shorts.get())
                }
                codec.releaseOutputBuffer(outIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
            }
        }
        codec.stop(); codec.release(); extractor.release()

        return resampleAndDownmix(pcm, channels, srcSampleRate)
    }

    private fun resampleAndDownmix(pcm: List<Short>, channels: Int, srcSr: Int): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)
        // interleaved shorts → mono float
        val frames = pcm.size / channels
        val mono = FloatArray(frames)
        var idx = 0
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) acc += pcm[idx++] / 32768f
            mono[f] = acc / channels
        }
        if (srcSr == TARGET_SR) return mono

        // linear resample to 16 kHz
        val outLen = ((mono.size.toLong() * TARGET_SR) / srcSr).toInt()
        val out = FloatArray(outLen)
        val ratio = srcSr.toDouble() / TARGET_SR
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt()
            val i1 = minOf(i0 + 1, mono.size - 1)
            val frac = (srcPos - i0).toFloat()
            out[i] = mono[i0] * (1 - frac) + mono[i1] * frac
        }
        return out
    }
}
