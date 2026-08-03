package com.dhaval.echo.data.backup

import com.dhaval.echo.data.backup.BackupManifest.Companion.ENTRY_CHECKSUMS
import com.dhaval.echo.data.backup.BackupManifest.Companion.ENTRY_MANIFEST
import kotlinx.serialization.encodeToString
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey

/**
 * Builds a `.echo` archive as a single forward pass over the source files.
 *
 * Layout is dictated by what has to be cheap later:
 *
 *  - `manifest.json` goes **first**, so describing an archive costs one entry
 *    rather than a full read. That is what lets the history screen list
 *    hundreds of backups, and the restore preview show counts, without
 *    unpacking gigabytes.
 *  - `checksums.json` goes **last**, because the hashes are produced by the act
 *    of writing. Computing them up front would mean reading every file twice at
 *    backup time, on top of the read-back verification that already follows.
 */
class ArchiveWriter private constructor(
    private val zip: ZipOutputStream
) : Closeable {

    private val checksums = LinkedHashMap<String, String>()
    private var finished = false

    /** Entry path to SHA-256, as accumulated so far. */
    val writtenChecksums: Map<String, String> get() = checksums

    fun putManifest(manifest: BackupManifest) {
        putBytes(ENTRY_MANIFEST, BackupManifest.json.encodeToString(manifest).toByteArray())
    }

    fun putBytes(
        entryPath: String,
        bytes: ByteArray,
        level: Int = Deflater.DEFAULT_COMPRESSION
    ) {
        zip.setLevel(level)
        zip.putNextEntry(ZipEntry(entryPath))
        zip.write(bytes)
        zip.closeEntry()
        checksums[entryPath] = MessageDigest.getInstance(DIGEST).digest(bytes).toHexString()
    }

    /**
     * @param level [Deflater.NO_COMPRESSION] for media. Recordings, photos and
     *   video are already compressed formats; deflating them again spends CPU
     *   and battery to grow the file by a fraction of a percent.
     * @param onBytes called with the delta after each block, for progress.
     * @return the number of source bytes written.
     */
    fun putFile(
        entryPath: String,
        file: File,
        level: Int = Deflater.NO_COMPRESSION,
        onBytes: (Long) -> Unit = {}
    ): Long {
        zip.setLevel(level)
        zip.putNextEntry(ZipEntry(entryPath))

        val digest = MessageDigest.getInstance(DIGEST)
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                zip.write(buffer, 0, read)
                total += read
                onBytes(read.toLong())
            }
        }
        zip.closeEntry()
        checksums[entryPath] = digest.digest().toHexString()
        return total
    }

    /**
     * Seals the archive: writes `checksums.json`, then closes the ZIP, which in
     * turn flushes the encrypting stream's final chunk and closes the
     * destination. Nothing may be written afterwards.
     */
    fun finish() {
        if (finished) return
        finished = true
        val payload = BackupManifest.json.encodeToString(
            BackupChecksums(entries = checksums.toMap())
        )
        zip.setLevel(Deflater.BEST_COMPRESSION)
        zip.putNextEntry(ZipEntry(ENTRY_CHECKSUMS))
        zip.write(payload.toByteArray())
        zip.closeEntry()
        zip.close()
    }

    /**
     * Abandons an unfinished archive. The partial file is the caller's to
     * delete — a half-written backup left in the destination folder is exactly
     * the thing that makes people distrust a backup system.
     */
    override fun close() {
        if (finished) return
        finished = true
        runCatching { zip.close() }
    }

    companion object {
        private const val DIGEST = "SHA-256"
        private const val BUFFER_BYTES = 64 * 1024

        /**
         * @param key null for an unencrypted archive, in which case [kdf] must
         *   also be null and the payload is a plain ZIP.
         */
        fun open(
            sink: OutputStream,
            createdAtMillis: Long,
            key: SecretKey?,
            kdf: BackupCrypto.KdfParams?
        ): ArchiveWriter {
            BackupCrypto.writeHeader(sink, createdAtMillis, kdf)
            val payload = if (key != null && kdf != null) {
                BackupCrypto.encryptingStream(sink, key, kdf)
            } else {
                sink
            }
            return ArchiveWriter(ZipOutputStream(BufferedOutputStream(payload, BUFFER_BYTES)))
        }
    }
}
