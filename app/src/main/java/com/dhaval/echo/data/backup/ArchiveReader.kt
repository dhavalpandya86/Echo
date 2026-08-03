package com.dhaval.echo.data.backup

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Reads a `.echo` archive forwards, one entry at a time.
 *
 * Strictly sequential because that is all a ZIP inside an encrypted, chunked
 * stream allows — there is no central directory to seek to without decrypting
 * everything first. In practice this costs nothing: the manifest is the first
 * entry, so the two things callers actually want (describe an archive; unpack
 * an archive) are both forward passes.
 */
class ArchiveReader private constructor(
    val envelope: BackupCrypto.Envelope,
    private val zip: ZipInputStream
) : Closeable {

    /** Advances to the next entry, or null at the end of the archive. */
    fun nextEntry(): String? = zip.nextEntry?.name

    /** The current entry in full. Only for small entries — manifest, checksums. */
    fun readEntryBytes(): ByteArray = zip.readBytes()

    /**
     * Streams the current entry out, hashing as it goes.
     *
     * The hash is produced here rather than by re-reading the extracted file,
     * so verification costs nothing beyond the copy that was happening anyway.
     *
     * @return bytes written, and their SHA-256 as lower-case hex.
     */
    fun copyEntryTo(out: OutputStream, onBytes: (Long) -> Unit = {}): Pair<Long, String> {
        val digest = MessageDigest.getInstance(DIGEST)
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            out.write(buffer, 0, read)
            total += read
            onBytes(read.toLong())
        }
        return total to digest.digest().toHexString()
    }

    /** Hashes the current entry without keeping it — used by Verify. */
    fun digestEntry(onBytes: (Long) -> Unit = {}): Pair<Long, String> =
        copyEntryTo(NullOutputStream, onBytes)

    fun readManifest(): BackupManifest =
        BackupManifest.json.decodeFromString(String(readEntryBytes()))

    fun readChecksums(): BackupChecksums =
        BackupManifest.json.decodeFromString(String(readEntryBytes()))

    override fun close() {
        runCatching { zip.close() }
    }

    companion object {
        private const val DIGEST = "SHA-256"
        private const val BUFFER_BYTES = 64 * 1024

        /**
         * @param password required when the archive is encrypted; deriving the
         *   key takes about a second by design, so callers should open once and
         *   read what they need rather than reopening per entry.
         * @throws BackupPasswordRequiredException when the archive is encrypted
         *   and no password was supplied — a distinct signal from a *wrong*
         *   password, because the UI response differs: ask, versus apologise.
         */
        fun open(source: InputStream, password: CharArray?): ArchiveReader {
            val buffered = BufferedInputStream(source, BUFFER_BYTES)
            val envelope = BackupCrypto.readHeader(buffered)

            val payload: InputStream = if (envelope.encrypted) {
                val kdf = envelope.kdf ?: throw BackupFormatException("Encrypted backup has no key parameters")
                if (password == null) throw BackupPasswordRequiredException()
                BackupCrypto.decryptingStream(buffered, BackupCrypto.deriveKey(password, kdf), kdf)
            } else {
                buffered
            }

            return ArchiveReader(envelope, ZipInputStream(BufferedInputStream(payload, BUFFER_BYTES)))
        }

        /**
         * Reads only the plaintext envelope, without touching the payload.
         *
         * This is what lets the history list and the file picker say "encrypted
         * Echo backup from 3 August, 2.8 GB" for every archive in a folder
         * without prompting for a single password.
         */
        fun readHeaderOnly(source: InputStream): BackupCrypto.Envelope =
            source.use { BackupCrypto.readHeader(BufferedInputStream(it, HEADER_BUFFER_BYTES)) }

        private const val HEADER_BUFFER_BYTES = 256
    }
}

/** The archive is encrypted and no password was given. Ask for one. */
class BackupPasswordRequiredException : Exception("This backup is encrypted")

/** Discards everything, so an entry can be hashed without being extracted. */
private object NullOutputStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}
