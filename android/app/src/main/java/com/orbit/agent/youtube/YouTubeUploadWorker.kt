package com.orbit.agent.youtube

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject

class YouTubeUploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.failure()
        val mime = inputData.getString(KEY_MIME) ?: "video/mp4"
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val description = inputData.getString(KEY_DESCRIPTION).orEmpty()
        val tags = inputData.getString(KEY_TAGS).orEmpty().split("|").filter(String::isNotBlank)
        val category = inputData.getString(KEY_CATEGORY) ?: "22"
        val token = UploadTokenStore(applicationContext).get(jobId) ?: return Result.failure(
            androidx.work.Data.Builder().putString("error", "Google authorization token नहीं मिला।").build()
        )

        return try {
            setForegroundAsync(createForegroundInfo("YouTube पर वीडियो अपलोड हो रहा है…")).get()
            val metadata = VideoMetadata(title, description, tags, category, "unlisted")
            val receipt = YouTubeResumableUploader(applicationContext.contentResolver).upload(
                uri, mime, metadata, token
            ) { sent, total ->
                setProgressAsync(androidx.work.Data.Builder()
                    .putLong("bytes_sent", sent)
                    .putLong("bytes_total", total)
                    .putInt("percent", ((sent * 100L) / total).toInt())
                    .build())
            }
            UploadTokenStore(applicationContext).remove(jobId)
            Result.success(androidx.work.Data.Builder()
                .putString("video_id", receipt.videoId)
                .putString("watch_url", receipt.watchUrl)
                .putString("privacy_status", "unlisted")
                .build())
        } catch (error: Exception) {
            UploadTokenStore(applicationContext).remove(jobId)
            Result.failure(androidx.work.Data.Builder()
                .putString("error", error.localizedMessage ?: error.javaClass.simpleName)
                .build())
        }
    }

    private fun createForegroundInfo(message: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "YouTube uploads", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Guri YouTube Upload")
                .setContentText(message)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(applicationContext)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Guri YouTube Upload")
                .setContentText(message)
                .setOngoing(true)
                .build()
        }
        val type = if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(NOTIFICATION_ID, notification, type)
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_URI = "video_uri"
        const val KEY_MIME = "mime_type"
        const val KEY_TITLE = "title"
        const val KEY_DESCRIPTION = "description"
        const val KEY_TAGS = "tags"
        const val KEY_CATEGORY = "category_id"
        private const val CHANNEL_ID = "youtube_uploads"
        private const val NOTIFICATION_ID = 9302
    }
}
