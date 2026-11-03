package com.orbit.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.tts.TextToSpeech
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var status: TextView
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 48, 32, 48)
        }
        status = TextView(this).apply {
            text = "नमस्ते! मैं Orbit हूँ। बोलिए, आपको क्या काम करवाना है?"
            textSize = 20f
            gravity = Gravity.CENTER
        }
        val speak = Button(this).apply {
            text = "🎙 बोलकर आदेश दें"
            setOnClickListener { listen() }
        }
        val settings = Button(this).apply {
            text = "Enable Accessibility Control"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        layout.addView(status)
        layout.addView(speak)
        layout.addView(settings)
        setContentView(layout)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
        }
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "इस फोन पर Speech Recognition उपलब्ध नहीं है।"
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "सुन रहा हूँ… बोलिए।" }
                override fun onResults(results: Bundle?) {
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    handleCommand(phrase)
                }
                override fun onError(error: Int) { status.text = "आवाज़ समझ नहीं आई। फिर से कोशिश करें।" }
                override fun onBeginningOfSpeech() {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onRmsChanged(rmsdB: Float) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "अपना काम बताइए")
            }
            sr.startListening(intent)
        }
    }

    private fun handleCommand(raw: String) {
        val command = raw.lowercase(Locale.ROOT)
        status.text = "आपने कहा: $raw"
        val service = AgentAccessibilityService.instance
        when {
            command.contains("instagram") || command.contains("इंस्टाग्राम") -> {
                val launch = packageManager.getLaunchIntentForPackage("com.instagram.android")
                if (launch == null) reply("Instagram इस फोन में इंस्टॉल नहीं है।")
                else {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                    reply("Instagram खोल रहा हूँ। Reels खोलने की कोशिश करूँगा।")
                    status.postDelayed({ AgentAccessibilityService.instance?.openReelsTab() }, 1400)
                }
            }
            command.contains("reel") || command.contains("रील") -> {
                if (service == null) reply("पहले Accessibility Control चालू करें।")
                else if (service.openReelsTab()) reply("Reels खोलने की कोशिश कर रहा हूँ।")
                else reply("Instagram खोलें और फिर Reels बोलें।")
            }
            command.contains("scroll") || command.contains("स्क्रॉल") || command.contains("अगली") -> {
                val count = Regex("\\d+").find(command)?.value?.toIntOrNull()?.coerceIn(1, 20) ?: 1
                if (service?.swipeReels(count) == true) reply("$count बार स्क्रॉल कर दिया।")
                else reply("Instagram Reels स्क्रीन सामने नहीं है। पहले Reels खोलें।")
            }
            else -> reply("यह आदेश अभी उपलब्ध नहीं है। Instagram खोलो, Reels खोलो या अगली Reel बोलकर देखें।")
        }
    }

    private fun reply(text: String) {
        status.text = text
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "orbit-reply")
    }
    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) tts?.language = Locale("hi", "IN")
    }
    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
