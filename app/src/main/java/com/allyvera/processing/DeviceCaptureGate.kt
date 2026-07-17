package com.allyvera.processing

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * Shared gate for whether a screenshot should be taken. Capture must not run while the
 * display is off or the keyguard / lock screen is showing.
 */
object DeviceCaptureGate {

    fun canCapture(context: Context): Boolean {
        val appContext = context.applicationContext
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        if (powerManager?.isInteractive != true) return false

        val keyguardManager = appContext.getSystemService(KeyguardManager::class.java)
        if (keyguardManager?.isKeyguardLocked == true) return false

        return true
    }
}
