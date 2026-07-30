package com.kidslauncher.mdm.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.SettingsBinding
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.ui.UIObjectActivity

/**
 * The [SettingsActivity] holds all of the app's settings on a single page.
 *
 * Settings are closed automatically if the activity goes `onPause` unexpectedly.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialise layout
        binding = SettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    override fun onStart() {
        super.onStart()
        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
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
