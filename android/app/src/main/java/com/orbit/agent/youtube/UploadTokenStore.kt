package com.orbit.agent.youtube

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal class UploadTokenStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "youtube_upload_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun put(jobId: String, token: String) {
        preferences.edit().putString(jobId, token).apply()
    }

    fun get(jobId: String): String? = preferences.getString(jobId, null)

    fun remove(jobId: String) {
        preferences.edit().remove(jobId).apply()
    }
}
