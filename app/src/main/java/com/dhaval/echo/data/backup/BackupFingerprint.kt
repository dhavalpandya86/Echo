package com.dhaval.echo.data.backup

import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A cheap answer to "has anything changed since the last backup?".
 *
 * Without this, a phone whose owner didn't open Echo all week still writes seven
 * identical multi-gigabyte archives, evicting six genuinely distinct ones from
 * retention on the way. With it, a scheduled run on an unchanged device writes
 * nothing and says so.
 *
 * The fingerprint is a hash of the database's *contents* plus the media
 * inventory — path, size and modification time per file, never the media bytes
 * themselves. Hashing 2.8 GB of audio to decide whether to copy 2.8 GB of audio
 * would cost as much as the backup it is trying to avoid; recordings are also
 * immutable once written, so their metadata is a sound proxy.
 */
@Singleton
class BackupFingerprint @Inject constructor(
    private val roots: EchoStorageRoots
) {
    /**
     * Must be computed **after** the WAL checkpoint. SQLite in WAL mode leaves
     * recent writes in `echo_db-wal`, so hashing `echo_db` beforehand would
     * produce an unchanged fingerprint for a database that has in fact changed,
     * and Echo would skip a backup that was genuinely needed.
     */
    fun compute(includeMedia: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")

        digest.update(DATABASE_TAG)
        hashFileContents(digest, roots.database)

        if (includeMedia) {
            digest.update(MEDIA_TAG)
            // Paths relative to their root, sorted: the same media on the same
            // device must hash identically between runs regardless of the order
            // the filesystem happens to walk it in.
            roots.mediaRoots.forEach { (prefix, root) ->
                inventory(root).forEach { line ->
                    digest.update("$prefix/$line\n".toByteArray())
                }
            }
        }

        return digest.digest().toHexString()
    }

    private fun hashFileContents(digest: MessageDigest, file: File) {
        if (!file.exists()) return
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
    }

    private fun inventory(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .map { "${it.toRelativeString(root)}|${it.length()}|${it.lastModified()}" }
            .sorted()
            .toList()
    }

    private companion object {
        val DATABASE_TAG = "db:".toByteArray()
        val MEDIA_TAG = "media:".toByteArray()
        const val BUFFER_BYTES = 64 * 1024
    }
}
