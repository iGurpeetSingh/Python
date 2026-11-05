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
    private var pendingMessageReady = false
    private var pendingMessagePackage = ""

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
        val component = packageName + "/" + AgentAccessibilityService::class.java.name
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
        if (pendingMessageReady && (command.contains("भेज") || command.contains("send") || command.contains("हाँ") || command.contains("हां"))) {
            val service = AgentAccessibilityService.instance
            if (service == null || !service.isPackageForeground(pendingMessagePackage)) {
                pendingMessageReady = false
                finishAction("Message वाली chat अब active नहीं है। दोबारा command दें।")
                return
            }
            actionRunning = true
            stopListening()
            val sent = service.clickVisibleLabel("send", "भेजें", "भेजो", "send message")
            pendingMessageReady = false
            pendingMessagePackage = ""
            finishAction(if (sent) "Send button दबा दिया।" else "Send button नहीं मिला; message draft में सुरक्षित है।")
            return
        }
        val app = resolveTargetApp(command)
        if (app == null) {
            speakAndListen("कौन सा ऐप खोलना है? WhatsApp, Instagram या Facebook बोलें।")
            return
        }
        if (!accessibilityEnabled()) {
            updateAccessibilityButton()
            speakAndListen("पहले Phone Control चालू करें। Accessibility में Guri Voice Assistant enable करें।")
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(app.second)
        if (launch == null) {
            speakAndListen(app.first + " इस फोन में install नहीं है।")
            return
        }

        val recipient = extractRecipient(command, app.first)
        val message = extractMessage(command)
        val wantsScroll = command.contains("scroll") || command.contains("स्क्रॉल") ||
            command.contains("स्क्रोल") || command.contains("अगली") || command.contains("रील")

        actionRunning = true
        stopListening()
        status.text = app.first + " खोल रही हूँ…"
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } catch (error: Exception) {
            finishAction(app.first + " नहीं खुल पाया: " + (error.localizedMessage ?: ""))
            return
        }

        handler.postDelayed({
            val activeService = AgentAccessibilityService.instance
            if (activeService == null) {
                finishAction(app.first + " खुला, लेकिन Accessibility service उपलब्ध नहीं है।")
                return@postDelayed
            }
            when {
                wantsScroll && app.first == "Instagram" -> {
                    if (!activeService.isPackageForeground(app.second)) {
                        finishAction("Instagram अभी foreground में नहीं आया।")
                        return@postDelayed
                    }
                    val opened = activeService.openReelsTab()
                    if (!opened) {
                        finishAction("Instagram खुला, लेकिन Reels tab नहीं मिला।")
                        return@postDelayed
                    }
                    val count = extractCount(command).coerceIn(1, 20)
                    val accepted = activeService.swipeReels(count) { done ->
                        handler.post { finishAction(if (done) "Instagram Reels पर $count swipe पूरे हुए।" else "Swipe पूरा नहीं हुआ।") }
                    }
                    if (!accepted) finishAction("Reels पर swipe शुरू नहीं हुआ।")
                }
                !recipient.isNullOrBlank() && (app.first == "WhatsApp" || app.first == "Instagram") -> {
                    runContactWorkflow(activeService, app.first, app.second, recipient, message)
                }
                else -> finishAction(app.first + " खोल दिया।" +
                    if (recipient.isNullOrBlank()) "" else " Contact '$recipient' command से स्पष्ट नहीं मिला।")
            }
        }, 1600)
    }

    private fun runContactWorkflow(
        service: AgentAccessibilityService,
        appName: String,
        packageName: String,
        recipient: String,
        message: String
    ) {
        if (!service.isPackageForeground(packageName)) {
            finishAction("$appName खुला, लेकिन अभी स्क्रीन active नहीं है।")
            return
        }
        status.text = "$appName में $recipient खोज रही हूँ…"
        val searchClicked = service.clickVisibleLabel(
            "search", "खोजें", "खोज", "Search", "Search chats", "Search people"
        )
        handler.postDelayed({
            if (!service.isPackageForeground(packageName)) {
                finishAction("$appName की स्क्रीन बदल गई; कार्रवाई रोक दी।")
                return@postDelayed
            }
            val typed = service.typeIntoSearchField(recipient)
            if (!typed) {
                finishAction("$appName में Search field नहीं मिली। Search icon खोलकर दोबारा बोलें।")
                return@postDelayed
            }
            handler.postDelayed({
                val opened = service.clickVisibleLabel(recipient)
                if (!opened) {
                    finishAction("$appName में '$recipient' का matching result नहीं मिला।")
                    return@postDelayed
                }
                handler.postDelayed({
                    if (appName == "Instagram") {
                        val messageButton = service.clickVisibleLabel("message", "संदेश", "मैसेज")
                        if (!messageButton) {
                            finishAction("Instagram profile खुली, लेकिन Message button नहीं मिला।")
                            return@postDelayed
                        }
                        handler.postDelayed({
                            val drafted = service.typeMessageIntoComposer(message)
                            if (drafted) {
                                pendingMessageReady = true
                                pendingMessagePackage = packageName
                                finishAction("$recipient की Instagram chat में '$message' लिखा है। भेजने के लिए बोलें: हाँ भेज दो।")
                            } else finishAction("Instagram chat खुली, लेकिन message box नहीं मिला।")
                        }, 900)
                    } else {
                        val drafted = service.typeMessageIntoComposer(message)
                        if (drafted) {
                            pendingMessageReady = true
                            pendingMessagePackage = packageName
                            finishAction("$recipient की WhatsApp chat में '$message' लिखा है। भेजने के लिए बोलें: हाँ भेज दो।")
                        } else finishAction("WhatsApp chat खुली, लेकिन message box नहीं मिला।")
                    }
                }, 1000)
            }, 1400)
        }, if (searchClicked) 700 else 1100)
    }

    private fun extractMessage(command: String): String {
        val match = Regex("(?:message|मैसेज|संदेश)\\s+(?:लिखो|लिखना|करो|भेजो)?\\s*(hi|hello|हाय|हेलो|नमस्ते)", RegexOption.IGNORE_CASE)
            .find(command)
        if (match != null) return match.groupValues[1]
        return if (command.contains("hello") || command.contains("हेलो")) "Hello" else "Hi"
    }

    private fun extractRecipient(command: String, appName: String): String? {
        val personalName = Regex("(?:मेरी|मेरा|मेरे|my)\\s+([\\p{L}][\\p{L} .'-]{1,28}?)\\s+(?:के नाम|का नाम|की प्रोफाइल|की profile)", RegexOption.IGNORE_CASE)
            .find(command)?.groupValues?.getOrNull(1)?.trim()
        if (!personalName.isNullOrBlank()) return personalName

        val namedContact = Regex("(?:दोस्त|फ्रेंड|friend|contact|chat|profile|प्रोफाइल)\\s+(?:का नाम\\s+)?([\\p{L}][\\p{L} .'-]{1,28})", RegexOption.IGNORE_CASE)
            .find(command)?.groupValues?.getOrNull(1)?.trim()
        if (!namedContact.isNullOrBlank()) return namedContact

        var cleaned = command
            .replace(Regex("whatsapp|व्हाट्सएप|instagram|इंस्टाग्राम|facebook|फेसबुक", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("खोलो|ओपन करो|open|जाओ|जाके|जाकर|उस पर|उसपे|मेरी|मेरा|मेरे|खुद की|प्रोफाइल है|profile है|फ्रेंड है|friend है|मैसेज करो|message करो|मैसेज लिखो|message लिखो|hi लिखो|हाय लिखो|लिखो|भेजो|करो", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim()
        val patterns = listOf(
            Regex("(?:नाम से|contact|chat|profile)\\s+([\\p{L}][\\p{L} .'-]{1,35})", RegexOption.IGNORE_CASE),
            Regex("(?:पर|पे)\\s+([\\p{L}][\\p{L} .'-]{1,35})", RegexOption.IGNORE_CASE),
            Regex("([\\p{L}][\\p{L} .'-]{1,35}?)\\s+(?:पर|पे)\\b", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val found = pattern.find(cleaned)?.groupValues?.getOrNull(1)?.trim()
            if (!found.isNullOrBlank()) {
                val candidate = found.replace(Regex("^(मेरी|मेरा|मेरे|खुद की)\\s+"), "").trim()
                if (candidate.length >= 2 && !candidate.equals(appName, true)) return candidate
            }
        }
        // A plain "search <name>" command is also accepted.
        val search = Regex("(?:search|सर्च|खोजो)\\s+([\\p{L}][\\p{L} .'-]{1,35})", RegexOption.IGNORE_CASE)
            .find(cleaned)?.groupValues?.getOrNull(1)?.trim()
        return search?.takeIf { it.length >= 2 }
    }

    private fun resolveTargetApp(command: String): Pair<String, String>? {
        val apps = listOf(
            Triple("instagram", "Instagram", "com.instagram.android"),
            Triple("इंस्टाग्राम", "Instagram", "com.instagram.android"),
            Triple("whatsapp", "WhatsApp", "com.whatsapp"),
            Triple("व्हाट्सएप", "WhatsApp", "com.whatsapp"),
            Triple("facebook", "Facebook", "com.facebook.katana"),
            Triple("फेसबुक", "Facebook", "com.facebook.katana"),
            Triple("free fire", "Free Fire", "com.dts.freefireth"),
            Triple("free fire india", "Free Fire", "com.dts.freefireth"),
            Triple("फ्री फायर", "Free Fire", "com.dts.freefireth"),
            Triple("pubg", "PUBG Mobile", "com.tencent.ig"),
            Triple("pubg mobile", "PUBG Mobile", "com.tencent.ig"),
            Triple("bgmi", "BGMI", "com.pubg.imobile"),
            Triple("बीजीएमआई", "BGMI", "com.pubg.imobile"),
            Triple("पबजी", "PUBG Mobile", "com.tencent.ig")
        )
        val found = apps.mapNotNull { alias ->
            val index = command.indexOf(alias.first, ignoreCase = true)
            if (index >= 0) Triple(index, alias.second, alias.third) else null
        }.minByOrNull { it.first } ?: return null
        return found.second to found.third
    }

    private fun extractSearchText(command: String): String? {
        val patterns = listOf(
            Regex("(?:search\\s+करो|सर्च\\s+करो|खोजो|खोजें|ढूंढो|ढूँढो|search|सर्च)\\s+(.+?)(?:\\s+(?:और|फिर|then|लिखो|लिखना|open|खोलो).*)?$", RegexOption.IGNORE_CASE),
            Regex("(?:में|पर)\\s+(.+?)\\s+(?:सर्च|search|खोजो)", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(command) ?: continue
            val value = match.groupValues.getOrNull(1)?.trim()?.trim(',', '.', ' ')
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun attemptOpenReels(command: String, attempts: Int) {
        handler.postDelayed({
            val service = AgentAccessibilityService.instance
            if (service == null) {
                finishAction("Instagram खुल गया, लेकिन Guri Accessibility Service चालू नहीं है।")
                return@postDelayed
            }

            // Capture the actual phone display before deciding which UI control to tap.
            service.captureCurrentScreen { screenshotCaptured ->
                handler.post {
                    if (!service.isInstagramForeground()) {
                        if (attempts < 6) attemptOpenReels(command, attempts + 1)
                        else finishAction("Instagram foreground में नहीं आया; screen action रोक दिया।")
                        return@post
                    }

                    val opened = service.openReelsTab()
                    if (!opened && attempts < 6) {
                        attemptOpenReels(command, attempts + 1)
                        return@post
                    }
                    if (!opened) {
                        finishAction(
                            "Instagram खुल गया, लेकिन Reels tab नहीं मिला। " +
                                if (screenshotCaptured) "Screen capture सफल था।" else "Screen capture उपलब्ध नहीं हुआ।"
                        )
                        return@post
                    }

                    if (command.contains("scroll") || command.contains("स्क्रॉल") ||
                        command.contains("स्क्रोल") || command.contains("अगली")) {
                        handler.postDelayed({
                            val count = extractCount(command).coerceIn(1, 20)
                            val accepted = service.swipeReels(count) { completed ->
                                handler.post {
                                    finishAction(
                                        if (completed) "Instagram Reels पर $count बार scroll पूरा किया।"
                                        else "Reels खुले, लेकिन swipe बीच में रुक गया।"
                                    )
                                }
                            }
                            if (!accepted) finishAction("Reels खुले, लेकिन scroll gesture शुरू नहीं हुआ।")
                        }, 700)
                    } else {
                        finishAction("Instagram Reels खोल दिए।")
                    }
                }
            }
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
