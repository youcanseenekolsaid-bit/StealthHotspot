package com.mrd.stealthhotspot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log

class HotspotService : Service() {

    companion object {
        const val TAG = "HotspotService"
        const val CHANNEL_ID = "stealth_hotspot_channel"
        const val CHANNEL_DATA_ID = "data_alert_channel"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_DATA_ID = 1002
        const val ACTION_START = "com.mrd.stealthhotspot.START"
        const val ACTION_STOP = "com.mrd.stealthhotspot.STOP"
        const val BROADCAST_STATUS = "com.mrd.stealthhotspot.STATUS_UPDATE"
        const val EXTRA_IS_ACTIVE = "is_active"
        const val EXTRA_NETWORK_NAME = "network_name"
        const val EXTRA_PASSPHRASE = "passphrase"
        const val EXTRA_CONNECTED_COUNT = "connected_count"

        const val WIFI_RESTART_DELAY_MS = 3 * 60 * 1000L   // 3 minutes
        const val DATA_RESTART_DELAY_MS = 2 * 60 * 1000L   // 2 minutes
    }

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var isGroupCreated = false
    private var connectedDeviceCount = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefsManager: PreferencesManager
    private val handler = Handler(Looper.getMainLooper())

    // Stored network config for re-creating group after Wi-Fi restart
    private var currentNetworkName: String = ""
    private var currentPassphrase: String = ""

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): HotspotService = this@HotspotService
    }

    // --- Wi-Fi P2P Receiver ---
    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    requestGroupInfo()
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    )
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED && isGroupCreated.not() && prefsManager.isHotspotActive) {
                        // Wi-Fi P2P re-enabled, re-create the group
                        Log.i(TAG, "Wi-Fi P2P re-enabled, re-creating hotspot group")
                        handler.postDelayed({
                            startHotspot(currentNetworkName, currentPassphrase)
                        }, 2000)
                    }
                }
            }
        }
    }

    // --- Wi-Fi State Monitoring (auto re-enable) ---
    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                val wifiState = intent.getIntExtra(
                    WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN
                )
                when (wifiState) {
                    WifiManager.WIFI_STATE_DISABLED -> {
                        if (prefsManager.isAutoWifiEnabled) {
                            Log.i(TAG, "Wi-Fi disabled — scheduling re-enable in 3 minutes")
                            handler.removeCallbacks(wifiReEnableRunnable)
                            handler.postDelayed(wifiReEnableRunnable, WIFI_RESTART_DELAY_MS)
                        }
                    }
                    WifiManager.WIFI_STATE_ENABLED -> {
                        handler.removeCallbacks(wifiReEnableRunnable)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private val wifiReEnableRunnable = Runnable {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wifiManager.isWifiEnabled) {
                Log.i(TAG, "Auto re-enabling Wi-Fi...")
                wifiManager.setWifiEnabled(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-enable Wi-Fi: ${e.message}")
        }
    }

    // --- Mobile Data Monitoring (auto re-enable) ---
    private var connectivityManager: ConnectivityManager? = null
    private var dataNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var hasDataConnection = true

    private val dataReEnableRunnable = Runnable {
        try {
            if (!hasDataConnection && prefsManager.isAutoDataEnabled) {
                Log.i(TAG, "Attempting to re-enable mobile data...")
                // Try reflection method
                val success = tryEnableMobileData()
                if (!success) {
                    // Show notification to user to enable data manually
                    showDataEnableNotification()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-enable data: ${e.message}")
            showDataEnableNotification()
        }
    }

    private fun tryEnableMobileData(): Boolean {
        // Method 1: Settings.Global (needs WRITE_SECURE_SETTINGS granted via ADB once)
        try {
            Settings.Global.putInt(contentResolver, "mobile_data", 1)
            // Also try to trigger the system to apply the change
            val intent = Intent("android.intent.action.ANY_DATA_STATE")
            intent.setPackage("com.android.phone")
            sendBroadcast(intent)
            Log.i(TAG, "Mobile data re-enabled via Settings.Global")
            return true
        } catch (e: SecurityException) {
            Log.w(TAG, "Settings.Global method failed (WRITE_SECURE_SETTINGS not granted): ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Settings.Global method failed: ${e.message}")
        }

        // Method 2: TelephonyManager reflection
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val method = tm.javaClass.getDeclaredMethod("setDataEnabled", Boolean::class.javaPrimitiveType)
            method.invoke(tm, true)
            Log.i(TAG, "Mobile data re-enabled via TelephonyManager")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "TelephonyManager reflection failed: ${e.message}")
        }

        // Method 3: Shell command (needs root)
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "svc data enable"))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Log.i(TAG, "Mobile data re-enabled via root shell command")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Root shell method failed: ${e.message}")
        }

        Log.e(TAG, "All methods to enable mobile data failed")
        return false
    }

    private fun showDataEnableNotification() {
        val dataIntent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
        val pendingIntent = PendingIntent.getActivity(
            this, 2, dataIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, CHANNEL_DATA_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(getString(R.string.data_enable_prompt))
            .setContentText(getString(R.string.data_enable_prompt))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_DATA_ID, notification)
    }

    private fun registerDataCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        dataNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                hasDataConnection = true
                handler.removeCallbacks(dataReEnableRunnable)
                // Dismiss any data notification
                val nm = getSystemService(NotificationManager::class.java)
                nm.cancel(NOTIFICATION_DATA_ID)
            }

            override fun onLost(network: Network) {
                hasDataConnection = false
                if (prefsManager.isAutoDataEnabled) {
                    Log.i(TAG, "Mobile data lost — scheduling re-enable in 2 minutes")
                    handler.removeCallbacks(dataReEnableRunnable)
                    handler.postDelayed(dataReEnableRunnable, DATA_RESTART_DELAY_MS)
                }
            }
        }

        connectivityManager?.registerNetworkCallback(request, dataNetworkCallback!!)
    }

    // --- Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        prefsManager = PreferencesManager(this)
        createNotificationChannels()
        setupWifiP2p()
        registerP2pReceiver()
        registerWifiStateReceiver()
        registerDataCallback()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopHotspot()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                currentNetworkName = intent?.getStringExtra(EXTRA_NETWORK_NAME)
                    ?: prefsManager.networkName
                currentPassphrase = intent?.getStringExtra(EXTRA_PASSPHRASE)
                    ?: prefsManager.networkPassphrase

                startForeground(NOTIFICATION_ID, buildNotification())
                startHotspot(currentNetworkName, currentPassphrase)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopHotspot()
        try { unregisterReceiver(p2pReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(wifiStateReceiver) } catch (_: Exception) {}
        dataNetworkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        super.onDestroy()
    }

    // --- Wi-Fi P2P Setup ---

    private fun setupWifiP2p() {
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = wifiP2pManager?.initialize(this, mainLooper, null)
    }

    private fun registerP2pReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        registerReceiver(p2pReceiver, intentFilter)
    }

    private fun registerWifiStateReceiver() {
        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        registerReceiver(wifiStateReceiver, filter)
    }

    // --- Hotspot Control ---

    @Suppress("MissingPermission")
    private fun startHotspot(networkName: String, passphrase: String) {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { createGroup(manager, ch, networkName, passphrase) }
            override fun onFailure(reason: Int) { createGroup(manager, ch, networkName, passphrase) }
        })
    }

    @Suppress("MissingPermission")
    private fun createGroup(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        networkName: String,
        passphrase: String
    ) {
        val config = WifiP2pConfig.Builder()
            .setNetworkName("DIRECT-$networkName")
            .setPassphrase(passphrase)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            .build()

        manager.createGroup(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Stealth hotspot created: DIRECT-$networkName")
                isGroupCreated = true
                prefsManager.isHotspotActive = true
                broadcastStatus(true)
                updateNotification()
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to create hotspot. Reason: $reason")
                isGroupCreated = false
                broadcastStatus(false)
            }
        })
    }

    @Suppress("MissingPermission")
    private fun stopHotspot() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        if (isGroupCreated) {
            manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "Stealth hotspot stopped")
                    isGroupCreated = false
                    prefsManager.isHotspotActive = false
                    broadcastStatus(false)
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Failed to stop hotspot. Reason: $reason")
                }
            })
        }
    }

    @Suppress("MissingPermission")
    private fun requestGroupInfo() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        manager.requestGroupInfo(ch) { group: WifiP2pGroup? ->
            connectedDeviceCount = group?.clientList?.size ?: 0
            broadcastStatus(isGroupCreated)
            updateNotification()
        }
    }

    fun getConnectedDeviceCount(): Int = connectedDeviceCount
    fun isActive(): Boolean = isGroupCreated

    private fun broadcastStatus(isActive: Boolean) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_IS_ACTIVE, isActive)
            putExtra(EXTRA_CONNECTED_COUNT, connectedDeviceCount)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // --- Notifications ---

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            CHANNEL_ID, "تحديث الطقس", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background service"
            setShowBadge(false)
        }
        nm.createNotificationChannel(serviceChannel)

        val dataChannel = NotificationChannel(
            CHANNEL_DATA_ID, "تنبيهات", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Data alerts"
        }
        nm.createNotificationChannel(dataChannel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, LoginActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HotspotService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(getString(R.string.notif_service_text))
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(null, "إيقاف", stopIntent).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    // --- Wake Lock ---

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "StealthHotspot::WakeLock"
        ).apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
