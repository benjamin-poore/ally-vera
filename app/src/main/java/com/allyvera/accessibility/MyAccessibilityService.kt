package com.allyvera.accessibility  // replace with your package name

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class MyAccessibilityService : AccessibilityService() {

    override fun onCreate() {
        super.onCreate()
        Log.d("MyAccessibilityService", "Service created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We'll fill this later
        Log.d("MyAccessibilityService", "Event received: ${event?.eventType}")
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("MyAccessibilityService", "Service connected")
    }
}