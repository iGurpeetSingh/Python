package com.orbit.agent

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Direct Android YouTube Data API uploader.
 * Uses a resumable session and bounded 8 MiB chunks; never loads the full video into memory.
 */
object YouTubeUploadManager {
    private const val INIT_URL =
        "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status"
    private const val CHUNK_BYTES = 8 * 1024 * 1024L

    data class VideoInfo(val name: String, val size: Long, val mimeType: String)

    data class UploadResult(val videoId: String, val title: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun inspect(resolver: ContentResolver, uri: Uri): VideoInfo {
        var name = "Guri upload"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        if (size <= 0L) {
            resolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
        }
        val mime = resolver.getType(uri)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
        require(size > 0L) { "वीडियो का file size पढ़ा नहीं जा सका।" }
        return VideoInfo(name, size, mime)
    }

    fun upload(
        context: Context,
        uri: Uri,
        accessToken: String,
        title: String,
        description: String,
        privacy: String = "unlisted",
        onProgress: (Long, Long) -> Unit,
        onSuccess: (UploadResult) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val resolver = context.applicationContext.contentResolver
                val info = inspect(resolver, uri)
                val safeTitle = title.trim().ifBlank { info.name.substringBeforeLast('.').take(100) }
                    .take(100)
                val metadata = JSONObject().apply {
                    put("snippet", JSONObject().apply {
                        put("title", safeTitle)
                        put("description", description.take(5000))
                        put("categoryId", "22")
                    })
                    put("status", JSONObject().apply {
                        put("privacyStatus", if (privacy in setOf("private", "public", "unlisted")) privacy else "unlisted")
                        put("selfDeclaredMadeForKids", false)
                    })
                }

                val initRequest = Request.Builder()
                    .url(INIT_URL)
                    .header("Authorization", "Bearer $accessToken")
                    .header("X-Upload-Content-Length", info.size.toString())
                    .header("X-Upload-Content-Type", info.mimeType)
                    .post(metadata.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val sessionUrl = client.newCall(initRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val detail = response.body?.string().orEmpty().take(700)
                        throw IOException("YouTube upload session नहीं बनी (HTTP ${response.code}): $detail")
                    }
                    response.header("Location") ?: throw IOException("YouTube ने resumable upload URL नहीं दिया।")
                }

                var offset = 0L
                var retries = 0
                while (offset < info.size) {
                    val input = resolver.openInputStream(uri) ?: throw IOException("फोन से वीडियो फाइल पढ़ी नहीं जा सकी।")
                    val chunk = input.use { stream ->
                        var skipped = 0L
                        while (skipped < offset) {
                            val count = stream.skip(offset - skipped)
                            if (count <= 0L) {
                                if (stream.read() < 0) throw IOException("वीडियो के upload offset तक पहुँचना संभव नहीं हुआ।")
                                skipped++
                            } else skipped += count
                        }
                        val wanted = minOf(CHUNK_BYTES, info.size - offset).toInt()
                        val bytes = ByteArray(wanted)
                        var read = 0
                        while (read < wanted) {
                            val count = stream.read(bytes, read, wanted - read)
                            if (count < 0) throw IOException("वीडियो पढ़ते समय फाइल अधूरी मिली।")
                            read += count
                        }
                        if (read == bytes.size) bytes else bytes.copyOf(read)
                    }
                    val end = offset + chunk.size - 1
                    val request = Request.Builder()
                        .url(sessionUrl)
                        .header("Authorization", "Bearer $accessToken")
                        .header("Content-Range", "bytes $offset-$end/${info.size}")
                        .put(chunk.toRequestBody(info.mimeType.toMediaType()))
                        .build()

                    try {
                        client.newCall(request).execute().use { response ->
                            when (response.code) {
                                200, 201 -> {
                                    val payload = JSONObject(response.body?.string().orEmpty())
                                    val id = payload.optString("id")
                                    if (id.isBlank()) throw IOException("YouTube response में video ID नहीं मिली।")
                                    onProgress(info.size, info.size)
                                    onSuccess(UploadResult(id, safeTitle))
                                    return@Thread
                                }
                                308 -> {
                                    val range = response.header("Range")
                                    offset = if (range.isNullOrBlank()) end + 1L
                                    else Regex("bytes=0-(\\d+)").find(range)?.groupValues?.get(1)?.toLongOrNull()?.plus(1L)
                                        ?: throw IOException("YouTube ने upload offset का invalid Range लौटाया।")
                                    retries = 0
                                    onProgress(offset.coerceAtMost(info.size), info.size)
                                }
                                else -> {
                                    val detail = response.body?.string().orEmpty().take(700)
                                    throw IOException("YouTube upload failed (HTTP ${response.code}): $detail")
                                }
                            }
                        }
                    } catch (error: IOException) {
                        retries++
                        if (retries > 4) throw error
                        // Ask YouTube how many bytes it has already stored before retrying.
                        val statusRequest = Request.Builder()
                            .url(sessionUrl)
                            .header("Authorization", "Bearer $accessToken")
                            .header("Content-Range", "bytes */${info.size}")
                            .put(ByteArray(0).toRequestBody(null))
                            .build()
                        client.newCall(statusRequest).execute().use { statusResponse ->
                            if (statusResponse.code == 308) {
                                val range = statusResponse.header("Range")
                                offset = if (range.isNullOrBlank()) 0L
                                else Regex("bytes=0-(\\d+)").find(range)?.groupValues?.get(1)?.toLongOrNull()?.plus(1L)
                                    ?: throw IOException("YouTube resume offset पढ़ा नहीं जा सका।")
                            } else if (statusResponse.code == 200 || statusResponse.code == 201) {
                                val payload = JSONObject(statusResponse.body?.string().orEmpty())
                                val id = payload.optString("id")
                                if (id.isBlank()) throw IOException("Upload complete हुआ, लेकिन video ID नहीं मिली।")
                                onProgress(info.size, info.size)
                                onSuccess(UploadResult(id, safeTitle))
                                return@Thread
                            } else {
                                throw IOException("YouTube upload resume जाँच विफल (HTTP ${statusResponse.code})")
                            }
                        }
                        Thread.sleep(800L * retries)
                    }
                }
                throw IOException("Upload पूरा हुआ लेकिन YouTube ने अंतिम video ID नहीं लौटाई।")
            } catch (error: Exception) {
                onError(error.message ?: "YouTube upload में अज्ञात त्रुटि आई।")
            }
        }.start()
    }
}
