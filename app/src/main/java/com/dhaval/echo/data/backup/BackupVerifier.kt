package com.dhaval.echo.data.backup

import android.util.Log
import com.dhaval.echo.domain.backup.BackupFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a finished archive back and re-hashes every entry against the
 * `checksums.json` written inside it.
 *
 * This is a second full pass over the file, and for a media backup that is not
 * cheap. It is the difference between "we wrote some bytes" and "we have a
 * backup". None of the ways a write silently goes wrong — a buffer that never
 * reached the medium, an SD card that lied about a flush, an archive cut short
 * by a disk that filled during the write — announce themselves at write time.
 * Finding them now costs one read. Finding them during a restore costs the
 * diary.
 *
 * Also used by the Verify action in history, for archives that have been
 * sitting on removable storage for months.
 */
@Singleton
class BackupVerifier @Inject constructor(
    private val storage: StorageProvider
) {
    /**
     * @param onBytes progress, in decrypted bytes read.
     * @return null when the archive is sound, otherwise why it isn't.
     */
    suspend fun verify(
        uri: String,
        password: CharArray?,
        onBytes: (Long) -> Unit = {}
    ): BackupFailure? = try {
        ArchiveReader.open(storage.openRead(uri), password).use { reader ->
            val actual = mutableMapOf<String, String>()
            var declared: BackupChecksums? = null
            var seen = 0L

            while (true) {
                currentCoroutineContext().ensureActive()
                val name = reader.nextEntry() ?: break
                if (name == BackupManifest.ENTRY_CHECKSUMS) {
                    declared = reader.readChecksums()
                } else {
                    actual[name] = reader.digestEntry { delta ->
                        seen += delta
                        onBytes(seen)
                    }.second
                }
            }

            // checksums.json is the last entry, so its absence means the archive
            // ends early — which for an encrypted archive the chunk framing
            // would already have caught, and for a plain one it is the only
            // signal there is.
            val expected = declared?.entries
                ?: return BackupFailure.Corrupted("The backup is incomplete — no checksums")

            val mismatched = expected.count { (path, hash) -> actual[path] != hash }
            when (mismatched) {
                0 -> null
                else -> BackupFailure.Corrupted(
                    "$mismatched of ${expected.size} items don't match their checksums"
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackupDecryptionException) {
        if (e.isProbablyWrongPassword) BackupFailure.WrongPassword
        else BackupFailure.Corrupted("The backup is damaged (chunk ${e.chunkIndex})")
    } catch (e: BackupTruncatedException) {
        BackupFailure.Corrupted("The backup file is incomplete")
    } catch (e: Exception) {
        Log.e(TAG, "Verification failed for $uri", e)
        BackupFailure.Corrupted(e.message)
    }

    private companion object {
        const val TAG = "BackupVerifier"
    }
}
