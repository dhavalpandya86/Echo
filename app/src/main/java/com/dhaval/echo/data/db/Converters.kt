package com.dhaval.echo.data.db

import android.util.Log
import androidx.room.TypeConverter
import com.dhaval.echo.domain.video.VideoAttachment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class Converters {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    @TypeConverter
    fun fromTimestamp(value: String?): LocalDateTime? {
        return value?.let { LocalDateTime.parse(it, formatter) }
    }

    @TypeConverter
    fun dateToTimestamp(date: LocalDateTime?): String? {
        return date?.format(formatter)
    }

    @TypeConverter
    fun fromStringList(value: String?): List<String>? {
        return value?.let { Json.decodeFromString(it) }
    }

    @TypeConverter
    fun toStringList(list: List<String>?): String? {
        return list?.let { Json.encodeToString(it) }
    }

    @TypeConverter
    fun fromEntityMap(value: String?): Map<String, List<String>>? {
        return value?.let { Json.decodeFromString(it) }
    }

    @TypeConverter
    fun toEntityMap(map: Map<String, List<String>>?): String? {
        return map?.let { Json.encodeToString(it) }
    }

    @TypeConverter
    fun fromVideoList(value: String?): List<VideoAttachment>? {
        if (value.isNullOrBlank()) return null
        // A row written by a newer build, or hand-edited, must not crash reads of
        // the whole memory — degrade to "no videos" rather than take the app down.
        return runCatching { lenientJson.decodeFromString<List<VideoAttachment>>(value) }
            .getOrElse {
                Log.w("Converters", "Could not decode videos column; treating as empty", it)
                null
            }
    }

    @TypeConverter
    fun toVideoList(videos: List<VideoAttachment>?): String? {
        return videos?.let { lenientJson.encodeToString(it) }
    }

    private companion object {
        /** Tolerates fields added to VideoAttachment by later versions. */
        val lenientJson = Json { ignoreUnknownKeys = true }
    }
}
