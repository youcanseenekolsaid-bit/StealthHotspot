package com.mrd.stealthhotspot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * Invisible activity that opens Wi-Fi settings and finishes immediately.
 * Used when long-pressing the fake Wi-Fi Quick Settings tile.
 */
class WifiSettingsRedirect : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
    }
}
