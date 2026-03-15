package com.mrd.stealthhotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restarts the HotspotService when the device boots
 * (if hotspot was previously active).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.samsung.android.intent.action.EMERGENCY_STATE_CHANGED"
        ) {
            val prefs = PreferencesManager(context)
            if (prefs.isHotspotActive) {
                Log.i("BootReceiver", "Device booted — restarting hotspot service")
                val serviceIntent = Intent(context, HotspotService::class.java).apply {
                    this.action = HotspotService.ACTION_START
                    putExtra(HotspotService.EXTRA_NETWORK_NAME, prefs.networkName)
                    putExtra(HotspotService.EXTRA_PASSPHRASE, prefs.networkPassphrase)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
