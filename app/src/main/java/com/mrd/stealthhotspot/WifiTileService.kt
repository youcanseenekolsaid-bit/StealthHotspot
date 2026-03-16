package com.mrd.stealthhotspot

import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Fake Wi-Fi Quick Settings Tile.
 *
 * Appears in the notification shade as "Wi Fi" with a Wi-Fi icon.
 * Toggles between active/inactive visually — does absolutely nothing.
 * Long press opens Wi-Fi settings (network list).
 * Pure camouflage.
 */
class WifiTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile(false)
    }

    override fun onStartListening() {
        super.onStartListening()
        val prefs = getSharedPreferences("tile_prefs", MODE_PRIVATE)
        val isActive = prefs.getBoolean("wifi_tile_active", false)
        updateTile(isActive)
    }

    override fun onClick() {
        super.onClick()
        // Toggle the visual state only — no real action
        val prefs = getSharedPreferences("tile_prefs", MODE_PRIVATE)
        val wasActive = prefs.getBoolean("wifi_tile_active", false)
        val newState = !wasActive
        prefs.edit().putBoolean("wifi_tile_active", newState).apply()
        updateTile(newState)
    }

    private fun updateTile(isActive: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Wi Fi"
        tile.updateTile()
    }
}
