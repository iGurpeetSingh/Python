package com.orbit.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var status: TextView
    private lateinit var micButton: Button
    private lateinit var accessibilityButton: Button
    private var listeningEnabled = true
    private var isResumed = false
    private var actionRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 48, 36, 48)
            setBackgroundColor(android.graphics.Color.rgb(8, 14, 26))
        }
        val orb = TextView(this).apply {
            text = "G"
            textSize = 64f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.rgb(150, 238, 212))
            setBackgroundColor(android.graphics.Color.rgb(21, 43, 58))
            val size = (170 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
                bottomMargin = 28
            }
        }
        status = TextView(this).apply {
            text = "गुरी सुनने के लिए तैयार है"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
        }
        val hint = TextView(this).apply {
            text = "बोलें: “गुरी, Instagram खोलो और Reels scroll करो”\nGuri खुद Instagram खोलेगी और Reels पर swipe करेगी।"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(8, 14, 8, 26)
        }
        micButton = Button(this).apply {
            text = "माइक बंद करें"
            setOnClickListener {
                listeningEnabled = !listeningEnabled
                if (listeningEnabled) {
                    startListening()
                    text = "माइक बंद करें"
                } else {
                    stopListening()
                    text = "माइक चालू करें"
                    status.text = "गुरी pause है"
                }
            }
        }
        accessibilityButton = Button(this).apply {
            text = "फोन Control चालू करें"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val help = TextView(this).apply {
            text = "पहली बार केवल दो permissions दें:\n1. Microphone permission\n2. Accessibility में Guri Voice Assistant ON\n\nकोई backend URL, pairing code या Render link नहीं चाहिए।"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.rgb(150, 190, 205))
            setPadding(8, 24, 8, 0)
        }
        layout.addView(orb)
        layout.addView(status)
        layout.addView(hint)
        layout.addView(micButton)
        layout.addView(accessibilityButton)
        layout.addView(help)
        setContentView(layout)
        updateAccessibilityButton()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        updateAccessibilityButton()
        if (listeningEnabled && hasAudioPermission()) handler.postDelayed({ startListening() }, 650)
    }

    override fun onPause() {
        isResumed = false
        stopListening()
        super.onPause()
    }

    private fun hasAudioPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val component = "$packageName/\${AgentAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    private fun updateAccessibilityButton() {
        if (!::accessibilityButton.isInitialized) return
        accessibilityButton.text = if (accessibilityEnabled()) "Phone Control चालू है ✓" else "Phone Control चालू करें"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                listeningEnabled = true
                handler.postDelayed({ startListening() }, 500)
            } else status.text = "Voice चलाने के लिए Microphone permission जरूरी है।"
        }
    }

    private fun ensureRecognizer(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "इस फोन पर Speech Recognition उपलब्ध नहीं है।"
            return false
        }
        if (recognizer != null) return true
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (isResumed && listeningEnabled) status.text = "गुरी सुन रही है…" }
                override fun onResults(results: Bundle?) {
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    handleVoiceInput(phrase)
                }
                override fun onError(error: Int) {
                    if (!listeningEnabled || !isResumed || actionRunning) return
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) status.text = "Microphone permission चालू करें।"
                    else handler.postDelayed({ startListening() }, 900)
                }
                override fun onBeginningOfSpeech() {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onRmsChanged(rmsdB: Float) {}
            })
        }
        return true
    }

    private fun startListening() {
        if (!listeningEnabled || !isResumed || actionRunning) return
        if (!hasAudioPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            return
        }
        if (!ensureRecognizer()) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "गुरी कहकर अपना आदेश दें")
        }
        try {
            recognizer?.startListening(intent)
            status.text = "गुरी सुन रही है…"
        } catch (error: Exception) {
            status.text = "Voice शुरू नहीं हुई: " + (error.localizedMessage ?: "unknown error")
            handler.postDelayed({ startListening() }, 1500)
        }
    }

    private fun stopListening() { try { recognizer?.cancel() } catch (_: Exception) {} }

    private fun handleVoiceInput(raw: String) {
        if (raw.isBlank()) {
            handler.postDelayed({ startListening() }, 500)
            return
        }
        val command = raw.replace(Regex("गुरी|गुरि|गुरू|\\bguri\\b", RegexOption.IGNORE_CASE), "")
            .replace(",", " ").trim().lowercase(Locale.ROOT)
        if (command.isBlank()) {
            speakAndListen("हाँ, बोलिए।")
            return
        }
        runCommand(command, raw)
    }

    private fun runCommand(command: String, original: String) {
        status.text = "समझ गई: $original"
        val hasInstagram = command.contains("instagram") || command.contains("इंस्टाग्राम")
        val asksReels = command.contains("reel") || command.contains("रील")
        val asksScroll = command.contains("scroll") || command.contains("स्क्रॉल") || command.contains("स्क्रोल") || command.contains("अगली")
        if (!hasInstagram && !asksReels && !asksScroll) {
            speakAndListen("अभी यह command उपलब्ध नहीं है। Instagram खोलो और Reels scroll करो बोलकर देखें।")
            return
        }
        val launch = packageManager.getLaunchIntentForPackage("com.instagram.android")
        if (launch == null) {
            speakAndListen("Instagram इस फोन में install नहीं है।")
            return
        }
        if ((asksReels || asksScroll) && !accessibilityEnabled()) {
            updateAccessibilityButton()
            speakAndListen("पहले Phone Control चालू करें। नीचे का button दबाकर Guri Accessibility enable करें।")
            return
        }
        actionRunning = true
        stopListening()
        status.text = "Instagram खोल रही हूँ…"
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } catch (error: Exception) {
            actionRunning = false
            speakAndListen("Instagram नहीं खुल पाया। " + (error.localizedMessage ?: ""))
            return
        }
        if (asksReels || asksScroll) attemptOpenReels(command, 0)
        else {
            actionRunning = false
            speakAndListen("Instagram खोल दिया।")
        }
    }

    private fun attemptOpenReels(command: String, attempts: Int) {
        handler.postDelayed({
            val opened = AgentAccessibilityService.instance?.openReelsTab() == true
            if (!opened && attempts < 6) {
                attemptOpenReels(command, attempts + 1)
                return@postDelayed
            }
            if (opened && (command.contains("scroll") || command.contains("स्क्रॉल") || command.contains("स्क्रोल") || command.contains("अगली"))) {
                handler.postDelayed({
                    val count = extractCount(command).coerceIn(1, 20)
                    val queued = AgentAccessibilityService.instance?.swipeReels(count) == true
                    finishAction(if (queued) "Instagram Reels पर $count बार scroll कर दिया।" else "Reels खुल गए, लेकिन scroll gesture नहीं चला।")
                }, 1000)
            } else finishAction(if (opened) "Instagram Reels खोल दिए।" else "Instagram खुल गया, लेकिन Reels tab नहीं मिला।")
        }, if (attempts == 0) 2200 else 900)
    }

    private fun finishAction(message: String) {
        actionRunning = false
        status.text = message
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "guri-action-result")
        if (listeningEnabled) handler.postDelayed({ startListening() }, 1800)
    }

    private fun extractCount(command: String): Int {
        Regex("\\d+").find(command)?.value?.toIntOrNull()?.let { return it }
        val numbers = mapOf("एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5, "छह" to 6, "छः" to 6, "सात" to 7, "आठ" to 8, "नौ" to 9, "दस" to 10)
        return numbers.entries.firstOrNull { command.contains(it.key) }?.value ?: 3
    }

    private fun speakAndListen(message: String) {
        status.text = message
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "guri-reply")
        if (listeningEnabled) handler.postDelayed({ startListening() }, 1800)
    }

    override fun onInit(statusCode: Int) { if (statusCode == TextToSpeech.SUCCESS) tts?.language = Locale("hi", "IN") }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        super.onDestroy()
    }

    companion object { private const val REQUEST_AUDIO = 42 }
}
