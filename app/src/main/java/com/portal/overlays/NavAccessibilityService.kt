package com.portal.overlays

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * The only sanctioned way for a normal app to send a system-wide Back is via an AccessibilityService
 * and performGlobalAction(GLOBAL_ACTION_BACK). The floating back-button overlay calls into here.
 * Enable it from a computer:
 *   metavr adb shell settings put secure enabled_accessibility_services \
 *     com.portal.overlays/com.portal.overlays.NavAccessibilityService
 *   metavr adb shell settings put secure accessibility_enabled 1
 */
class NavAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Swipe down from the top edge to pull open Portal's Control Center panel. */
    private fun swipeDownFromTop() {
        val w = resources.displayMetrics.widthPixels
        val path = Path().apply {
            moveTo(w / 2f, 4f)
            lineTo(w / 2f, resources.displayMetrics.heightPixels * 0.6f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile var instance: NavAccessibilityService? = null
        val isEnabled: Boolean get() = instance != null
        fun back(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        fun home(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false
        fun recents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
        fun controlCenter(): Boolean {
            val s = instance ?: return false
            s.swipeDownFromTop(); return true
        }
        /** Lock the display immediately. GLOBAL_ACTION_LOCK_SCREEN is available on API 28+. */
        fun lock(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false
    }
}
