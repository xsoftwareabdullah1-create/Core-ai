package ai.ultron.mobile
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
class UltronAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); current = this }
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    fun visibleText(): String {
        val out = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || out.size > 250) return
            (n.text ?: n.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return out.distinct().joinToString("\n").ifBlank { "No readable text. Some apps hide accessibility content." }
    }
    fun tapText(text: String): Boolean {
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            val value = (n.text ?: n.contentDescription)?.toString().orEmpty()
            if (value.equals(text, true) && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        val node = find(rootInActiveWindow) ?: return false
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return (target ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    fun global(action: String): Boolean = when (action) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        else -> false
    }
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 450)).build()
        return dispatchGesture(gesture, null, null)
    }
    companion object { @Volatile var current: UltronAccessibilityService? = null }
}
