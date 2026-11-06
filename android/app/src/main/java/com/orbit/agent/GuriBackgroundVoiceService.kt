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
    private var whatsAppAwaitingContact = false

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
        when (intent?.action ?: ACTION_LISTEN) {
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

    private fun startBackgroundListening() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { updateListeningNotification("Microphone permission नहीं मिली"); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { updateListeningNotification("Speech Recognition उपलब्ध नहीं है"); return }
        listening = true
        actionInProgress = false
        updateListeningNotification("Guri सुन रही है — Instagram Reel command बोलें")
        startRecognition()
    }

    private fun updateListeningNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String = "Guri voice control active"): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Guri Voice Control")
            .setContentText(message)
            .setOngoing(true)
            .build()
    }

    private fun stopBackgroundListening() {
        listening = false
        actionInProgress = false
        updateListeningNotification("Guri voice control बंद है")
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: Exception) {}
    }

    private fun ensureRecognizer(): Boolean {
        if (recognizer != null) return true
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { updateListeningNotification("Guri सुन रही है — बोलिए") }
                override fun onBeginningOfSpeech() { updateListeningNotification("Guri आपकी आवाज़ सुन रही है…") }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (!listening || actionInProgress) return
                    // Google SpeechRecognizer is a one-shot recognizer. Recreate it after
                    // end/error so the next command is not lost in a BUSY state.
                    handler.postDelayed({
                        try { recognizer?.cancel() } catch (_: Exception) {}
                        try { recognizer?.destroy() } catch (_: Exception) {}
                        recognizer = null
                        startRecognition()
                    }, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200 else 500)
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
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try { recognizer?.startListening(intent) }
        catch (_: Exception) { handler.postDelayed({ startRecognition() }, 1000) }
    }

    private fun handleBackgroundCommand(raw: String) {
        val normalized = raw.lowercase(Locale.ROOT)
            .replace("गुरि", "गुरी")
            .replace("गुरू", "गुरी")
            .replace("गुरु", "गुरी")
            .replace("गुरूजी", "गुरी")
            .replace("गुरुजी", "गुरी")
        val hasWakeWord = normalized.contains("गुरी") || normalized.contains("guri")
        val asksNext = listOf("अगली", "अगला", "फिर स्क्रॉल", "फिर स्क्रोल", "रील को स्क्रॉल", "रील को स्क्रोल", "रील स्क्रॉल",
            "रील स्क्रोल", "रील स्क्रॉल करो", "रील स्क्रोल करो", "री स्क्रॉल", "री स्क्रोल",
            "रीस्क्रॉल", "रीस्क्रोल", "स्क्रॉल करो", "स्क्रोल करो", "स्क्रॉल", "स्क्रोल", "next reel", "scroll up", "swipe up", "reel scroll")
            .any { normalized.contains(it) }
        if (!hasWakeWord) {
            updateListeningNotification("आवाज़ मिली, Guri नाम नहीं पहचाना: " + normalized.take(70))
            handler.postDelayed({ startRecognition() }, 350)
            return
        }

        updateListeningNotification("कमांड पहचानी: " + normalized.take(70))

        val asksInstagram = listOf("instagram", "इंस्टाग्राम").any { normalized.contains(it) }
        val asksWhatsApp = listOf("व्हाट्सएप", "व्हाट्स ऐप", "वाट्सएप", "whatsapp", "what's app").any { normalized.contains(it) }

        if (asksWhatsApp || (whatsAppAwaitingContact && (normalized.contains("चैट") || normalized.contains("chat") || normalized.contains("नाम")))) {
            handleWhatsAppCommand(normalized)
            return
        }

        if (asksNext && !asksInstagram) {
            val service = AgentAccessibilityService.instance
            if (service == null) {
                speak("फोन कंट्रोल चालू नहीं है। Guri की Accessibility अनुमति चालू करें।")
                return
            }
            actionInProgress = true
            try { recognizer?.cancel() } catch (_: Exception) {}
            val accepted = service.swipeCurrentFeed(1) { completed ->
                handler.post {
                    speak(if (completed) "एक बार scroll कर दिया।" else "इस स्क्रीन पर scroll नहीं हो पाया।")
                }
            }
            if (!accepted) speak("कोई active app screen नहीं मिली।")
            return
        }

        if (asksInstagram) {
            handleInstagramCommand(asksNext)
            return
        }

        val installedApp = resolveInstalledApp(normalized)
        if (installedApp != null) {
            val launch = packageManager.getLaunchIntentForPackage(installedApp.second)
            if (launch != null) {
                actionInProgress = true
                try {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                    speak("${installedApp.first} खोल दिया है।")
                } catch (_: Exception) {
                    speak("${installedApp.first} नहीं खुल पाया।")
                }
            } else {
                handler.postDelayed({ startRecognition() }, 350)
            }
            return
        }

        if (whatsAppAwaitingContact && (normalized.contains("चैट") || normalized.contains("chat") || normalized.contains("नाम"))) {
            handleWhatsAppCommand(normalized)
            return
        }

        handler.postDelayed({ startRecognition() }, 350)
    }

    private fun resolveInstalledApp(command: String): Pair<String, String>? {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launchIntent, 0)
            .mapNotNull { info ->
                val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                val packageName = info.activityInfo?.packageName.orEmpty()
                if (label.length < 2 || packageName.isBlank()) null
                else if (command.contains(label, ignoreCase = true)) label to packageName
                else null
            }
            .maxByOrNull { it.first.length }
    }

    private fun handleInstagramCommand(shouldScroll: Boolean) {
        val service = AgentAccessibilityService.instance
        if (service == null) {
            speak("फोन कंट्रोल चालू नहीं है। Guri की Accessibility अनुमति चालू करें।")
            return
        }
        actionInProgress = true
        try { recognizer?.cancel() } catch (_: Exception) {}
        val packageName = "com.instagram.android"
        val alreadyOpen = service.isPackageForeground(packageName)
        if (!alreadyOpen) {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch == null) {
                speak("Instagram इस फोन में इंस्टॉल नहीं है।")
                return
            }
            try {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            } catch (_: Exception) {
                speak("Instagram नहीं खुल पाया।")
                return
            }
        }
        if (!shouldScroll) {
            speak("Instagram खोल दिया है।")
            return
        }
        updateListeningNotification("Instagram खुल रहा है — Reels तैयार कर रही हूँ")
        handler.postDelayed({
            if (!service.isPackageForeground(packageName)) {
                speak("Instagram खुला नहीं मिला। स्क्रीन पर Instagram लाकर दोबारा बोलें।")
                return@postDelayed
            }
            val reelsOpened = if (alreadyOpen) true else service.openReelsTab()
            if (!reelsOpened) {
                speak("Instagram खुल गया, लेकिन Reels tab नहीं मिला।")
                return@postDelayed
            }
            handler.postDelayed({
                if (!service.isInstagramForeground()) {
                    speak("Instagram की स्क्रीन बदल गई; swipe नहीं किया।")
                    return@postDelayed
                }
                val accepted = service.swipeReels(1) { completed ->
                    handler.post {
                        speak(if (completed) "एक Reel आगे कर दी। अगली Reel के लिए फिर बोलें।"
                              else "Reel swipe पूरा नहीं हुआ।")
                    }
                }
                if (!accepted) speak("Reel पर swipe शुरू नहीं हुआ।")
            }, 650)
        }, if (alreadyOpen) 250 else 1800)
    }

    private fun handleWhatsAppCommand(command: String) {
        val service = AgentAccessibilityService.instance
        if (service == null) {
            speak("फोन कंट्रोल चालू नहीं है। Guri की Accessibility अनुमति चालू करें।")
            return
        }
        val recipient = extractWhatsAppRecipient(command)
        actionInProgress = true
        val launch = packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (launch == null) {
            speak("WhatsApp इस फोन में इंस्टॉल नहीं है।")
            return
        }
        if (!service.isPackageForeground("com.whatsapp")) {
            try {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            } catch (_: Exception) {
                speak("WhatsApp नहीं खुल पाया।")
                return
            }
        }

        handler.postDelayed({
            if (!service.isPackageForeground("com.whatsapp")) {
                speak("WhatsApp अभी स्क्रीन पर active नहीं है।")
                return@postDelayed
            }
            if (recipient.isNullOrBlank()) {
                whatsAppAwaitingContact = true
                speak("WhatsApp खोल दिया है। अब chat का नाम बताइए।")
                return@postDelayed
            }
            whatsAppAwaitingContact = false
            updateListeningNotification("WhatsApp में $recipient chat खोल रही हूँ")
            val searchOpened = service.clickVisibleLabel(
                "search", "Search", "Search chats", "खोजें", "खोज", "Search contacts"
            )
            handler.postDelayed({
                if (!service.isPackageForeground("com.whatsapp")) {
                    speak("WhatsApp की स्क्रीन बदल गई।")
                    return@postDelayed
                }
                val typed = service.typeIntoSearchField(recipient)
                if (!typed) {
                    speak("WhatsApp की Search field नहीं मिली।")
                    return@postDelayed
                }
                handler.postDelayed({
                    val chatOpened = service.clickVisibleLabel(recipient)
                    if (!chatOpened) {
                        speak("$recipient नाम की chat नहीं मिली।")
                        return@postDelayed
                    }
                    handler.postDelayed({
                        val drafted = service.typeMessageIntoComposer("Hi")
                        if (drafted) {
                            speak("$recipient की WhatsApp chat में Hi लिख दिया है। Send नहीं किया है।")
                        } else {
                            speak("Chat खुल गई, लेकिन message box नहीं मिला। Hi नहीं लिखा गया।")
                        }
                    }, 1100)
                }, 1500)
            }, if (searchOpened) 650 else 1000)
        }, 1200)
    }

    private fun extractWhatsAppRecipient(command: String): String? {
        val source = command
            .replace(Regex("whatsapp|what's app|व्हाट्सएप|व्हाट्स ऐप|वाट्सएप", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("गुरी|गुरि|गुरू|\\bguri\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("ओपन करो|open करो|खोलो|ओपन|open|जाओ|जाके|जाकर|उसमें|उसमें जाकर|उस चैट में|एक|है|चैट|chat|में|जाना|जाना है|जाकर|फिर|उसको|लिखो|लिखना|hi|हाय|send|सेंड|नहीं करना|मत भेजना|भेजना नहीं", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim()
        val patterns = listOf(
            Regex("([\\p{L}][\\p{L}0-9 .'-]{0,24}?)\\s+के नाम से", RegexOption.IGNORE_CASE),
            Regex("([\\p{L}][\\p{L}0-9 .'-]{0,24}?)\\s+नाम की", RegexOption.IGNORE_CASE),
            Regex("नाम से\\s+([\\p{L}][\\p{L}0-9 .'-]{0,24})", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val value = pattern.find(source)?.groupValues?.getOrNull(1)?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun speak(message: String) {
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "guri-background-result")
        handler.postDelayed({
            actionInProgress = false
            updateListeningNotification("Guri सुन रही है — अगली Reel command बोलें")
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
