package com.orbit.agent.youtube

import android.app.Activity
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

class YouTubeAuthorizationManager(private val activity: Activity) {
    fun authorize(requestCode: Int, onToken: (String) -> Unit, onError: (String) -> Unit) {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(UPLOAD_SCOPE)))
            .build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { result ->
                val token = result.accessToken
                if (!token.isNullOrBlank()) {
                    onToken(token)
                } else {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        onError("Google authorization ने access token नहीं दिया।")
                    } else {
                        try {
                            activity.startIntentSenderForResult(
                                pendingIntent.intentSender, requestCode, null, 0, 0, 0
                            )
                        } catch (e: Exception) {
                            onError("Google consent screen नहीं खुली: ${e.localizedMessage}")
                        }
                    }
                }
            }
            .addOnFailureListener { error ->
                onError("Google authorization failed: ${error.localizedMessage ?: error.javaClass.simpleName}")
            }
    }

    fun tokenFromResult(data: Intent?): String? = try {
        data?.let { Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(it).accessToken }
    } catch (_: ApiException) {
        null
    }

    companion object {
        const val REQUEST_CODE = 9301
        const val UPLOAD_SCOPE = "https://www.googleapis.com/auth/youtube.upload"
    }
}
