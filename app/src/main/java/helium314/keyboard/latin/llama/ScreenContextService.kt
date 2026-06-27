package helium314.keyboard.latin.llama

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class ScreenContextService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}

    fun getScreenText(excludePackage: String): String {
        val sb = StringBuilder()
        val wins = windows ?: return ""
        for (win in wins) {
            if (win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                win.recycle()
                continue
            }
            val root = win.root
            if (root != null) {
                collectText(root, sb, excludePackage)
                root.recycle()
            }
            win.recycle()
        }
        return sb.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder, excludePackage: String) {
        if (node.packageName?.toString() == excludePackage) return
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) sb.append(text).append('\n')
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectText(child, sb, excludePackage)
                child.recycle()
            }
        }
    }

    companion object {
        @Volatile var instance: ScreenContextService? = null
    }
}
