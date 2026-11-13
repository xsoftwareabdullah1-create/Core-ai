package ai.ultron.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.Looper

/**
 * User-enabled Android UI automation bridge.
 * Actions run only while the user has explicitly enabled this Accessibility Service.
 */
class UltronAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); current = this }
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (current === this) current = null
        return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun visibleText(): String {
        val out = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 35 || out.size >= 300) return
            if (node.isVisibleToUser) {
                val label = sequenceOf(node.text?.toString(), node.contentDescription?.toString())
                    .filterNotNull().map { it.trim() }.firstOrNull { it.isNotBlank() }
                if (label != null) {
                    val r = Rect(); node.getBoundsInScreen(r)
                    out.add("${label} [${r.centerX()},${r.centerY()}]${if (node.isClickable) " (clickable)" else ""}${if (node.isEditable) " (editable)" else ""}")
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(rootInActiveWindow, 0)
        return out.distinct().joinToString("\n").ifBlank { "No readable foreground UI. The app may hide accessibility nodes." }
    }

    fun tapText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        fun find(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 35 || !node.isVisibleToUser) return null
            val value = sequenceOf(node.text?.toString(), node.contentDescription?.toString())
                .filterNotNull().any { it.trim().equals(text.trim(), ignoreCase = true) }
            if (value) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target != null) return target
            }
            for (i in 0 until node.childCount) find(node.getChild(i), depth + 1)?.let { return it }
            return null
        }
        return find(root, 0)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    fun tapCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 90L)).build(), null, Handler(Looper.getMainLooper()))
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 420L): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(60L, 5000L))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, Handler(Looper.getMainLooper()))
    }

    fun scroll(direction: String): Boolean {
        val down = direction.equals("down", true) || direction.equals("right", true)
        val vertical = direction.equals("down", true) || direction.equals("up", true)
        val root = rootInActiveWindow
        fun findScrollable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 35) return null
            if (node.isVisibleToUser && node.isScrollable) return node
            for (i in 0 until node.childCount) findScrollable(node.getChild(i), depth + 1)?.let { return it }
            return null
        }
        val scrollNode = findScrollable(root, 0)
        if (scrollNode != null && scrollNode.performAction(
                if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            )) return true
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f; val cy = dm.heightPixels / 2f
        return when (direction.lowercase()) {
            "down" -> swipe(cx, cy + dm.heightPixels * .18f, cx, cy - dm.heightPixels * .18f)
            "up" -> swipe(cx, cy - dm.heightPixels * .18f, cx, cy + dm.heightPixels * .18f)
            "left" -> swipe(cx - dm.widthPixels * .18f, cy, cx + dm.widthPixels * .18f, cy)
            "right" -> swipe(cx + dm.widthPixels * .18f, cy, cx - dm.widthPixels * .18f, cy)
            else -> false
        }
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null || !target.isEditable) target = findEditable(root, 0)
        if (target == null || !target.isEnabled) return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 35) return null
        if (node.isVisibleToUser && node.isEnabled && node.isEditable) return node
        for (i in 0 until node.childCount) findEditable(node.getChild(i), depth + 1)?.let { return it }
        return null
    }

    fun global(action: String): Boolean = when (action.lowercase()) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "power_dialog" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        "lock" -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        else -> false
    }

    companion object { @Volatile var current: UltronAccessibilityService? = null }
}
