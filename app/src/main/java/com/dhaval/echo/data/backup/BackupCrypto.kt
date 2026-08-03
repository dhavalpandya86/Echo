package com.dhaval.echo.data.backup

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The `.echo` envelope: a plaintext header describing the file, followed by a
 * payload that is either a raw ZIP or that same ZIP sealed chunk by chunk.
 *
 * ```
 * "ECHOBK" | backupFormat | flags | createdAtMillis
 *   [encrypted]  kdfId | iterations | saltLen | salt | noncePrefix | chunkSize
 * payload: raw ZIP  — or —  repeated [frameHeader][ciphertext]
 * ```
 *
 * **Why the header is plaintext.** Someone picking a file needs to be told
 * "this is an encrypted Echo backup from 3 August" *before* being asked for a
 * password — otherwise the only way to identify a file is to successfully
 * decrypt it. Nothing private lives out here: how many memories the archive
 * holds, what device made it and what the user named it are all inside the
 * ciphertext, where a stolen archive can't disclose them.
 *
 * **Why chunked GCM rather than one CipherStream.** GCM authenticates the whole
 * message or nothing, so a single-stream archive can't be verified without
 * buffering all of it — 2.8 GB in memory, which no phone will do. Framing the
 * payload into 1 MiB chunks makes it streamable. The cost is that chunks then
 * become individually forgeable in *arrangement* even though each is
 * authenticated, so the AAD binds each chunk's index and a final-chunk flag:
 * reordering, duplicating, dropping, or truncating the stream all fail
 * authentication rather than silently producing a shorter archive.
 */
object BackupCrypto {

    /** File magic. Six bytes, so a wrong file is rejected before anything else. */
    private val MAGIC = byteArrayOf(0x45, 0x43, 0x48, 0x4F, 0x42, 0x4B) // "ECHOBK"

    /** The container version. Bumping this makes older builds refuse to restore. */
    const val BACKUP_FORMAT = 1

    private const val FLAG_ENCRYPTED = 0x01
    private const val KDF_PBKDF2_HMAC_SHA256 = 1

    /**
     * OWASP's 2023 floor for PBKDF2-HMAC-SHA256. Runs in roughly a second on a
     * mid-range phone — paid once per archive, never per chunk.
     */
    const val PBKDF2_ITERATIONS = 210_000

    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val NONCE_PREFIX_BYTES = 4
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /**
     * 1 MiB of plaintext per chunk: small enough that decryption never holds
     * much, large enough that the 16-byte tag per chunk is noise (0.0015%).
     */
    const val DEFAULT_CHUNK_BYTES = 1 shl 20

    /** High bit of the frame header marks the last chunk. */
    private const val FINAL_FLAG = -0x80000000 // 0x80000000
    private const val LENGTH_MASK = 0x7FFFFFFF

    private val secureRandom by lazy { SecureRandom() }

    // ── Header ───────────────────────────────────────────────────────────────

    /** Key-derivation and framing parameters, stored per archive. */
    data class KdfParams(
        val iterations: Int,
        val salt: ByteArray,
        val noncePrefix: ByteArray,
        val chunkSize: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KdfParams) return false
            return iterations == other.iterations &&
                salt.contentEquals(other.salt) &&
                noncePrefix.contentEquals(other.noncePrefix) &&
                chunkSize == other.chunkSize
        }

        override fun hashCode(): Int {
            var result = iterations
            result = 31 * result + salt.contentHashCode()
            result = 31 * result + noncePrefix.contentHashCode()
            result = 31 * result + chunkSize
            return result
        }
    }

    data class Envelope(
        val backupFormat: Int,
        val encrypted: Boolean,
        val createdAtMillis: Long,
        val kdf: KdfParams?
    )

    fun newKdfParams(chunkSize: Int = DEFAULT_CHUNK_BYTES) = KdfParams(
        iterations = PBKDF2_ITERATIONS,
        salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes),
        noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(secureRandom::nextBytes),
        chunkSize = chunkSize
    )

    fun writeHeader(out: OutputStream, createdAtMillis: Long, kdf: KdfParams?) {
        out.write(MAGIC)
        out.write(BACKUP_FORMAT)
        out.write(if (kdf != null) FLAG_ENCRYPTED else 0)
        out.writeLongBE(createdAtMillis)
        if (kdf != null) {
            out.write(KDF_PBKDF2_HMAC_SHA256)
            out.writeIntBE(kdf.iterations)
            out.write(kdf.salt.size)
            out.write(kdf.salt)
            out.write(kdf.noncePrefix)
            out.writeIntBE(kdf.chunkSize)
        }
    }

    /** Reads the header, leaving [input] positioned at the first payload byte. */
    fun readHeader(input: InputStream): Envelope {
        val magic = input.readExactly(MAGIC.size)
        if (!magic.contentEquals(MAGIC)) {
            throw BackupFormatException("Not an Echo backup file")
        }
        val format = input.readByteOrThrow()
        val flags = input.readByteOrThrow()
        val createdAt = input.readLongBE()
        val encrypted = (flags and FLAG_ENCRYPTED) != 0

        // The format check comes after parsing so a newer archive can still be
        // *described* to the user ("made by a newer Echo") rather than being an
        // opaque failure. Restore is what refuses it; reading is not.
        val kdf = if (!encrypted) null else {
            val kdfId = input.readByteOrThrow()
            if (kdfId != KDF_PBKDF2_HMAC_SHA256) {
                throw BackupFormatException("Unsupported key derivation ($kdfId)")
            }
            val iterations = input.readIntBE()
            val salt = input.readExactly(input.readByteOrThrow())
            val noncePrefix = input.readExactly(NONCE_PREFIX_BYTES)
            KdfParams(iterations, salt, noncePrefix, input.readIntBE())
        }
        return Envelope(format, encrypted, createdAt, kdf)
    }

    // ── Keys ─────────────────────────────────────────────────────────────────

    /**
     * The password is a [CharArray] rather than a String all the way through, so
     * it can be zeroed after use instead of lingering in the string pool until
     * some future GC — a heap dump of a diary app should not yield the key to
     * every backup the user has ever made.
     */
    fun deriveKey(password: CharArray, kdf: KdfParams): SecretKey {
        val spec = PBEKeySpec(password, kdf.salt, kdf.iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2withHmacSHA256")
                .generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    // ── Payload ──────────────────────────────────────────────────────────────

    /**
     * Wraps [sink] so everything written to it is sealed chunk by chunk.
     *
     * Closing the returned stream emits the final chunk and closes [sink]; the
     * final flag is only knowable at close, so nothing may be written after it.
     */
    fun encryptingStream(sink: OutputStream, key: SecretKey, kdf: KdfParams): OutputStream =
        ChunkedGcmOutputStream(sink, key, kdf.noncePrefix, kdf.chunkSize)

    /** Wraps [source] so reads return verified plaintext, or throw. */
    fun decryptingStream(source: InputStream, key: SecretKey, kdf: KdfParams): InputStream =
        ChunkedGcmInputStream(source, key, kdf.noncePrefix)

    private fun nonceFor(prefix: ByteArray, index: Long): ByteArray =
        ByteArray(NONCE_BYTES).also { nonce ->
            prefix.copyInto(nonce, 0, 0, NONCE_PREFIX_BYTES)
            for (i in 0 until 8) {
                nonce[NONCE_PREFIX_BYTES + i] = (index ushr (56 - 8 * i)).toByte()
            }
        }

    /**
     * Binds a chunk to its position and to whether it ends the stream. Without
     * the index, chunks could be reordered or replayed; without the final flag,
     * an attacker (or a half-written file) could truncate the archive and every
     * remaining chunk would still authenticate perfectly.
     */
    private fun aadFor(index: Long, isFinal: Boolean): ByteArray =
        ByteArray(9).also { aad ->
            for (i in 0 until 8) aad[i] = (index ushr (56 - 8 * i)).toByte()
            aad[8] = if (isFinal) 1 else 0
        }

    private class ChunkedGcmOutputStream(
        private val sink: OutputStream,
        private val key: SecretKey,
        private val noncePrefix: ByteArray,
        private val chunkSize: Int
    ) : OutputStream() {

        private val buffer = ByteArray(chunkSize)
        private var bufferLen = 0

        /**
         * A completed chunk held back because we don't yet know whether another
         * one follows. Emitting it requires knowing if it's the last, and the
         * only way to know is to see whether more data arrives — so the stream
         * always runs exactly one chunk behind the writer.
         */
        private var pending: ByteArray? = null
        private var index = 0L
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "Stream is closed" }
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val n = minOf(remaining, chunkSize - bufferLen)
                b.copyInto(buffer, bufferLen, offset, offset + n)
                bufferLen += n
                offset += n
                remaining -= n
                if (bufferLen == chunkSize) {
                    pending?.let { emit(it, it.size, isFinal = false) }
                    pending = buffer.copyOf()
                    bufferLen = 0
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                val held = pending
                when {
                    bufferLen > 0 -> {
                        held?.let { emit(it, it.size, isFinal = false) }
                        emit(buffer, bufferLen, isFinal = true)
                    }
                    held != null -> emit(held, held.size, isFinal = true)
                    // An empty payload still gets a final frame, so that "no
                    // chunks at all" is distinguishable from "truncated to zero".
                    else -> emit(ByteArray(0), 0, isFinal = true)
                }
                sink.flush()
            } finally {
                sink.close()
            }
        }

        private fun emit(data: ByteArray, len: Int, isFinal: Boolean) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonceFor(noncePrefix, index)))
                updateAAD(aadFor(index, isFinal))
            }
            val sealed = cipher.doFinal(data, 0, len)
            sink.writeIntBE(if (isFinal) sealed.size or FINAL_FLAG else sealed.size)
            sink.write(sealed)
            index++
        }
    }

    private class ChunkedGcmInputStream(
        private val source: InputStream,
        private val key: SecretKey,
        private val noncePrefix: ByteArray
    ) : InputStream() {

        private var plain = ByteArray(0)
        private var offset = 0
        private var index = 0L
        private var sawFinal = false

        override fun read(): Int {
            if (!fill()) return -1
            return plain[offset++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, plain.size - offset)
            plain.copyInto(b, off, offset, offset + n)
            offset += n
            return n
        }

        override fun available(): Int = plain.size - offset

        override fun close() = source.close()

        /** True when [plain] holds at least one unread byte. */
        private fun fill(): Boolean {
            while (offset >= plain.size) {
                if (sawFinal) return false
                val header = source.readIntOrNull()
                    // The stream ended without a chunk claiming to be last: the
                    // file was cut short. Every chunk read so far authenticated
                    // fine, which is exactly why this check has to exist.
                    ?: throw BackupTruncatedException()

                val isFinal = (header and FINAL_FLAG) != 0
                val length = header and LENGTH_MASK
                val sealed = try {
                    source.readExactly(length)
                } catch (e: EOFException) {
                    throw BackupTruncatedException()
                }

                plain = try {
                    Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonceFor(noncePrefix, index)))
                        updateAAD(aadFor(index, isFinal))
                        doFinal(sealed)
                    }
                } catch (e: Exception) {
                    throw BackupDecryptionException(index, e)
                }
                offset = 0
                index++
                if (isFinal) sawFinal = true
            }
            return true
        }
    }
}

/** The file isn't an Echo backup, or uses parameters this build doesn't know. */
class BackupFormatException(message: String) : IOException(message)

/** The archive ends before a chunk marked final — it was cut short. */
class BackupTruncatedException : IOException("Backup file is incomplete")

/**
 * A chunk failed authentication.
 *
 * At [chunkIndex] 0 this is almost always the wrong password, since a correct
 * key would have to be paired with damage in the very first megabyte to fail
 * here. Later on it means the archive was altered or corrupted in place. The
 * caller uses that distinction to say something useful rather than "decryption
 * failed".
 */
class BackupDecryptionException(
    val chunkIndex: Long,
    cause: Throwable?
) : IOException("Could not decrypt chunk $chunkIndex", cause) {
    val isProbablyWrongPassword: Boolean get() = chunkIndex == 0L
}

/** Lower-case hex, the form checksums are written and compared in. */
internal fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it) }

// ── Big-endian stream helpers ────────────────────────────────────────────────

private fun OutputStream.writeIntBE(value: Int) {
    write((value ushr 24) and 0xFF); write((value ushr 16) and 0xFF)
    write((value ushr 8) and 0xFF); write(value and 0xFF)
}

private fun OutputStream.writeLongBE(value: Long) {
    for (i in 0 until 8) write(((value ushr (56 - 8 * i)) and 0xFF).toInt())
}

private fun InputStream.readByteOrThrow(): Int =
    read().also { if (it < 0) throw EOFException("Unexpected end of backup file") }

internal fun InputStream.readExactly(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(bytes, read, count - read)
        if (n < 0) throw EOFException("Unexpected end of backup file")
        read += n
    }
    return bytes
}

private fun InputStream.readIntBE(): Int {
    val b = readExactly(4)
    return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
        ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
}

private fun InputStream.readLongBE(): Long {
    val b = readExactly(8)
    var value = 0L
    for (byte in b) value = (value shl 8) or (byte.toLong() and 0xFF)
    return value
}

/** Null at a clean end-of-stream; throws only on a partial read. */
private fun InputStream.readIntOrNull(): Int? {
    val first = read()
    if (first < 0) return null
    val rest = readExactly(3)
    return ((first and 0xFF) shl 24) or ((rest[0].toInt() and 0xFF) shl 16) or
        ((rest[1].toInt() and 0xFF) shl 8) or (rest[2].toInt() and 0xFF)
}
