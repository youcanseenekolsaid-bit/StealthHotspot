package com.mrd.stealthhotspot

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

class PreferencesManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "stealth_hotspot_prefs"
        private const val KEY_APP_PASSWORD_HASH = "app_password_hash"
        private const val KEY_NETWORK_NAME = "network_name"
        private const val KEY_NETWORK_PASSPHRASE = "network_passphrase"
        private const val KEY_HOTSPOT_ACTIVE = "hotspot_active"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_AUTO_WIFI = "auto_wifi_enabled"
        private const val KEY_AUTO_DATA = "auto_data_enabled"
        private const val KEY_PROXY_PORT = "proxy_port"

        private const val DEFAULT_NETWORK_NAME = "MyNetwork"
        private const val DEFAULT_NETWORK_PASSPHRASE = "12345678"
        private const val DEFAULT_PROXY_PORT = 8080
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- App Password ---

    val isFirstLaunch: Boolean
        get() = prefs.getBoolean(KEY_FIRST_LAUNCH, true)

    fun setAppPassword(password: String) {
        prefs.edit()
            .putString(KEY_APP_PASSWORD_HASH, hashPassword(password))
            .putBoolean(KEY_FIRST_LAUNCH, false)
            .apply()
    }

    fun verifyPassword(password: String): Boolean {
        val storedHash = prefs.getString(KEY_APP_PASSWORD_HASH, null) ?: return false
        return hashPassword(password) == storedHash
    }

    // --- Network Settings ---

    var networkName: String
        get() = prefs.getString(KEY_NETWORK_NAME, DEFAULT_NETWORK_NAME) ?: DEFAULT_NETWORK_NAME
        set(value) = prefs.edit().putString(KEY_NETWORK_NAME, value).apply()

    var networkPassphrase: String
        get() = prefs.getString(KEY_NETWORK_PASSPHRASE, DEFAULT_NETWORK_PASSPHRASE)
            ?: DEFAULT_NETWORK_PASSPHRASE
        set(value) = prefs.edit().putString(KEY_NETWORK_PASSPHRASE, value).apply()

    // --- Hotspot State ---

    var isHotspotActive: Boolean
        get() = prefs.getBoolean(KEY_HOTSPOT_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_HOTSPOT_ACTIVE, value).apply()

    // --- Proxy ---

    var proxyPort: Int
        get() = prefs.getInt(KEY_PROXY_PORT, DEFAULT_PROXY_PORT)
        set(value) = prefs.edit().putInt(KEY_PROXY_PORT, value).apply()

    // --- Auto Reconnect ---

    var isAutoWifiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_WIFI, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_WIFI, value).apply()

    var isAutoDataEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DATA, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DATA, value).apply()

    // --- Helpers ---

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
