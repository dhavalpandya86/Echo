package com.dhaval.echo.data.backup

import java.io.InputStream
import java.io.OutputStream

/**
 * Where archives are kept.
 *
 * The seam exists so the archive format never learns about its destination.
 * Today there is exactly one implementation, [SafStorageProvider], writing to a
 * folder the user picked. Adding Drive, a NAS, WebDAV or a USB stick later is a
 * new class here and nothing else — no change to the format, the pipeline, or
 * restore. Without the seam, "add cloud backup" would mean touching the code
 * that writes people's diaries, which is the code that should change least.
 */
interface StorageProvider {

    /** A human name for the destination, for the settings row. Null if unset. */
    suspend fun describe(): String?

    /** True when the destination exists and can be written to right now. */
    suspend fun isWritable(): Boolean

    /** Every Echo archive at the destination, newest first. */
    suspend fun list(): List<StoredArchive>

    /**
     * Creates an empty archive and hands back a stream to fill it.
     *
     * The file exists on disk from this moment, which is deliberate: it gives
     * [ArchiveSink.freeBytes] a real file descriptor to measure free space
     * against before any bytes are committed to it.
     */
    suspend fun openWrite(fileName: String): ArchiveSink

    suspend fun openRead(uri: String): InputStream

    suspend fun delete(uri: String): Boolean

    /** Size of an existing archive, or null when it can't be resolved. */
    suspend fun sizeOf(uri: String): Long?
}

/** An archive at rest. */
data class StoredArchive(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long
)

/**
 * A newly created, empty archive plus the stream that fills it.
 *
 * [freeBytes] is best-effort by design: it works for local storage, and returns
 * null for providers that can't answer (a cloud-backed document provider has no
 * meaningful "free space on the volume"). A null is reported as "unknown" and
 * the backup proceeds; inventing a number would be worse than admitting we
 * don't have one.
 */
interface ArchiveSink : AutoCloseable {
    val uri: String
    val stream: OutputStream
    fun freeBytes(): Long?
}
