package com.mrd.stealthhotspot

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class LoginActivity : AppCompatActivity() {

    private lateinit var prefsManager: PreferencesManager
    private lateinit var passwordInput: TextInputEditText
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var unlockButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        prefsManager = PreferencesManager(this)

        passwordInput = findViewById(R.id.passwordInput)
        passwordLayout = findViewById(R.id.passwordLayout)
        unlockButton = findViewById(R.id.unlockButton)

        if (prefsManager.isFirstLaunch) {
            showSetPasswordDialog()
        }

        unlockButton.setOnClickListener {
            val password = passwordInput.text?.toString() ?: ""
            if (password.isEmpty()) {
                passwordLayout.error = getString(R.string.error_empty_password)
                return@setOnClickListener
            }

            if (prefsManager.isFirstLaunch) {
                showSetPasswordDialog()
                return@setOnClickListener
            }

            if (prefsManager.verifyPassword(password)) {
                passwordLayout.error = null
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                passwordLayout.error = getString(R.string.error_wrong_password)
                passwordInput.text?.clear()
            }
        }
    }

    private fun showSetPasswordDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_set_password, null)
        val newPasswordInput = dialogView.findViewById<TextInputEditText>(R.id.newPasswordInput)
        val confirmPasswordInput = dialogView.findViewById<TextInputEditText>(R.id.confirmPasswordInput)
        val newPasswordLayout = dialogView.findViewById<TextInputLayout>(R.id.newPasswordLayout)
        val confirmPasswordLayout = dialogView.findViewById<TextInputLayout>(R.id.confirmPasswordLayout)

        val dialog = AlertDialog.Builder(this, R.style.Theme_StealthHotspot_Dialog)
            .setTitle(R.string.set_password_title)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newPassword = newPasswordInput.text?.toString() ?: ""
            val confirmPassword = confirmPasswordInput.text?.toString() ?: ""

            newPasswordLayout.error = null
            confirmPasswordLayout.error = null

            when {
                newPassword.isEmpty() -> {
                    newPasswordLayout.error = getString(R.string.error_empty_password)
                }
                newPassword.length < 4 -> {
                    newPasswordLayout.error = getString(R.string.error_short_password)
                }
                newPassword != confirmPassword -> {
                    confirmPasswordLayout.error = getString(R.string.error_passwords_mismatch)
                }
                else -> {
                    prefsManager.setAppPassword(newPassword)
                    Toast.makeText(this, R.string.password_saved, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }
    }
}
