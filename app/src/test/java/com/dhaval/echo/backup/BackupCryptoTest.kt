package com.dhaval.echo.backup

import com.dhaval.echo.data.backup.BackupCrypto
import com.dhaval.echo.data.backup.BackupDecryptionException
import com.dhaval.echo.data.backup.BackupTruncatedException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * The archive format is the one thing here that must never be wrong: a bug in
 * chunk framing doesn't fail loudly at backup time, it fails months later when
 * someone tries to get their diary back.
 *
 * A deliberately tiny chunk size exercises the multi-chunk paths — the pending
 * chunk held back to discover whether it's last, the boundary where a payload
 * is an exact multiple of the chunk — without moving megabytes per test. Low
 * PBKDF2 iterations keep it fast; the real value is a constant, not a variable.
 */
class BackupCryptoTest {

    private val password = "correct horse battery staple".toCharArray()
    private val kdf = BackupCrypto.newKdfParams(chunkSize = CHUNK).copy(iterations = 1_000)

    // ── Round trips ──────────────────────────────────────────────────────────

    @Test
    fun `unencrypted archive round-trips and reports no key parameters`() {
        val payload = Random(1).nextBytes(5_000)
        val file = ByteArrayOutputStream().also { out ->
            BackupCrypto.writeHeader(out, CREATED_AT, kdf = null)
            out.write(payload)
        }.toByteArray()

        val input = ByteArrayInputStream(file)
        val envelope = BackupCrypto.readHeader(input)

        assertEquals(BackupCrypto.BACKUP_FORMAT, envelope.backupFormat)
        assertEquals(CREATED_AT, envelope.createdAtMillis)
        assertTrue(!envelope.encrypted)
        assertNull(envelope.kdf)
        assertArrayEquals(payload, input.readBytes())
    }

    /**
     * The sizes that matter are the ones around a chunk boundary: an empty
     * payload still needs a final frame, and a payload that is an exact multiple
     * of the chunk size is where an off-by-one turns the last chunk into a
     * missing one.
     */
    @Test
    fun `encrypted archive round-trips at every chunk boundary`() {
        val sizes = listOf(0, 1, CHUNK - 1, CHUNK, CHUNK + 1, CHUNK * 3, CHUNK * 3 + 7)
        for (size in sizes) {
            val payload = Random(size).nextBytes(size)
            val decrypted = decrypt(encrypt(payload), password)
            assertArrayEquals("payload of $size bytes did not survive", payload, decrypted)
        }
    }

    @Test
    fun `header of an encrypted archive is readable without the password`() {
        val file = encrypt(Random(2).nextBytes(4_096))
        val envelope = BackupCrypto.readHeader(ByteArrayInputStream(file))

        assertTrue(envelope.encrypted)
        assertEquals(CREATED_AT, envelope.createdAtMillis)
        assertEquals(kdf.iterations, envelope.kdf!!.iterations)
        assertArrayEquals(kdf.salt, envelope.kdf!!.salt)
    }

    // ── Failure modes ────────────────────────────────────────────────────────

    @Test
    fun `wrong password fails on the first chunk and says so`() {
        val file = encrypt(Random(3).nextBytes(CHUNK * 2))

        val error = runCatching { decrypt(file, "wrong password".toCharArray()) }
            .exceptionOrNull() as? BackupDecryptionException

        assertTrue("expected a decryption failure", error != null)
        assertEquals(0L, error!!.chunkIndex)
        assertTrue("a first-chunk failure should read as a wrong password", error.isProbablyWrongPassword)
    }

    /**
     * The case chunked AEAD exists to catch. Every surviving chunk still
     * authenticates perfectly, so without the final-chunk flag in the AAD a
     * truncated archive would decrypt cleanly into a silently shorter backup.
     */
    @Test
    fun `truncated archive is rejected rather than restored short`() {
        val file = encrypt(Random(4).nextBytes(CHUNK * 4))
        val truncated = file.copyOf(file.size - CHUNK / 2)

        val error = runCatching { decrypt(truncated, password) }.exceptionOrNull()

        assertTrue(
            "expected truncation to be detected, got $error",
            error is BackupTruncatedException || error is BackupDecryptionException
        )
    }

    @Test
    fun `a single flipped byte is detected`() {
        val file = encrypt(Random(5).nextBytes(CHUNK * 2))
        val corrupted = file.copyOf().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() }

        val error = runCatching { decrypt(corrupted, password) }.exceptionOrNull()

        assertTrue("expected corruption to be caught, got $error", error is BackupDecryptionException)
    }

    /**
     * Each chunk is sealed with its own index, so a chunk lifted from one
     * position and replayed at another fails even though it is a genuine,
     * correctly-encrypted chunk of this very archive.
     */
    @Test
    fun `a replayed chunk does not authenticate at the wrong position`() {
        val payload = Random(6).nextBytes(CHUNK * 3)
        val file = encrypt(payload)

        // Frames follow the header; every non-final frame is 4 + CHUNK + 16 bytes.
        val headerSize = file.size - frameBytes(payload.size)
        val frameSize = 4 + CHUNK + GCM_TAG
        val duplicated = file.copyOf().also { bytes ->
            val first = headerSize
            val second = headerSize + frameSize
            System.arraycopy(bytes, first, bytes, second, frameSize)
        }

        val error = runCatching { decrypt(duplicated, password) }.exceptionOrNull()

        assertTrue("expected a replayed chunk to be rejected, got $error", error is BackupDecryptionException)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun encrypt(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.writeHeader(out, CREATED_AT, kdf)
        val key = BackupCrypto.deriveKey(password, kdf)
        BackupCrypto.encryptingStream(out, key, kdf).use { it.write(payload) }
        return out.toByteArray()
    }

    private fun decrypt(file: ByteArray, withPassword: CharArray): ByteArray {
        val input = ByteArrayInputStream(file)
        val envelope = BackupCrypto.readHeader(input)
        val key = BackupCrypto.deriveKey(withPassword, envelope.kdf!!)
        return BackupCrypto.decryptingStream(input, key, envelope.kdf!!).use { it.readBytes() }
    }

    /** Total framed size of a payload, for locating the first frame. */
    private fun frameBytes(payloadSize: Int): Int {
        if (payloadSize == 0) return 4 + GCM_TAG
        val whole = payloadSize / CHUNK
        val remainder = payloadSize % CHUNK
        val frames = whole + if (remainder > 0) 1 else 0
        return frames * (4 + GCM_TAG) + payloadSize
    }

    private companion object {
        const val CHUNK = 64
        const val GCM_TAG = 16
        const val CREATED_AT = 1_754_236_800_000L
    }
}
