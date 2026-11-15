package com.orbit.agent.youtube

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class YouTubeResumableUploader(
    private val resolver: ContentResolver,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()
) {
    fun upload(
        uri: Uri,
        mimeType: String,
        metadata: VideoMetadata,
        accessToken: String,
        onProgress: (Long, Long) -> Unit
    ): UploadReceipt {
        metadata.validate()?.let { throw IllegalArgumentException(it) }
        require(accessToken.isNotBlank()) { "Google authorization token missing." }
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Video file open नहीं हुई।")
        descriptor.use { pfd ->
            val total = android.system.Os.fstat(pfd.fileDescriptor).st_size
            if (total <= 0L) throw IOException("Video file का size पता नहीं चला।")
            val metadataJson = JSONObject().apply {
                put("snippet", JSONObject().apply {
                    put("title", metadata.title)
                    put("description", metadata.description)
                    put("tags", org.json.JSONArray(metadata.tags))
                    put("categoryId", metadata.categoryId)
                })
                put("status", JSONObject().put("privacyStatus", "unlisted"))
            }
            val initRequest = Request.Builder()
                .url("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status")
                .header("Authorization", "Bearer $accessToken")
                .header("X-Upload-Content-Length", total.toString())
                .header("X-Upload-Content-Type", mimeType)
                .header("Content-Type", "application/json; charset=UTF-8")
                .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), metadataJson.toString()))
                .build()
            val sessionUrl = client.newCall(initRequest).execute().use { response ->
                if (!response.isSuccessful) throw IOException("YouTube API HTTP ${response.code}: ${response.body?.string()?.take(800)}")
                response.header("Location") ?: throw IOException("YouTube ने resumable session URL नहीं लौटाया।")
            }

            val channel = FileInputStream(pfd.fileDescriptor).channel
            var offset = 0L
            val chunkSize = 8L * 1024L * 1024L
            while (offset < total) {
                val length = minOf(chunkSize, total - offset).toInt()
                val bytes = java.nio.ByteBuffer.allocate(length)
                channel.position(offset)
                while (bytes.hasRemaining()) {
                    if (channel.read(bytes) < 0) throw IOException("Video file पढ़ते समय stream समाप्त हो गई।")
                }
                val end = offset + length - 1
                val chunkRequest = Request.Builder()
                    .url(sessionUrl)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Type", mimeType)
                    .header("Content-Range", "bytes $offset-$end/$total")
                    .put(RequestBody.create(mimeType.toMediaType(), bytes.array()))
                    .build()
                val response = executeWithRetry(chunkRequest)
                response.use {
                    when (it.code) {
                        308 -> {
                            val accepted = it.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: (end + 1)
                            if (accepted <= offset || accepted > total) throw IOException("YouTube ने invalid upload byte range लौटाया।")
                            offset = accepted
                            onProgress(offset, total)
                        }
                        201 -> {
                            val result = JSONObject(it.body?.string() ?: "{}")
                            val id = result.optString("id")
                            if (id.isBlank()) throw IOException("YouTube response में video ID नहीं मिली।")
                            onProgress(total, total)
                            return UploadReceipt(id, "https://www.youtube.com/watch?v=$id")
                        }
                        else -> throw IOException("YouTube API HTTP ${it.code}: ${it.body?.string()?.take(800)}")
                    }
                }
            }
            throw IOException("YouTube ने final video resource नहीं लौटाया।")
        }
    }

    private fun executeWithRetry(request: Request): okhttp3.Response {
        var lastError: IOException? = null
        repeat(4) { attempt ->
            try {
                val response = client.newCall(request).execute()
                if (response.code !in listOf(500, 502, 503, 504)) return response
                response.close()
                lastError = IOException("YouTube temporary server error.")
            } catch (error: IOException) {
                lastError = error
            }
            try { Thread.sleep(1000L shl attempt) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Upload cancel हो गया।")
            }
        }
        throw lastError ?: IOException("Upload network failure.")
    }
}
