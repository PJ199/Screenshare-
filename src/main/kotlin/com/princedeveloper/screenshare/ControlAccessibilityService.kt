package com.princedeveloper.screenshare

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ControlAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ControlAccessibilityService? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { instance = null }
    override fun onDestroy() { instance = null; super.onDestroy() }

    // --- 1. TAP (Improved) ---
    fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val builder = GestureDescription.Builder()
        // Very short duration for a crisp click
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        dispatchGesture(builder.build(), null, null)
    }

    // --- 2. FAST SWIPE (For Navigation) ---
    // Duration: 200ms (Fast flick)
    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 200))
        dispatchGesture(builder.build(), null, null)
    }

    // --- 3. SLOW SCROLL (For Reading) ---
    // Duration: 1000ms (Slow drag)
    fun performScroll(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 1000))
        dispatchGesture(builder.build(), null, null)
    }

    // --- SIDE SWIPES ---
    fun swipeLeft() {
        val m = resources.displayMetrics
        performSwipe(m.widthPixels * 0.8f, m.heightPixels * 0.5f, m.widthPixels * 0.2f, m.heightPixels * 0.5f)
    }

    fun swipeRight() {
        val m = resources.displayMetrics
        performSwipe(m.widthPixels * 0.2f, m.heightPixels * 0.5f, m.widthPixels * 0.8f, m.heightPixels * 0.5f)
    }

    // --- GLOBAL ACTIONS ---
    fun performBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    // --- TEXT INJECTION ---
    fun injectText(text: String) {
        val rootNode = rootInActiveWindow ?: return
        val focusNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusNode != null) {
            val arguments = android.os.Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
    }
}

