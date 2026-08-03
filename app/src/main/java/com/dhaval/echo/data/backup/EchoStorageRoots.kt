package com.dhaval.echo.data.backup

import android.content.Context
import com.dhaval.echo.data.db.EchoDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where Echo's data actually is on disk.
 *
 * This exists because the answer was previously spread across the storage
 * engines, the ViewModels that save photos, and the storage reporter — and they
 * disagreed. [com.dhaval.echo.data.user.StorageReporter] measured `recordings/`
 * and `videos/`, directories nothing has ever written to, and therefore reported
 * every user's recordings as 0 bytes. Backup cannot afford that class of error:
 * a directory nobody remembered is a directory whose contents are silently not
 * backed up, discovered only when a restore comes back empty.
 *
 * So: one object, injected everywhere the question is asked, and the paths below
 * are the only definition of "Echo's data" the app has.
 */
@Singleton
class EchoStorageRoots @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Internal app storage. Photos and the DataStore live here. */
    val internalFiles: File get() = context.filesDir

    /**
     * App-specific external storage — where the audio and video engines write.
     *
     * Falls back to [internalFiles] exactly as
     * [com.dhaval.echo.data.audio.AndroidAudioStorageEngine] does; if the two
     * disagreed on a device without external storage, backup would look for
     * media in a place the recorder never wrote to.
     */
    val externalFiles: File get() = context.getExternalFilesDir(null) ?: context.filesDir

    /** The Room database file itself, without its -wal / -shm siblings. */
    val database: File get() = context.getDatabasePath(EchoDatabase.DATABASE_NAME)

    /** The database plus its write-ahead log and shared-memory files. */
    val databaseFamily: List<File>
        get() = database.let { db ->
            listOf(db, File("${db.path}-wal"), File("${db.path}-shm"))
        }

    /** Recordings: `Echo/audio/yyyy/MM/dd/{sessionId}.m4a` plus `.json` sidecars. */
    val audio: File get() = File(externalFiles, "Echo/audio")

    /** Videos: `Echo/video/yyyy/MM/dd/{videoId}.mp4`. */
    val video: File get() = File(externalFiles, "Echo/video")

    /** Extracted video poster frames. */
    val thumbnails: File get() = File(externalFiles, "Echo/thumbnails")

    /** Photos, copied into internal storage at attach time. */
    val images: File get() = File(internalFiles, "images")

    /** The preferences DataStore backing AiPreferences and AppearancePreferences. */
    val preferencesFile: File
        get() = File(internalFiles, "datastore/ai_preferences.preferences_pb")

    /**
     * Everything backup copies, keyed by the archive prefix it is stored under.
     *
     * Order matters only in that it keeps archives comparable between runs.
     */
    val mediaRoots: Map<String, File>
        get() = linkedMapOf(
            MEDIA_AUDIO to audio,
            MEDIA_VIDEO to video,
            MEDIA_THUMBNAILS to thumbnails,
            MEDIA_IMAGES to images
        )

    /**
     * Scratch space for staging a restore.
     *
     * Deliberately siblings of the real directories rather than somewhere in the
     * cache: committing a restore is then a rename within one filesystem, which
     * is instant and atomic, instead of a multi-gigabyte copy that can fail
     * halfway and leave the user with neither the old data nor the new.
     */
    fun stagingFor(root: File): File = File(root.parentFile, "${root.name}$STAGING_SUFFIX")

    /** Where the previous media is parked during a restore, so Undo can swap it back. */
    fun holdingFor(root: File): File = File(root.parentFile, "${root.name}$HOLDING_SUFFIX")

    /** The staged database, alongside the real one so the swap is a rename. */
    val stagedDatabase: File get() = File(database.parentFile, "${database.name}$STAGING_SUFFIX")

    /** The pre-restore copy of the database, kept for Undo. */
    val heldDatabase: File get() = File(database.parentFile, "${database.name}$HOLDING_SUFFIX")

    /**
     * Every scratch directory a crashed operation could have left behind.
     * Swept at launch by [OperationJournal]; see its recovery notes.
     */
    fun scratchPaths(): List<File> = buildList {
        mediaRoots.values.forEach {
            add(stagingFor(it))
            add(holdingFor(it))
        }
        add(stagedDatabase)
        add(heldDatabase)
    }

    /**
     * Models are pointedly absent from every list above. They are ~300 MB of
     * Whisper and e5 weights copied out of install-time asset packs, identical
     * on every device running this build, and they reinstall with the app.
     * Backing them up would triple every archive to protect data that is not
     * the user's and cannot be lost.
     */
    val modelDirectories: List<File>
        get() = listOf(
            File(internalFiles, "whisper"),
            File(internalFiles, "embeddings"),
            File(internalFiles, "models")
        )

    companion object {
        const val MEDIA_AUDIO = "audio"
        const val MEDIA_VIDEO = "video"
        const val MEDIA_THUMBNAILS = "thumbnails"
        const val MEDIA_IMAGES = "images"

        private const val STAGING_SUFFIX = ".restore-staging"
        private const val HOLDING_SUFFIX = ".restore-holding"
    }
}
