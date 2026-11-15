package com.orbit.agent.youtube

import android.net.Uri

data class VideoCandidate(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String
) {
    val durationSeconds: Long get() = durationMs / 1000L
}

data class VideoMetadata(
    val title: String,
    val description: String,
    val tags: List<String>,
    val categoryId: String = "22",
    val privacyStatus: String = "unlisted"
) {
    fun validate(): String? {
        if (title.isBlank()) return "Title खाली नहीं हो सकता।"
        if (title.length > 100) return "YouTube title 100 characters से अधिक नहीं हो सकता।"
        if (description.length > 5000) return "Description बहुत लंबा है।"
        if (tags.size > 500) return "Tags की संख्या बहुत अधिक है।"
        if (privacyStatus !in setOf("private", "public", "unlisted")) return "Privacy status मान्य नहीं है।"
        if (privacyStatus != "unlisted") return "इस workflow में Upload privacy Unlisted ही होनी चाहिए।"
        return null
    }
}

data class UploadReceipt(val videoId: String, val watchUrl: String)
