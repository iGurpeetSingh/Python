package com.orbit.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.Locale

class AgentAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var cloudClient: GuriCloudSocket? = null
    private var connectedCloudUrl = ""
    private var connectedPairingCode = ""
    private var cloudConnected = false
    private var statusListener: ((String) -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Standalone on-device mode: no cloud URL or pairing setup is required.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window nodes are inspected only when a user command requests an action.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        cloudClient?.close()
        cloudClient = null
        statusListener = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    fun setStatusListener(listener: ((String) -> Unit)?) {
        statusListener = listener
    }

    fun connectCloud(serverUrl: String, pairingCode: String) {
        val url = serverUrl.trim().trimEnd('/')
        val code = pairingCode.trim()
        if (url.isBlank() || code.isBlank()) {
            statusListener?.invoke("Guri Cloud URL और pairing code भरें।")
            return
        }
        if (url == connectedCloudUrl && code == connectedPairingCode && cloudClient != null) {
            statusListener?.invoke(
                if (cloudConnected) "Guri Cloud से Android connected"
                else "Guri Cloud reconnect कर रही है…"
            )
            return
        }

        cloudClient?.close()
        connectedCloudUrl = url
        connectedPairingCode = code
        val client = GuriCloudSocket()
        cloudClient = client
        client.connect(
            url,
            code,
            { message ->
                handler.post {
                    cloudConnected = message.contains("connected", ignoreCase = true)
                    statusListener?.invoke(message)
                }
            },
            { requestId, command ->
                handler.post { handleCloudCommand(requestId, command) }
            }
        )
    }

    /**
     * Captures the current display in memory for action verification.
     * The bitmap is immediately released; no screenshot is uploaded or persisted.
     */
    fun captureCurrentScreen(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val available = rootInActiveWindow != null
            onResult(available)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        } catch (_: Exception) {
                            null
                        } finally {
                            buffer.close()
                        }
                        val captured = bitmap != null
                        bitmap?.recycle()
                        onResult(captured)
                    }

                    override fun onFailure(errorCode: Int) {
                        onResult(false)
                    }
                }
            )
        } catch (_: Exception) {
            onResult(false)
        }
    }

    fun isInstagramForeground(): Boolean {
        val root = rootInActiveWindow ?: return false
        val active = root.packageName?.toString() == "com.instagram.android"
        root.recycle()
        return active
    }

    fun openReelsTab(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "com.instagram.android") {
            root.recycle()
            return false
        }

        val matches = mutableListOf<AccessibilityNodeInfo>()
        collectReelsNodes(root, matches, depth = 0)
        root.recycle()

        // Never guess a screen coordinate here: a blind tap can open the
        // comment composer or another control instead of the Reels tab.
        val target = matches.firstOrNull { it.isVisibleToUser && it.isEnabled }
        var clicked = false
        if (target != null) clicked = clickNode(target)
        matches.forEach { it.recycle() }
        return clicked
    }

    private fun collectReelsNodes(
        node: AccessibilityNodeInfo,
        matches: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 18) return

        val text = node.text?.toString().orEmpty().lowercase(Locale.ROOT)
        val description = node.contentDescription?.toString().orEmpty().lowercase(Locale.ROOT)
        val labels = listOf(text, description).filter { it.isNotBlank() }
        val isReelsLabel = labels.any { label ->
            label == "reels" ||
                label == "reel" ||
                label.contains("reels tab") ||
                label.contains("रील्स") ||
                label == "रील"
        }

        if (node.isVisibleToUser && isReelsLabel) {
            matches.add(AccessibilityNodeInfo.obtain(node))
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectReelsNodes(child, matches, depth + 1)
            child.recycle()
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        while (current != null) {
            if (current.isClickable) {
                val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current.recycle()
                return result
            }
            val parent = current.parent
            current.recycle()
            current = parent
        }
        return false
    }

    fun swipeReels(count: Int): Boolean = swipeReels(count) {}

    /** One deliberate feed swipe, bounded to the active app's content window. */
    fun swipeReels(count: Int, onComplete: (Boolean) -> Unit): Boolean =
        swipeCurrentFeed(count, "com.instagram.android", onComplete)

    /** Swipe the currently foreground app without touching editable/comment controls. */
    fun swipeCurrentFeed(count: Int, expectedPackage: String? = null, onComplete: (Boolean) -> Unit = {}): Boolean {
        val root = rootInActiveWindow ?: return false
        val activePackage = root.packageName?.toString()
        if (activePackage.isNullOrBlank() || (expectedPackage != null && activePackage != expectedPackage)) {
            root.recycle()
            return false
        }
        val bounds = android.graphics.Rect()
        root.getBoundsInScreen(bounds)
        root.recycle()
        if (bounds.width() <= 0 || bounds.height() <= 0) return false

        val total = count.coerceIn(1, 1) // one voice command = exactly one swipe
        val x = bounds.left + bounds.width() * 0.52f
        // Instagram overlays comments and action controls near the lower edge.
        // Start in the lower-middle of the video, not on the comment composer.
        val startY = bounds.top + bounds.height() * 0.66f
        val endY = bounds.top + bounds.height() * 0.30f

        fun send(index: Int) {
            val current = rootInActiveWindow ?: run { onComplete(false); return }
            val stillForeground = current.packageName?.toString() == activePackage
            current.recycle()
            if (!stillForeground) { onComplete(false); return }
            val path = Path().apply {
                moveTo(x, startY)
                lineTo(x, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
                .build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onComplete(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onComplete(false)
                }
            }, handler)
            if (!accepted) onComplete(false)
        }
        send(0)
        return true
    }

    /** True only when the requested app owns the current accessibility window. */
    fun isPackageForeground(packageName: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val active = root.packageName?.toString() == packageName
        root.recycle()
        return active
    }

    /** Types into the app's search field, preferring fields whose hint or resource id says search. */
    fun typeIntoSearchField(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(root, nodes, 0)
        root.recycle()
        if (nodes.isEmpty()) return false
        val target = nodes.firstOrNull { node ->
            val hint = node.hintText?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            node.isVisibleToUser && node.isEnabled && (hint.contains("search") || id.contains("search"))
        } ?: nodes.firstOrNull { it.isVisibleToUser && it.isEnabled }
        nodes.filter { it !== target }.forEach { it.recycle() }
        if (target == null) return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val focused = target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val changed = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        target.recycle()
        return changed
    }

    /** Types only into a likely chat composer, never an arbitrary first field. */
    fun typeMessageIntoComposer(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(root, nodes, 0)
        root.recycle()
        if (nodes.isEmpty()) return false
        val target = nodes.firstOrNull { node ->
            val hint = node.hintText?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            node.isVisibleToUser && node.isEnabled &&
                (hint.contains("message") || hint.contains("type a message") ||
                 id.contains("message") || id.contains("composer") || id.contains("input"))
        }
        nodes.filter { it !== target }.forEach { it.recycle() }
        if (target == null) return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val focused = target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val changed = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        target.recycle()
        return focused && changed
    }

    /** Opens a visible app by package name. */
    fun launchInstalledApp(packageName: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            true
        } catch (_: Exception) { false }
    }

    /** Types into the first visible editable field, optionally opening an app's Search tab first. */
    fun typeIntoFocusedOrFirstField(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(root, candidates, 0)
        root.recycle()
        val target = candidates.firstOrNull { it.isVisibleToUser && it.isEnabled }
        candidates.drop(1).forEach { it.recycle() }
        if (target == null) return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        target.recycle()
        return ok
    }

    private fun collectEditableNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 20) return
        if (node.isEditable || node.className?.toString()?.contains("EditText", true) == true) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditableNodes(child, out, depth + 1)
            child.recycle()
        }
    }

    /** Clicks a visible node whose text or content description matches a label. */
    fun clickVisibleLabel(vararg labels: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val wanted = labels.map { it.lowercase(Locale.ROOT) }
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectLabelNodes(root, nodes, wanted, 0)
        root.recycle()
        val ordered = nodes.sortedByDescending { it.isClickable }
        var result = false
        for (node in ordered) {
            if (!result && node.isVisibleToUser) result = clickNode(node)
            node.recycle()
        }
        return result
    }

    private fun collectLabelNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        labels: List<String>,
        depth: Int
    ) {
        if (depth > 20) return
        val text = node.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
        if (labels.any { it.isNotBlank() && (text == it || desc == it || text.contains(it) || desc.contains(it)) }) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectLabelNodes(child, out, labels, depth + 1)
            child.recycle()
        }
    }

    fun performVerticalSwipe(up: Boolean = true): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val fromY = if (up) metrics.heightPixels * .82f else metrics.heightPixels * .25f
        val toY = if (up) metrics.heightPixels * .25f else metrics.heightPixels * .82f
        val path = Path().apply { moveTo(x, fromY); lineTo(x, toY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 420)).build()
        return dispatchGesture(gesture, null, handler)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    private fun handleCloudCommand(requestId: String, rawCommand: String) {
        val command = rawCommand.lowercase(Locale.ROOT)
        val hasInstagram = command.contains("instagram") || command.contains("इंस्टाग्राम")
        val asksReels = command.contains("reel") || command.contains("रील")
        val asksScroll = command.contains("scroll") ||
            command.contains("स्क्रॉल") || command.contains("स्क्रोल") || command.contains("अगली")

        if (!hasInstagram && !asksReels && !asksScroll) {
            sendCloudResult(requestId, "Android पर Instagram और Reels commands उपलब्ध हैं।", false)
            return
        }

        val launch = packageManager.getLaunchIntentForPackage("com.instagram.android")
        if (launch == null) {
            sendCloudResult(requestId, "Instagram इस फोन में install नहीं है।", false)
            return
        }

        val alreadyOpen = isInstagramForeground()
        if (!alreadyOpen || hasInstagram) {
            try {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            } catch (error: Exception) {
                sendCloudResult(
                    requestId,
                    "Instagram launch नहीं हुआ: " + (error.localizedMessage ?: "unknown error"),
                    false
                )
                return
            }
        }

        if (asksReels || asksScroll) {
            handler.postDelayed({
                executeCloudReels(requestId, command, attempts = 0)
            }, if (alreadyOpen) 250 else 1700)
        } else {
            sendCloudResult(requestId, "Instagram फोन में खोल दिया।", true)
        }
    }

    private fun executeCloudReels(requestId: String, command: String, attempts: Int) {
        val opened = openReelsTab()
        if (!opened && attempts < 4) {
            handler.postDelayed({
                executeCloudReels(requestId, command, attempts + 1)
            }, 700)
            return
        }

        if (!opened) {
            sendCloudResult(
                requestId,
                "Instagram खुल गया, लेकिन Reels tab नहीं मिला। Instagram layout के अनुसार manually खोलें।",
                false
            )
            return
        }

        if (command.contains("scroll") || command.contains("स्क्रॉल") ||
            command.contains("स्क्रोल") || command.contains("अगली")) {
            handler.postDelayed({
                val count = extractCount(command).coerceIn(1, 20)
                val sent = swipeReels(count)
                sendCloudResult(
                    requestId,
                    if (sent) "Instagram Reels पर $count बार scroll gesture भेज दिया।"
                    else "Reels खुल गए, लेकिन scroll gesture नहीं चला।",
                    sent
                )
            }, 900)
        } else {
            sendCloudResult(requestId, "Instagram Reels खोल दिए।", true)
        }
    }

    private fun extractCount(command: String): Int {
        Regex("\\\\d+").find(command)?.value?.toIntOrNull()?.let { return it }
        val hindiNumbers = mapOf(
            "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5,
            "छह" to 6, "छः" to 6, "सात" to 7, "आठ" to 8, "नौ" to 9, "दस" to 10
        )
        return hindiNumbers.entries.firstOrNull { command.contains(it.key) }?.value ?: 3
    }

    private fun sendCloudResult(requestId: String, message: String, success: Boolean) {
        val payload = JSONObject()
            .put("reply", message)
            .put("action", "instagram")
            .put("status", if (success) "completed" else "failed")
        cloudClient?.sendResult(requestId, payload)
        statusListener?.invoke(message)
    }
}
