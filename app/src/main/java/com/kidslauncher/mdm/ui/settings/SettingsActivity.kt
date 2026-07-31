package com.kidslauncher.mdm.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.SettingsBinding
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.ui.UIObjectActivity

/**
 * The [SettingsActivity] holds all of the app's settings on a single page.
 *
 * Settings are closed automatically if the activity goes `onPause` unexpectedly.
 *
 * Gated behind the same offline-override PIN (if one is configured) so a kid can't tamper with
 * enrollment/sync/the restrictions-pause switch below. The gate is a non-cancelable modal dialog
 * shown on top of the normally-inflated content rather than deferring content/binding setup,
 * since [com.kidslauncher.mdm.ui.UIObject]'s `onStart()` unconditionally calls [setOnClicks],
 * which needs [binding] to already exist.
 */
class SettingsActivity : UIObjectActivity() {

    private val sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, prefKey ->
            if (prefKey?.startsWith("theme.") == true ||
                prefKey?.startsWith("display.") == true
            ) {
                recreate()
            }
        }
    private lateinit var binding: SettingsBinding
    private var pinGateShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialise layout
        binding = SettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Invisible (not gone - still needs to be measured/laid out for setOnClicks() to work
        // once onStart() runs) until the PIN gate below passes, so a kid can't glimpse the
        // settings list behind the dialog before entering the code.
        if (OfflineOverride.isConfigured()) {
            binding.root.visibility = View.INVISIBLE
        }
    }

    override fun onStart() {
        super.onStart()
        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)

        if (!pinGateShown && OfflineOverride.isConfigured()) {
            pinGateShown = true
            showPinGate()
        }
    }

    private fun showPinGate() {
        if (OfflineOverride.isLockedOut()) {
            Toast.makeText(this, R.string.lock_unlock_code_locked_out, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val dialog = AlertDialog.Builder(this, R.style.AlertDialogCustom).apply {
            setTitle(R.string.settings_gate_dialog_title)
            setView(R.layout.dialog_offline_override_pin)
            setCancelable(false)
            setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            setPositiveButton(android.R.string.ok, null)
        }.create()
        dialog.show()

        // Overridden after show() so a wrong PIN re-prompts instead of dismissing/finishing.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val input = dialog.findViewById<EditText>(R.id.dialog_offline_override_pin_input)
            val pin = input?.text?.toString().orEmpty()
            if (OfflineOverride.verifyPin(pin)) {
                binding.root.visibility = View.VISIBLE
                dialog.dismiss()
            } else if (OfflineOverride.isLockedOut()) {
                Toast.makeText(this, R.string.lock_unlock_code_locked_out, Toast.LENGTH_LONG).show()
                dialog.dismiss()
                finish()
            } else {
                Toast.makeText(this, R.string.lock_unlock_code_wrong, Toast.LENGTH_SHORT).show()
                input?.text?.clear()
            }
        }
    }

    override fun onPause() {
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        super.onPause()
    }

    override fun setOnClicks() {
        // As older APIs somehow do not recognize the xml defined onClick
        binding.settingsClose.setOnClickListener { finish() }
        // open device settings (see https://stackoverflow.com/a/62092663/12787264)
        binding.settingsSystem.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                // The system Settings app is suspended/hidden when it's not in the KidMode
                // allowlist - there is then nothing to resolve this intent to.
                Toast.makeText(this, R.string.toast_system_settings_unavailable, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }
}
