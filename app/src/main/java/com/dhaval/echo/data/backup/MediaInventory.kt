package com.dhaval.echo.data.backup

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One media file, and where it sits inside an archive.
 *
 * @property prefix which root it came from — the thing that decides whether it
 *   counts as a recording, a photo or a video, and where it goes back to on
 *   restore.
 */
data class MediaFile(
    val entryPath: String,
    val file: File,
    val prefix: String
) {
    /**
     * Recordings are accompanied by `.json` metadata sidecars written by
     * [com.dhaval.echo.data.audio.AndroidAudioStorageEngine]. They are backed
     * up, but counting them would report roughly twice as many recordings as
     * the user actually has.
     */
    val isRecording: Boolean
        get() = prefix == EchoStorageRoots.MEDIA_AUDIO && !file.name.endsWith(".json")

    val isPhoto: Boolean get() = prefix == EchoStorageRoots.MEDIA_IMAGES
    val isVideo: Boolean get() = prefix == EchoStorageRoots.MEDIA_VIDEO
}

/** Everything under the media roots, as archive entries. */
@Singleton
class MediaInventory @Inject constructor(
    private val roots: EchoStorageRoots
) {
    fun enumerate(): List<MediaFile> = roots.mediaRoots.flatMap { (prefix, root) ->
        if (!root.isDirectory) return@flatMap emptyList()
        root.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                MediaFile(
                    // Separators are normalised because the entry path becomes a
                    // ZIP name, which is defined as '/'-separated regardless of
                    // the platform that wrote it.
                    entryPath = "${BackupManifest.PREFIX_MEDIA}$prefix/" +
                        file.toRelativeString(root).replace('\\', '/'),
                    file = file,
                    prefix = prefix
                )
            }
            .toList()
    }
}
