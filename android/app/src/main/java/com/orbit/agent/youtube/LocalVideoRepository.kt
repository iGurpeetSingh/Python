package com.orbit.agent.youtube

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

class LocalVideoRepository(private val context: Context) {
    fun findByDuration(targetSeconds: Long, toleranceSeconds: Long = 2L): List<VideoCandidate> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE
        )
        val results = mutableListOf<VideoCandidate>()
        val selection = "${MediaStore.Video.Media.DURATION} BETWEEN ? AND ?"
        val args = arrayOf(
            ((targetSeconds - toleranceSeconds).coerceAtLeast(0L) * 1000L).toString(),
            ((targetSeconds + toleranceSeconds) * 1000L).toString()
        )
        context.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                results += VideoCandidate(
                    ContentUris.withAppendedId(collection, id),
                    cursor.getString(nameCol) ?: "video_$id",
                    cursor.getLong(durationCol),
                    cursor.getLong(sizeCol),
                    cursor.getString(mimeCol) ?: "video/mp4"
                )
            }
        }
        return results
    }

    fun describe(uri: Uri): VideoCandidate {
        var name = "selected_video"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                name = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) ?: name
                size = it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE))
            }
        }
        val retriever = android.media.MediaMetadataRetriever()
        val duration = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                retriever.setDataSource(descriptor.fileDescriptor)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } ?: 0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        val mime = context.contentResolver.getType(uri) ?: "video/mp4"
        return VideoCandidate(uri, name, duration, size, mime)
    }
}
