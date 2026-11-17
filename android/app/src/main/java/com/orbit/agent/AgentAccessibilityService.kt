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

    /**
     * Opens WhatsApp's own Search control. First detect an already expanded search box;
     * otherwise click a visible Search action by its label or resource id.
     */
    /** True when WhatsApp's expanded toolbar Search input is actually present. */
    fun isWhatsAppSearchFieldVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "com.whatsapp") {
            root.recycle()
            return false
        }
        val rootBounds = android.graphics.Rect()
        root.getBoundsInScreen(rootBounds)
        val editables = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(root, editables, 0)
        root.recycle()
        val visible = editables.any { node ->
            val hint = node.hintText?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val text = node.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val inTopSearchArea = rootBounds.height() > 0 &&
                bounds.centerY() < rootBounds.top + rootBounds.height() * 0.45f
            val hasSearchIdentity =
                id.contains("search") || hint.contains("search") || hint.contains("खोज") ||
                text.contains("ask meta ai") || desc.contains("ask meta ai") ||
                cls.contains("autocompletetextview") || cls.contains("searchview") ||
                (node.isFocused && inTopSearchArea)
            node.isVisibleToUser && node.isEnabled && inTopSearchArea && hasSearchIdentity
        }
        editables.forEach { it.recycle() }
        return visible
    }

    /**
     * Opens WhatsApp Search using stable Android view IDs first, then accessibility
     * labels. If Search is hidden in the overflow menu, open that menu; the caller
     * retries after the menu has rendered and then selects its Search item.
     */
    fun openWhatsAppSearch(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "com.whatsapp") {
            root.recycle()
            return false
        }
        if (isWhatsAppSearchFieldVisible()) {
            root.recycle()
            return true
        }

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectWhatsAppSearchControls(root, candidates, 0)
        val ranked = candidates
            .filter { it.isVisibleToUser && it.isEnabled }
            .sortedByDescending { node ->
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
                val text = node.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
                val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
                when {
                    text.contains("ask meta ai") || desc.contains("ask meta ai") -> 130
                    (text.contains("search") && (text.contains("meta ai") || text.contains("chats"))) ||
                        (desc.contains("search") && (desc.contains("meta ai") || desc.contains("chats"))) -> 125
                    id.endsWith("menuitem_search") || id.contains("menuitem_search") -> 100
                    id.contains("search") -> 90
                    text == "search" || desc == "search" || text == "खोजें" || desc == "खोजें" -> 80
                    text.contains("search chats") || desc.contains("search chats") -> 70
                    else -> 10
                } + if (node.isClickable) 5 else 0
            }
        var clicked = false
        var metaSearchBounds: android.graphics.Rect? = null
        for (candidate in ranked) {
            val label = (
                candidate.text?.toString().orEmpty() + " " +
                candidate.contentDescription?.toString().orEmpty()
            ).lowercase(Locale.ROOT)
            if (label.contains("ask meta ai") || (label.contains("meta ai") && label.contains("search"))) {
                metaSearchBounds = android.graphics.Rect().also { candidate.getBoundsInScreen(it) }
            }
            if (clickNode(candidate)) {
                clicked = true
                break
            }
        }
        candidates.forEach { it.recycle() }
        root.recycle()
        if (clicked) return true

        // Some WhatsApp builds expose the Meta AI/Search pill as a non-clickable
        // accessibility child while its visual parent handles touch input. Tap
        // the center of the exact labelled pill instead of opening the overflow menu.
        val pill = metaSearchBounds
        if (pill != null && pill.width() > 0 && pill.height() > 0) {
            val x = pill.exactCenterX()
            val y = pill.exactCenterY()
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 90))
                .build()
            if (dispatchGesture(gesture, null, handler)) return true
        }

        // Search may be inside the top-right overflow menu on older WhatsApp layouts.
        val freshRoot = rootInActiveWindow ?: return false
        if (freshRoot.packageName?.toString() != "com.whatsapp") {
            freshRoot.recycle()
            return false
        }
        val overflow = mutableListOf<AccessibilityNodeInfo>()
        collectWhatsAppOverflowControls(freshRoot, overflow, 0)
        freshRoot.recycle()
        val target = overflow.firstOrNull { it.isVisibleToUser && it.isEnabled }
        overflow.filter { it !== target }.forEach { it.recycle() }
        if (target == null) return false
        val opened = clickNode(target)
        target.recycle()
        return opened
    }

    private fun collectWhatsAppSearchControls(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 24) return
        val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        // New WhatsApp Android UI exposes a persistent pill labelled
        // "Ask Meta AI or Search" on the Chats screen; it is not an EditText
        // until tapped and may have no resource-id containing "search".
        val hasMetaSearchLabel =
            text.contains("ask meta ai") || desc.contains("ask meta ai") ||
            (text.contains("meta ai") && text.contains("search")) ||
            (desc.contains("meta ai") && desc.contains("search"))
        val isSearch = hasMetaSearchLabel ||
            id.contains("menuitem_search") || id.contains("search") ||
            text == "search" || desc == "search" ||
            text == "खोजें" || desc == "खोजें" ||
            text.contains("search chats") || desc.contains("search chats")
        if (node.isVisibleToUser && isSearch) out.add(AccessibilityNodeInfo.obtain(node))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectWhatsAppSearchControls(child, out, depth + 1)
            child.recycle()
        }
    }

    private fun collectWhatsAppOverflowControls(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 24) return
        val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val isOverflow = id.contains("menuitem_overflow") || id.contains("overflow") ||
            desc.contains("more options") || text.contains("more options") ||
            desc.contains("अधिक विकल्प") || text.contains("अधिक विकल्प")
        if (node.isVisibleToUser && isOverflow) out.add(AccessibilityNodeInfo.obtain(node))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectWhatsAppOverflowControls(child, out, depth + 1)
            child.recycle()
        }
    }

    /** Types only into a verified WhatsApp Search field, including AutoCompleteTextView variants. */
    fun typeIntoSearchField(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "com.whatsapp") {
            root.recycle()
            return false
        }
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(root, nodes, 0)
        root.recycle()
        val rootBounds = android.graphics.Rect()
        val freshRoot = rootInActiveWindow
        freshRoot?.getBoundsInScreen(rootBounds)
        freshRoot?.recycle()
        val target = nodes.firstOrNull { node ->
            val hint = node.hintText?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val text = node.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val className = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val inTopSearchArea = rootBounds.height() > 0 &&
                bounds.centerY() < rootBounds.top + rootBounds.height() * 0.45f
            val hasSearchIdentity =
                hint.contains("search") || hint.contains("खोज") ||
                text.contains("ask meta ai") || desc.contains("ask meta ai") ||
                desc.contains("search") || desc.contains("खोज") ||
                id.contains("search") ||
                className.contains("autocompletetextview") || className.contains("searchview") ||
                (node.isFocused && inTopSearchArea)
            node.isVisibleToUser && node.isEnabled && inTopSearchArea && hasSearchIdentity
        }
        nodes.filter { it !== target }.forEach { it.recycle() }
        if (target == null) return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        // Focus/click first, then refresh the accessibility tree: WhatsApp can
        // replace the pill with a new EditText after expansion.
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var changed = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        target.recycle()
        if (changed) return true

        val refreshedRoot = rootInActiveWindow ?: return false
        if (refreshedRoot.packageName?.toString() != "com.whatsapp") {
            refreshedRoot.recycle()
            return false
        }
        val refreshed = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(refreshedRoot, refreshed, 0)
        val refreshedBounds = android.graphics.Rect()
        refreshedRoot.getBoundsInScreen(refreshedBounds)
        refreshedRoot.recycle()
        val retryTarget = refreshed.firstOrNull { node ->
            val hint = node.hintText?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val cls = node.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            node.isVisibleToUser && node.isEnabled &&
                refreshedBounds.height() > 0 &&
                bounds.centerY() < refreshedBounds.top + refreshedBounds.height() * 0.45f &&
                (id.contains("search") || hint.contains("search") || hint.contains("खोज") ||
                 cls.contains("autocompletetextview") || cls.contains("searchview"))
        }
        refreshed.filter { it !== retryTarget }.forEach { it.recycle() }
        if (retryTarget == null) return false
        retryTarget.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        changed = retryTarget.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        retryTarget.recycle()
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

    /**
     * Returns unique visible WhatsApp search-result labels beginning with the spoken query.
     * The caller must ask the user when more than one distinct label is present.
     */
    fun findContactSearchResults(query: String): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != "com.whatsapp") {
            root.recycle()
            return emptyList()
        }
        val names = linkedSetOf<String>()
        val normalizedQuery = normalizeWhatsAppLabel(query)
        collectContactLabels(root, names, normalizedQuery, 0)
        root.recycle()
        return names.toList().sortedBy { it.lowercase(Locale.ROOT) }
    }

    private fun collectContactLabels(
        node: AccessibilityNodeInfo,
        out: MutableSet<String>,
        query: String,
        depth: Int
    ) {
        if (depth > 20 || query.isBlank()) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            val candidates = listOf(text, description).distinct()
            candidates.forEach { label ->
                if (label.isNotBlank()) {
                    // Ignore preview lines and compare names without leading symbols/emojis.
                    val clean = label.lineSequence().firstOrNull()?.trim().orEmpty()
                    val normalized = normalizeWhatsAppLabel(clean)
                    if (normalized.isNotBlank() && normalized.startsWith(query) && clean.length <= 80) out.add(clean)
                }
            }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectContactLabels(child, out, query, depth + 1)
            child.recycle()
        }
    }

    private fun normalizeWhatsAppLabel(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Selects only a visible WhatsApp search result matching the requested contact name. */
    fun clickContactSearchResult(name: String, occurrence: Int = 1): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "com.whatsapp") {
            root.recycle()
            return false
        }
        val matches = mutableListOf<AccessibilityNodeInfo>()
        collectContactMatches(root, matches, normalizeWhatsAppLabel(name), 0)
        root.recycle()
        val ordered = matches
            .filter { it.isVisibleToUser && it.isEnabled }
            .distinctBy { node ->
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                "${node.text}:${bounds.top}:${bounds.left}"
            }
            .sortedBy {
                val bounds = android.graphics.Rect()
                it.getBoundsInScreen(bounds)
                bounds.top
            }
        val selected = ordered.getOrNull((occurrence - 1).coerceAtLeast(0))
        ordered.filter { it !== selected }.forEach { it.recycle() }
        if (selected == null) return false
        val clicked = clickNode(selected)
        selected.recycle()
        return clicked
    }

    private fun collectContactMatches(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        query: String,
        depth: Int
    ) {
        if (depth > 20 || query.isBlank()) return
        val text = normalizeWhatsAppLabel(node.text?.toString()?.lineSequence()?.firstOrNull().orEmpty())
        val desc = normalizeWhatsAppLabel(node.contentDescription?.toString()?.lineSequence()?.firstOrNull().orEmpty())
        val matches = text == query || desc == query
        if (matches && node.isVisibleToUser) out.add(AccessibilityNodeInfo.obtain(node))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectContactMatches(child, out, query, depth + 1)
            child.recycle()
        }
    }

    /** Clicks WhatsApp's explicit Send control; never taps a guessed screen coordinate. */
    fun clickWhatsAppSendButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "com.whatsapp") {
            root.recycle()
            return false
        }
        val matches = mutableListOf<AccessibilityNodeInfo>()
        collectSendNodes(root, matches, 0)
        root.recycle()
        val target = matches
            .filter { it.isVisibleToUser && it.isEnabled }
            .maxByOrNull { node ->
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                bounds.right
            }
        matches.filter { it !== target }.forEach { it.recycle() }
        if (target == null) return false
        val clicked = clickNode(target)
        target.recycle()
        return clicked
    }

    private fun collectSendNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 20) return
        val text = node.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        if (node.isVisibleToUser &&
            (text == "send" || desc == "send" || text == "भेजें" || desc == "भेजें" ||
             id.contains("send"))) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectSendNodes(child, out, depth + 1)
            child.recycle()
        }
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

    /** Locks the device screen through Android's supported accessibility global action. */
    fun lockPhoneScreen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }


    // ---- ChatGPT image prompt batch (on-device Accessibility workflow) ----
    private val imageBatchPrompts = mutableListOf<String>()
    private var imageBatchIndex = 0
    private var imageBatchRunning = false
    private var imageBatchPaused = false
    private var imageBatchPollAttempts = 0
    private var imageBatchBaselineImages = 0
    private var imageBatchStablePolls = 0
    private var imageBatchStatus: ((String) -> Unit)? = null
    private val chatGptPackage = "com.openai.chatgpt"

    fun startChatGptImageBatch(prompts: List<String>, listener: (String) -> Unit): Boolean {
        if (prompts.isEmpty() || prompts.size > 50 || prompts.any { it.isBlank() }) {
            listener("1 से 50 तक non-empty prompts डालें।")
            return false
        }
        if (imageBatchRunning) {
            listener("एक Image Batch पहले से चल रही है।")
            return false
        }
        val launch = packageManager.getLaunchIntentForPackage(chatGptPackage)
        if (launch == null) {
            listener("ChatGPT App इस फोन में install नहीं है। पहले Play Store से ChatGPT install करें।")
            return false
        }
        imageBatchPrompts.clear()
        imageBatchPrompts.addAll(prompts)
        imageBatchIndex = 0
        imageBatchRunning = true
        imageBatchPaused = false
        imageBatchStatus = listener
        listener("ChatGPT खोल रही हूँ… " + prompts.size + " prompts queue में हैं।")
        if (!isPackageForeground(chatGptPackage)) {
            try {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            } catch (error: Exception) {
                imageBatchRunning = false
                listener("ChatGPT नहीं खुल पाया: " + (error.localizedMessage ?: "unknown error"))
                return false
            }
        }
        handler.postDelayed({ sendNextChatGptPrompt(0) }, 2200)
        return true
    }

    fun pauseChatGptImageBatch() {
        if (imageBatchRunning) {
            imageBatchPaused = true
            imageBatchStatus?.invoke("Batch pause है। मौजूदा generation को बीच में नहीं रोका गया।")
        }
    }

    fun resumeChatGptImageBatch() {
        if (!imageBatchRunning) {
            imageBatchStatus?.invoke("कोई active batch नहीं है।")
            return
        }
        if (imageBatchPaused) {
            imageBatchPaused = false
            imageBatchStatus?.invoke("Batch फिर शुरू हो रही है…")
            handler.postDelayed({ sendNextChatGptPrompt(0) }, 500)
        }
    }

    fun cancelChatGptImageBatch() {
        imageBatchRunning = false
        imageBatchPaused = false
        handler.removeCallbacksAndMessages(null)
        imageBatchStatus?.invoke("Batch cancel कर दी गई। ChatGPT में पहले से भेजी गई generation जारी रह सकती है।")
        imageBatchPrompts.clear()
        imageBatchStatus = null
    }

    private fun sendNextChatGptPrompt(retries: Int) {
        if (!imageBatchRunning || imageBatchPaused) return
        if (imageBatchIndex >= imageBatchPrompts.size) {
            imageBatchRunning = false
            imageBatchStatus?.invoke("सभी " + imageBatchPrompts.size + " prompts पूरे हो गए।")
            imageBatchStatus = null
            return
        }
        if (!isPackageForeground(chatGptPackage)) {
            if (retries >= 30) {
                failChatGptBatch("ChatGPT स्क्रीन active नहीं हुई। ChatGPT खोलकर Resume दबाएँ।")
                return
            }
            imageBatchStatus?.invoke("ChatGPT स्क्रीन का इंतजार…")
            handler.postDelayed({ sendNextChatGptPrompt(retries + 1) }, 1000)
            return
        }
        val root = rootInActiveWindow ?: run {
            handler.postDelayed({ sendNextChatGptPrompt(retries + 1) }, 1000)
            return
        }
        val editables = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(root, editables, 0)
        root.recycle()
        val composer = editables.firstOrNull { node ->
            val hint = node.hintText?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
            node.isVisibleToUser && node.isEnabled &&
                (id.contains("prompt") || id.contains("composer") ||
                 hint.contains("message") || hint.contains("prompt") ||
                 desc.contains("message") || desc.contains("prompt") ||
                 node.isEditable)
        }
        editables.filter { it !== composer }.forEach { it.recycle() }
        if (composer == null) {
            if (retries >= 30) {
                failChatGptBatch("ChatGPT का message box नहीं मिला। Login करें और नई chat खोलें, फिर Resume करें।")
            } else handler.postDelayed({ sendNextChatGptPrompt(retries + 1) }, 1000)
            return
        }
        val prompt = imageBatchPrompts[imageBatchIndex]
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt)
        }
        val focused = composer.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val typed = composer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        composer.recycle()
        if (!focused || !typed) {
            failChatGptBatch("Prompt " + (imageBatchIndex + 1) + " message box में नहीं लिखा जा सका।")
            return
        }
        handler.postDelayed({
            val send = findChatGptActionNode("send", "send message", "भेजें", "भेजो")
            if (send == null || !clickNode(send)) {
                send?.recycle()
                failChatGptBatch("Prompt लिखा गया, लेकिन Send button नहीं मिला। ChatGPT स्क्रीन जाँचें।")
                return@postDelayed
            }
            send.recycle()
            imageBatchBaselineImages = countChatGptImageNodes()
            imageBatchPollAttempts = 0
            imageBatchStablePolls = 0
            imageBatchStatus?.invoke("Prompt " + (imageBatchIndex + 1) + "/" + imageBatchPrompts.size + " भेज दिया। Image बनने का इंतजार…")
            handler.postDelayed({ pollChatGptImageCompletion() }, 2500)
        }, 500)
    }

    private fun pollChatGptImageCompletion() {
        if (!imageBatchRunning || imageBatchPaused) return
        if (!isPackageForeground(chatGptPackage)) {
            imageBatchStatus?.invoke("ChatGPT खुला रखें। Generation की जाँच रुकी हुई है।")
            handler.postDelayed({ pollChatGptImageCompletion() }, 2500)
            return
        }
        imageBatchPollAttempts++
        val imageCount = countChatGptImageNodes()
        val generating = hasChatGptGeneratingIndicator()
        val imageAppeared = imageCount > imageBatchBaselineImages
        if (imageAppeared && !generating) imageBatchStablePolls++ else imageBatchStablePolls = 0

        if (imageBatchStablePolls >= 3) {
            imageBatchStatus?.invoke("Prompt " + (imageBatchIndex + 1) + " की Image दिखाई दी।")
            imageBatchIndex++
            if (imageBatchPaused) return
            handler.postDelayed({ sendNextChatGptPrompt(0) }, 1800)
            return
        }
        if (imageBatchPollAttempts >= 240) {
            failChatGptBatch("Prompt " + (imageBatchIndex + 1) + " की Image की पुष्टि 10 मिनट में नहीं हुई। Queue रोक दी है; स्क्रीन जाँचकर Resume करें।")
            return
        }
        if (imageBatchPollAttempts % 8 == 0) {
            imageBatchStatus?.invoke("Prompt " + (imageBatchIndex + 1) + ": Image generation चल रही है…")
        }
        handler.postDelayed({ pollChatGptImageCompletion() }, 2500)
    }

    private fun countChatGptImageNodes(): Int {
        val root = rootInActiveWindow ?: return 0
        if (root.packageName?.toString() != chatGptPackage) {
            root.recycle()
            return 0
        }
        val count = countImageNodes(root, 0)
        root.recycle()
        return count
    }

    private fun countImageNodes(node: AccessibilityNodeInfo, depth: Int): Int {
        if (depth > 24) return 0
        val cls = node.className?.toString().orEmpty().lowercase(Locale.ROOT)
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        var count = if (node.isVisibleToUser &&
            (cls.contains("image") || desc.contains("generated image") ||
             desc.contains("view image") || id.contains("image")) &&
            bounds.width() >= 160 && bounds.height() >= 160) 1 else 0
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            count += countImageNodes(child, depth + 1)
            child.recycle()
        }
        return count
    }

    private fun hasChatGptGeneratingIndicator(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != chatGptPackage) {
            root.recycle()
            return false
        }
        val found = findChatGptGeneratingNode(root, 0)
        root.recycle()
        return found
    }

    private fun findChatGptGeneratingNode(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 24) return false
        val text = (node.text?.toString().orEmpty() + " " +
            node.contentDescription?.toString().orEmpty() + " " +
            node.viewIdResourceName.orEmpty()).lowercase(Locale.ROOT)
        if (node.isVisibleToUser &&
            (text.contains("stop generating") || text.contains("stop response") ||
             text.contains("creating image") || text.contains("generating image") ||
             text.contains("creating your image") || text.contains("image is being generated"))) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findChatGptGeneratingNode(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun findChatGptActionNode(vararg labels: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != chatGptPackage) {
            root.recycle()
            return null
        }
        val wanted = labels.map { it.lowercase(Locale.ROOT) }
        val matches = mutableListOf<AccessibilityNodeInfo>()
        collectLabelNodes(root, matches, wanted, 0)
        root.recycle()
        val target = matches.firstOrNull { it.isVisibleToUser && it.isEnabled }
        matches.filter { it !== target }.forEach { it.recycle() }
        return target
    }

    private fun failChatGptBatch(message: String) {
        imageBatchRunning = false
        imageBatchPaused = true
        imageBatchStatus?.invoke(message)
    }

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
