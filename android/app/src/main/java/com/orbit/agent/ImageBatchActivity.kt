package com.orbit.agent

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ImageBatchActivity : Activity() {
    private lateinit var promptInput: EditText
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(18))
            setBackgroundColor(android.graphics.Color.rgb(8, 14, 26))
        }
        val title = TextView(this).apply {
            text = "Guri • ChatGPT Image Batch"
            textSize = 23f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        val instructions = TextView(this).apply {
            text = "अपने prompts यहाँ paste करें। हर prompt को अलग paragraph (बीच में एक खाली line) में रखें। अधिकतम 50 prompts। ChatGPT में login होना चाहिए।"
            textSize = 14f
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(0, 0, 0, dp(12))
        }
        promptInput = EditText(this).apply {
            hint = "Prompt 1…\n\nPrompt 2…\n\nPrompt 3…"
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            minLines = 10
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setBackgroundColor(android.graphics.Color.rgb(25, 35, 49))
        }
        statusView = TextView(this).apply {
            text = "Accessibility permission ON करें, फिर prompts paste करके Start दबाएँ।"
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(150, 238, 212))
            setPadding(0, dp(12), 0, dp(12))
        }
        val start = Button(this).apply {
            text = "Start Image Batch"
            setOnClickListener { startBatch() }
        }
        val pause = Button(this).apply {
            text = "Pause"
            setOnClickListener {
                val service = AgentAccessibilityService.instance
                if (service == null) showStatus("Guri Accessibility Service उपलब्ध नहीं है।")
                else service.pauseChatGptImageBatch()
            }
        }
        val resume = Button(this).apply {
            text = "Resume"
            setOnClickListener {
                val service = AgentAccessibilityService.instance
                if (service == null) showStatus("Guri Accessibility Service उपलब्ध नहीं है।")
                else service.resumeChatGptImageBatch()
            }
        }
        val cancel = Button(this).apply {
            text = "Cancel Batch"
            setOnClickListener { AgentAccessibilityService.instance?.cancelChatGptImageBatch() }
        }
        root.addView(title)
        root.addView(instructions)
        root.addView(promptInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(statusView)
        root.addView(start)
        root.addView(pause)
        root.addView(resume)
        root.addView(cancel)
        setContentView(root)
    }

    private fun startBatch() {
        val prompts = promptInput.text.toString().trim()
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (prompts.isEmpty()) {
            showStatus("पहले कम-से-कम एक prompt paste करें।")
            return
        }
        if (prompts.size > 50) {
            showStatus("अधिकतम 50 prompts ही एक batch में चल सकते हैं।")
            return
        }
        val service = AgentAccessibilityService.instance
        if (service == null) {
            showStatus("Phone Control चालू नहीं है। Guri की Accessibility permission enable करें।")
            return
        }
        val started = service.startChatGptImageBatch(prompts) { message -> showStatus(message) }
        if (!started && statusView.text.isNullOrBlank()) showStatus("Batch शुरू नहीं हुई।")
    }

    private fun showStatus(message: String) {
        runOnUiThread { statusView.text = message }
    }
}
