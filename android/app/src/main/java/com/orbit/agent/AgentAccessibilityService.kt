package com.orbit.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class AgentAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The active window is queried only when a user command requests an action.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
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

        val sorted = matches.sortedByDescending { it.isClickable }
        var clicked = false
        for (node in sorted) {
            if (!clicked && node.isVisibleToUser) {
                clicked = clickNode(node)
            }
            node.recycle()
        }
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

    fun swipeReels(count: Int): Boolean {
        if (!isInstagramForeground()) return false

        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * .82f
        val endY = metrics.heightPixels * .24f
        var firstAccepted = false

        repeat(count.coerceIn(1, 20)) { index ->
            handler.postDelayed({
                if (!isInstagramForeground()) return@postDelayed

                val path = Path().apply {
                    moveTo(x, startY)
                    lineTo(x, endY)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 430))
                    .build()
                val accepted = dispatchGesture(gesture, null, null)
                if (index == 0) firstAccepted = accepted
            }, index * 850L)
        }

        // true means the first gesture was scheduled for an Instagram foreground window.
        return true
    }
}
