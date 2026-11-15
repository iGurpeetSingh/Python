package com.orbit.agent.youtube

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

object YouTubeUploadCoordinator {
    fun enqueue(
        context: Context,
        uri: Uri,
        mimeType: String,
        metadata: VideoMetadata,
        accessToken: String
    ): UUID {
        metadata.validate()?.let { throw IllegalArgumentException(it) }
        val jobId = UUID.randomUUID().toString()
        UploadTokenStore(context).put(jobId, accessToken)
        val input = Data.Builder()
            .putString(YouTubeUploadWorker.KEY_JOB_ID, jobId)
            .putString(YouTubeUploadWorker.KEY_URI, uri.toString())
            .putString(YouTubeUploadWorker.KEY_MIME, mimeType)
            .putString(YouTubeUploadWorker.KEY_TITLE, metadata.title)
            .putString(YouTubeUploadWorker.KEY_DESCRIPTION, metadata.description)
            .putString(YouTubeUploadWorker.KEY_TAGS, metadata.tags.joinToString("|"))
            .putString(YouTubeUploadWorker.KEY_CATEGORY, metadata.categoryId)
            .build()
        val request = OneTimeWorkRequestBuilder<YouTubeUploadWorker>()
            .setInputData(input)
            .addTag("youtube-upload")
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "youtube-upload-$jobId", ExistingWorkPolicy.KEEP, request
        )
        return request.id
    }
}
