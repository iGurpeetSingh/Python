package com.orbit.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AgentAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: AgentAccessibilityService? = null }
    private val handler = Handler(Looper.getMainLooper())
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    private fun isInstagramForeground(): Boolean =
        rootInActiveWindow?.packageName?.toString() == "com.instagram.android"

    fun openReelsTab(): Boolean {
        if (!isInstagramForeground()) return false
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText("Reels")
        val target = nodes.firstOrNull { it.isVisibleToUser && it.isClickable }
            ?: nodes.firstOrNull { it.isVisibleToUser }
        val clicked = target?.let { clickNode(it) } ?: false
        nodes.forEach { it.recycle() }
        root.recycle()
        return clicked
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
        fun swipeOnce() {
            val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 420))
                .build()
            dispatchGesture(gesture, null, null)
        }
        repeat(count.coerceIn(1, 20)) { index ->
            handler.postDelayed({ if (isInstagramForeground()) swipeOnce() }, index * 850L)
        }
        return true
    }
}
