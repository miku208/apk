package com.arisu.companion.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Restarts the floating pet after reboot when the user opted in AND the
 * overlay permission is still granted. Android may still block the start on
 * some OEMs — we never work around platform restrictions.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        try {
            val prefs = context.getSharedPreferences("arisu_settings", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_on_boot", false)
            if (!autoStart) return
            if (!Settings.canDrawOverlays(context)) return
            context.startForegroundService(Intent(context, FloatingPetService::class.java))
        } catch (e: Exception) {
            // Background start restrictions; the user can always open the app instead.
            Log.w("BootReceiver", "auto-start rejected by platform", e)
        }
    }
}
