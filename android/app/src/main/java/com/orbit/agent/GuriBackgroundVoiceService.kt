package com.orbit.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Keeps Guri's microphone session alive while another app (for example Instagram)
 * is in the foreground. It listens only for explicit Guri + next-Reel commands.
 */
class GuriBackgroundVoiceService : Service(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var actionInProgress = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LISTEN -> startBackgroundListening()
            ACTION_PAUSE -> stopBackgroundListening()
            ACTION_STOP -> {
                stopBackgroundListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Guri voice control", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows when Guri is ready for voice commands in other apps"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Guri voice control active")
            .setContentText("Instagram में Reel command सुनने के लिए तैयार")
            .setOngoing(true)
            .build()
    }

    private fun startBackgroundListening() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        listening = true
        actionInProgress = false
        startRecognition()
    }

    private fun stopBackgroundListening() {
        listening = false
        actionInProgress = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: Exception) {}
    }

    private fun ensureRecognizer(): Boolean {
        if (recognizer != null) return true
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (listening && !actionInProgress) handler.postDelayed({ startRecognition() }, 700)
                }
                override fun onResults(results: Bundle?) {
                    if (!listening || actionInProgress) return
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    handleBackgroundCommand(phrase)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        return true
    }

    private fun startRecognition() {
        if (!listening || actionInProgress) return
        if (!ensureRecognizer()) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try { recognizer?.startListening(intent) }
        catch (_: Exception) { handler.postDelayed({ startRecognition() }, 1000) }
    }

    private fun handleBackgroundCommand(raw: String) {
        val normalized = raw.lowercase(Locale.ROOT)
            .replace("गुरि", "गुरी").replace("गुरू", "गुरी")
        val hasWakeWord = normalized.contains("गुरी") || normalized.contains("guri")
        val asksNext = listOf("अगली", "अगला", "फिर स्क्रॉल", "फिर स्क्रोल", "रील स्क्रॉल",
            "रील स्क्रोल", "रील स्क्रॉल करो", "रील स्क्रोल करो", "री स्क्रॉल", "री स्क्रोल",
            "रीस्क्रॉल", "रीस्क्रोल", "स्क्रॉल करो", "स्क्रोल करो", "स्क्रॉल", "स्क्रोल", "next reel", "scroll up", "swipe up", "reel scroll")
            .any { normalized.contains(it) }
        if (!hasWakeWord || !asksNext) {
            handler.postDelayed({ startRecognition() }, 350)
            return
        }

        actionInProgress = true
        try { recognizer?.cancel() } catch (_: Exception) {}
        val service = AgentAccessibilityService.instance
        if (service == null || !service.isInstagramForeground()) {
            speak("Instagram पर Reel खुली होनी चाहिए।")
            return
        }
        val accepted = service.swipeReels(1) { completed ->
            handler.post {
                speak(if (completed) "अगली Reel कर दी।" else "Reel swipe नहीं हो पाया।")
            }
        }
        if (!accepted) speak("Instagram पर swipe शुरू नहीं हुआ।")
    }

    private fun speak(message: String) {
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "guri-background-result")
        handler.postDelayed({
            actionInProgress = false
            startRecognition()
        }, 1500)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("hi", "IN")
    }

    override fun onDestroy() {
        stopBackgroundListening()
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_LISTEN = "com.orbit.agent.action.LISTEN_BACKGROUND"
        const val ACTION_PAUSE = "com.orbit.agent.action.PAUSE_BACKGROUND"
        const val ACTION_STOP = "com.orbit.agent.action.STOP_BACKGROUND"
        private const val CHANNEL_ID = "guri_voice_control"
        private const val NOTIFICATION_ID = 8201
    }
}
