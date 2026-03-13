package com.mrd.stealthhotspot

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private lateinit var prefsManager: PreferencesManager

    // UI
    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var hotspotSwitch: SwitchMaterial
    private lateinit var networkNameInput: TextInputEditText
    private lateinit var networkPassInput: TextInputEditText
    private lateinit var saveSettingsButton: MaterialButton
    private lateinit var connectedCountText: TextView
    private lateinit var changePasswordButton: MaterialButton
    private lateinit var fullNetworkNameText: TextView
    private lateinit var autoWifiSwitch: SwitchMaterial
    private lateinit var autoDataSwitch: SwitchMaterial

    // Service binding
    private var hotspotService: HotspotService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as HotspotService.LocalBinder
            hotspotService = binder.getService()
            isBound = true
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hotspotService = null
            isBound = false
        }
    }

    // Status broadcast receiver
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == HotspotService.BROADCAST_STATUS) {
                val isActive = intent.getBooleanExtra(HotspotService.EXTRA_IS_ACTIVE, false)
                val connectedCount = intent.getIntExtra(HotspotService.EXTRA_CONNECTED_COUNT, 0)
                updateStatusUI(isActive, connectedCount)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefsManager = PreferencesManager(this)
        initViews()
        requestPermissions()
        loadSettings()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        bindToService()
        val filter = IntentFilter(HotspotService.BROADCAST_STATUS)
        registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        updateUI()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun initViews() {
        statusIcon = findViewById(R.id.statusIcon)
        statusText = findViewById(R.id.statusText)
        hotspotSwitch = findViewById(R.id.hotspotSwitch)
        networkNameInput = findViewById(R.id.networkNameInput)
        networkPassInput = findViewById(R.id.networkPassInput)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        connectedCountText = findViewById(R.id.connectedCountText)
        changePasswordButton = findViewById(R.id.changePasswordButton)
        fullNetworkNameText = findViewById(R.id.fullNetworkNameText)
        autoWifiSwitch = findViewById(R.id.autoWifiSwitch)
        autoDataSwitch = findViewById(R.id.autoDataSwitch)
    }

    private fun loadSettings() {
        networkNameInput.setText(prefsManager.networkName)
        networkPassInput.setText(prefsManager.networkPassphrase)
        autoWifiSwitch.isChecked = prefsManager.isAutoWifiEnabled
        autoDataSwitch.isChecked = prefsManager.isAutoDataEnabled
        updateNetworkNamePreview()
    }

    private fun setupListeners() {
        hotspotSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startHotspot() else stopHotspot()
        }

        saveSettingsButton.setOnClickListener { saveSettings() }
        changePasswordButton.setOnClickListener { showChangePasswordDialog() }

        networkNameInput.setOnFocusChangeListener { _, _ -> updateNetworkNamePreview() }

        autoWifiSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.isAutoWifiEnabled = isChecked
        }

        autoDataSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.isAutoDataEnabled = isChecked
        }
    }

    private fun updateNetworkNamePreview() {
        val name = networkNameInput.text?.toString() ?: prefsManager.networkName
        fullNetworkNameText.text = getString(R.string.full_network_name_format, name)
    }

    private fun saveSettings() {
        val name = networkNameInput.text?.toString()?.trim() ?: ""
        val pass = networkPassInput.text?.toString()?.trim() ?: ""

        if (name.isEmpty()) {
            Toast.makeText(this, R.string.error_empty_name, Toast.LENGTH_SHORT).show()
            return
        }
        if (pass.length < 8) {
            Toast.makeText(this, R.string.error_short_passphrase, Toast.LENGTH_SHORT).show()
            return
        }

        prefsManager.networkName = name
        prefsManager.networkPassphrase = pass
        updateNetworkNamePreview()
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()

        // If hotspot is active, restart with new settings
        if (hotspotService?.isActive() == true) {
            stopHotspot()
            hotspotSwitch.postDelayed({
                startHotspot()
                hotspotSwitch.isChecked = true
            }, 1000)
        }
    }

    private fun startHotspot() {
        val intent = Intent(this, HotspotService::class.java).apply {
            action = HotspotService.ACTION_START
            putExtra(HotspotService.EXTRA_NETWORK_NAME, prefsManager.networkName)
            putExtra(HotspotService.EXTRA_PASSPHRASE, prefsManager.networkPassphrase)
        }
        ContextCompat.startForegroundService(this, intent)
        bindToService()
    }

    private fun stopHotspot() {
        val intent = Intent(this, HotspotService::class.java).apply {
            action = HotspotService.ACTION_STOP
        }
        startService(intent)
        prefsManager.isHotspotActive = false
        updateStatusUI(false, 0)
    }

    private fun bindToService() {
        if (!isBound) {
            val intent = Intent(this, HotspotService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun updateUI() {
        val service = hotspotService
        if (service != null) {
            val isActive = service.isActive()
            val count = service.getConnectedDeviceCount()
            updateStatusUI(isActive, count)
            hotspotSwitch.setOnCheckedChangeListener(null)
            hotspotSwitch.isChecked = isActive
            hotspotSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) startHotspot() else stopHotspot()
            }
        } else if (prefsManager.isHotspotActive) {
            updateStatusUI(true, 0)
            hotspotSwitch.setOnCheckedChangeListener(null)
            hotspotSwitch.isChecked = true
            hotspotSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) startHotspot() else stopHotspot()
            }
        }
    }

    private fun updateStatusUI(isActive: Boolean, connectedCount: Int) {
        if (isActive) {
            statusIcon.setImageResource(android.R.drawable.presence_online)
            statusText.text = getString(R.string.status_active)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.green_accent))
            connectedCountText.text = connectedCount.toString()
        } else {
            statusIcon.setImageResource(android.R.drawable.presence_offline)
            statusText.text = getString(R.string.status_inactive)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            connectedCountText.text = "0"
        }
    }

    private fun showChangePasswordDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_change_password, null)
        val currentPassInput = dialogView.findViewById<TextInputEditText>(R.id.currentPasswordInput)
        val newPassInput = dialogView.findViewById<TextInputEditText>(R.id.newPasswordInput2)
        val confirmPassInput = dialogView.findViewById<TextInputEditText>(R.id.confirmPasswordInput2)
        val currentPassLayout = dialogView.findViewById<TextInputLayout>(R.id.currentPasswordLayout)
        val newPassLayout = dialogView.findViewById<TextInputLayout>(R.id.newPasswordLayout2)
        val confirmPassLayout = dialogView.findViewById<TextInputLayout>(R.id.confirmPasswordLayout2)

        val dialog = AlertDialog.Builder(this, R.style.Theme_StealthHotspot_Dialog)
            .setTitle(R.string.change_password)
            .setView(dialogView)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val currentPass = currentPassInput.text?.toString() ?: ""
            val newPass = newPassInput.text?.toString() ?: ""
            val confirmPass = confirmPassInput.text?.toString() ?: ""

            currentPassLayout.error = null
            newPassLayout.error = null
            confirmPassLayout.error = null

            when {
                !prefsManager.verifyPassword(currentPass) -> {
                    currentPassLayout.error = getString(R.string.error_wrong_password)
                }
                newPass.isEmpty() -> {
                    newPassLayout.error = getString(R.string.error_empty_password)
                }
                newPass.length < 4 -> {
                    newPassLayout.error = getString(R.string.error_short_password)
                }
                newPass != confirmPass -> {
                    confirmPassLayout.error = getString(R.string.error_passwords_mismatch)
                }
                else -> {
                    prefsManager.setAppPassword(newPass)
                    Toast.makeText(this, R.string.password_changed, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }
    }

    // --- Permissions ---

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, notGranted.toTypedArray(), PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val denied = grantResults.any { it != PackageManager.PERMISSION_GRANTED }
            if (denied) {
                Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show()
            }
        }
    }
}
