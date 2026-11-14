package com.orbit.agent

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

private data class ObjectiveDraft(
    val prompt: StringBuilder = StringBuilder(),
    val options: MutableMap<Int, String> = mutableMapOf(),
    var correctIndex: Int? = null
)

private data class WrittenDraft(
    val prompt: StringBuilder = StringBuilder(),
    var marks: Int? = null
)

class CoachingExamActivity : Activity() {
    private lateinit var apiUrlInput: EditText
    private lateinit var adminTokenInput: EditText
    private lateinit var titleInput: EditText
    private lateinit var subjectInput: EditText
    private lateinit var classInput: EditText
    private lateinit var startAtInput: EditText
    private lateinit var durationInput: EditText
    private lateinit var instructionsInput: EditText
    private lateinit var paperTextInput: EditText
    private lateinit var statusText: TextView
    private lateinit var publishObjectiveButton: Button
    private lateinit var publishWrittenButton: Button
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Coaching & Exam Control"
        window.statusBarColor = android.graphics.Color.rgb(8, 14, 26)
        window.navigationBarColor = android.graphics.Color.rgb(8, 14, 26)

        val prefs = getSharedPreferences("coaching_exam_control", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(android.graphics.Color.rgb(8, 14, 26))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val heading = TextView(this).apply {
            text = "Coaching & Exam Control"
            textSize = 23f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "Create and publish Objective and Written papers to the Coaching website."
            textSize = 13f
            setTextColor(android.graphics.Color.rgb(165, 181, 198))
            setPadding(0, dp(7), 0, dp(18))
        }
        content.addView(heading)
        content.addView(subtitle)

        apiUrlInput = addInput(content, "Render API URL", prefs.getString("api_url", "") ?: "", "https://your-service.onrender.com")
        adminTokenInput = addInput(
            content, "Admin token (PAPER_ADMIN_TOKEN)",
            prefs.getString("admin_token", "") ?: "", "Paste the private admin token",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        addDivider(content)
        titleInput = addInput(content, "Exam title", "", "e.g. Science Mock Exam")
        subjectInput = addInput(content, "Subject", "", "e.g. Science")
        classInput = addInput(content, "Class / Batch", "", "e.g. Class 10 · Batch A")
        startAtInput = addInput(
            content, "Exam date and time (with timezone)",
            OffsetDateTime.now().plusHours(1).withSecond(0).withNano(0).toString(),
            "2026-11-20T10:00:00+05:30"
        )
        durationInput = addInput(content, "Duration (minutes)", "60", "60", InputType.TYPE_CLASS_NUMBER)
        instructionsInput = addInput(content, "Student instructions (optional)", "", "Read all questions carefully")
        addDivider(content)

        val pasteTitle = TextView(this).apply {
            text = "Paste question paper text"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(7))
        }
        content.addView(pasteTitle)
        val formatHint = TextView(this).apply {
            text = "Objective format: number each question (1. ...), add A) to D) options, then an Answer: B line. Written format: number each question and add Marks: 2, Marks: 4, or Marks: 6. Objective papers must have 50 questions; written papers must total 100 marks."
            textSize = 11f
            setTextColor(android.graphics.Color.rgb(159, 174, 190))
            setPadding(0, 0, 0, dp(10))
        }
        content.addView(formatHint)
        paperTextInput = EditText(this).apply {
            hint = "Paste your questions here…"
            textSize = 13f
            minLines = 12
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.rgb(119, 135, 154))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(android.graphics.Color.rgb(21, 31, 48))
        }
        content.addView(paperTextInput, LinearLayout.LayoutParams(-1, dp(280)).apply { bottomMargin = dp(2) })

        val previewButton = makeButton("Preview parsed paper", false) {
            previewPaper()
        }
        content.addView(previewButton, buttonParams())

        publishObjectiveButton = makeButton("Publish Objective Exam", true) {
            publishPaper("objective")
        }
        content.addView(publishObjectiveButton, buttonParams())

        publishWrittenButton = makeButton("Publish Written Exam", true) {
            publishPaper("written")
        }
        content.addView(publishWrittenButton, buttonParams())

        val unpublishTitle = TextView(this).apply {
            text = "PUBLISH CONTROL"
            textSize = 10f
            setTextColor(android.graphics.Color.rgb(156, 171, 190))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(17), 0, dp(7))
        }
        content.addView(unpublishTitle)
        val unpublishObjective = makeButton("Turn OFF Objective Exam", false) {
            setPublishedState("objective", false)
        }
        content.addView(unpublishObjective, buttonParams())
        val unpublishWritten = makeButton("Turn OFF Written Exam", false) {
            setPublishedState("written", false)
        }
        content.addView(unpublishWritten, buttonParams())

        statusText = TextView(this).apply {
            text = "Ready. Configure the API URL and admin token first."
            textSize = 12f
            setTextColor(android.graphics.Color.rgb(173, 192, 211))
            setPadding(0, dp(16), 0, 0)
        }
        content.addView(statusText)

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val back = Button(this).apply {
            text = "Back to Guri"
            setOnClickListener { finish() }
        }
        root.addView(back)
        setContentView(root)
    }

    private fun addInput(
        parent: LinearLayout,
        label: String,
        initial: String,
        hint: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT
    ): EditText {
        val title = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(android.graphics.Color.rgb(189, 201, 216))
            setPadding(0, dp(10), 0, dp(6))
        }
        parent.addView(title)
        val input = EditText(this).apply {
            setText(initial)
            this.hint = hint
            textSize = 13f
            this.inputType = inputType
            setSingleLine(inputType != (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.rgb(119, 135, 154))
            setPadding(dp(11), dp(9), dp(11), dp(9))
            setBackgroundColor(android.graphics.Color.rgb(21, 31, 48))
        }
        parent.addView(input, fieldParams())
        return input
    }

    private fun addDivider(parent: LinearLayout) {
        val divider = View(this).apply { setBackgroundColor(android.graphics.Color.rgb(44, 59, 79)) }
        val params = LinearLayout.LayoutParams(-1, dp(1))
        params.topMargin = dp(17)
        params.bottomMargin = dp(8)
        parent.addView(divider, params)
    }

    private fun makeButton(label: String, primary: Boolean, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            setOnClickListener { action() }
            if (primary) {
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.rgb(74, 105, 174))
            }
        }
    }

    private fun buttonParams() = LinearLayout.LayoutParams(-1, dp(48)).apply {
        topMargin = dp(9)
    }

    private fun fieldParams() = LinearLayout.LayoutParams(-1, dp(48)).apply {
        bottomMargin = dp(2)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun currentMetadata(): JSONObject {
        val duration = durationInput.text.toString().trim().toIntOrNull()
            ?: throw IllegalArgumentException("Duration must be a number.")
        if (titleInput.text.isBlank()) throw IllegalArgumentException("Exam title is required.")
        if (subjectInput.text.isBlank()) throw IllegalArgumentException("Subject is required.")
        if (classInput.text.isBlank()) throw IllegalArgumentException("Class or batch is required.")
        val startAt = startAtInput.text.toString().trim()
        try {
            OffsetDateTime.parse(startAt)
        } catch (_: Exception) {
            throw IllegalArgumentException("Enter date/time with timezone, e.g. 2026-11-20T10:00:00+05:30")
        }
        return JSONObject()
            .put("title", titleInput.text.toString().trim())
            .put("subject", subjectInput.text.toString().trim())
            .put("className", classInput.text.toString().trim())
            .put("startAt", startAt)
            .put("durationMinutes", duration)
            .put("instructions", instructionsInput.text.toString().trim())
    }

    private fun parseObjective(raw: String): JSONArray {
        val drafts = mutableListOf<ObjectiveDraft>()
        var current: ObjectiveDraft? = null
        val questionPattern = Regex("^(?:Q(?:uestion)?\\s*)?(\\d+)\\s*[.)\\:-]\\s*(.+)$", RegexOption.IGNORE_CASE)
        val optionPattern = Regex("^([A-D])\\s*[).:\\-]\\s*(.+)$", RegexOption.IGNORE_CASE)
        val answerPattern = Regex("^(?:answer|ans|correct\\s*option|उत्तर)\\s*[:=\\-]\\s*([A-D])\\s*$", RegexOption.IGNORE_CASE)

        raw.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val questionMatch = questionPattern.matchEntire(line)
            val optionMatch = optionPattern.matchEntire(line)
            val answerMatch = answerPattern.matchEntire(line)

            when {
                questionMatch != null -> {
                    current?.let { drafts.add(it) }
                    current = ObjectiveDraft().apply { prompt.append(questionMatch.groupValues[2].trim()) }
                }
                optionMatch != null -> {
                    val draft = current ?: throw IllegalArgumentException("Option found before the first numbered question.")
                    val optionIndex = optionMatch.groupValues[1].uppercase()[0] - 'A'
                    draft.options[optionIndex] = optionMatch.groupValues[2].trim()
                }
                answerMatch != null -> {
                    val draft = current ?: throw IllegalArgumentException("Answer found before the first numbered question.")
                    draft.correctIndex = answerMatch.groupValues[1].uppercase()[0] - 'A'
                }
                else -> current?.let {
                    if (it.options.isEmpty()) it.prompt.append(" ").append(line)
                }
            }
        }
        current?.let { drafts.add(it) }
        if (drafts.size != 50) throw IllegalArgumentException("Objective Exam needs exactly 50 numbered questions. Found ${drafts.size}.")
        val result = JSONArray()
        drafts.forEachIndexed { index, draft ->
            if (draft.prompt.isBlank()) throw IllegalArgumentException("Question ${index + 1} has no text.")
            if (draft.options.size != 4 || (0..3).any { draft.options[it].isNullOrBlank() }) {
                throw IllegalArgumentException("Question ${index + 1} needs A), B), C), and D) options.")
            }
            val answer = draft.correctIndex ?: throw IllegalArgumentException("Question ${index + 1} needs an Answer: A/B/C/D line.")
            result.put(
                JSONObject()
                    .put("prompt", draft.prompt.toString().trim())
                    .put("options", JSONArray((0..3).map { draft.options[it] }))
                    .put("correctIndex", answer)
                    .put("marks", 2)
            )
        }
        return result
    }

    private fun parseWritten(raw: String): JSONArray {
        val drafts = mutableListOf<WrittenDraft>()
        var current: WrittenDraft? = null
        val questionPattern = Regex("^(?:Q(?:uestion)?\\s*)?(\\d+)\\s*[.)\\:-]\\s*(.+)$", RegexOption.IGNORE_CASE)
        val marksPattern = Regex("^(?:marks?|अंक)\\s*[:=\\-]\\s*(\\d+)\\s*$", RegexOption.IGNORE_CASE)

        raw.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val questionMatch = questionPattern.matchEntire(line)
            val marksMatch = marksPattern.matchEntire(line)
            when {
                questionMatch != null -> {
                    current?.let { drafts.add(it) }
                    current = WrittenDraft().apply { prompt.append(questionMatch.groupValues[2].trim()) }
                }
                marksMatch != null -> {
                    val draft = current ?: throw IllegalArgumentException("Marks line found before the first numbered question.")
                    draft.marks = marksMatch.groupValues[1].toIntOrNull()
                }
                else -> current?.prompt?.append(" ")?.append(line)
            }
        }
        current?.let { drafts.add(it) }
        if (drafts.isEmpty()) throw IllegalArgumentException("No numbered written questions found.")
        var total = 0
        val result = JSONArray()
        drafts.forEachIndexed { index, draft ->
            val marks = draft.marks ?: throw IllegalArgumentException("Written question ${index + 1} needs a Marks: 2, 4, or 6 line.")
            if (marks !in listOf(2, 4, 6)) throw IllegalArgumentException("Question ${index + 1} must carry 2, 4, or 6 marks.")
            if (draft.prompt.isBlank()) throw IllegalArgumentException("Question ${index + 1} has no text.")
            total += marks
            result.put(JSONObject().put("prompt", draft.prompt.toString().trim()).put("marks", marks))
        }
        if (total != 100) throw IllegalArgumentException("Written Exam must total 100 marks. Current total: $total.")
        return result
    }

    private fun previewPaper() {
        try {
            val objective = runCatching { parseObjective(paperTextInput.text.toString()) }
            if (objective.isSuccess) {
                statusText.text = "Preview: ${objective.getOrThrow().length()} Objective questions · 100 marks. All answer keys detected."
                return
            }
            val written = runCatching { parseWritten(paperTextInput.text.toString()) }
            if (written.isSuccess) {
                statusText.text = "Preview: ${written.getOrThrow().length()} Written questions · 100 marks."
                return
            }
            statusText.text = "Preview failed. Check the numbered question, option, Answer or Marks format."
        } catch (error: Exception) {
            statusText.text = error.message ?: "Could not preview this paper."
        }
    }

    private fun publishPaper(paperType: String) {
        val payload = try {
            currentMetadata().apply {
                if (paperType == "objective") put("questions", parseObjective(paperTextInput.text.toString()))
                else put("writtenQuestions", parseWritten(paperTextInput.text.toString()))
            }
        } catch (error: Exception) {
            statusText.text = error.message ?: "Paper validation failed."
            return
        }
        sendPaperRequest(paperType, "publish", payload)
    }

    private fun setPublishedState(paperType: String, publish: Boolean) {
        if (publish) return
        sendPaperRequest(paperType, "unpublish", null)
    }

    private fun sendPaperRequest(paperType: String, action: String, payload: JSONObject?) {
        val baseUrl = apiUrlInput.text.toString().trim().trimEnd('/')
        val adminToken = adminTokenInput.text.toString().trim()
        if (!baseUrl.startsWith("https://")) {
            statusText.text = "Enter the secure HTTPS Render API URL first."
            return
        }
        if (adminToken.isBlank()) {
            statusText.text = "Enter the private admin token from Render Environment."
            return
        }
        val preferences = getSharedPreferences("coaching_exam_control", MODE_PRIVATE)
        preferences.edit()
            .putString("api_url", baseUrl)
            .putString("admin_token", adminToken)
            .apply()

        publishObjectiveButton.isEnabled = false
        publishWrittenButton.isEnabled = false
        statusText.text = if (action == "publish") "Validating and publishing $paperType paper…" else "Turning OFF $paperType paper…"

        Thread {
            try {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = (payload?.toString() ?: "{}").toRequestBody(mediaType)
                val url = "$baseUrl/api/admin/exams/$paperType/$action"
                val request = Request.Builder()
                    .url(url)
                    .header("X-Paper-Admin-Token", adminToken)
                    .header("Accept", "application/json")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    val responseText = response.body?.string().orEmpty()
                    val message = if (response.isSuccessful) {
                        runCatching { JSONObject(responseText).optString("message") }.getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: if (action == "publish") "${paperType.replaceFirstChar { it.uppercase() }} Exam published." else "${paperType.replaceFirstChar { it.uppercase() }} Exam is OFF."
                    } else {
                        runCatching { JSONObject(responseText).optString("detail") }.getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: "Request failed with HTTP ${response.code}."
                    }
                    runOnUiThread { statusText.text = message }
                }
            } catch (error: Exception) {
                runOnUiThread { statusText.text = "Connection failed: ${error.localizedMessage ?: "unknown error"}" }
            } finally {
                runOnUiThread {
                    publishObjectiveButton.isEnabled = true
                    publishWrittenButton.isEnabled = true
                }
            }
        }.start()
    }
}
