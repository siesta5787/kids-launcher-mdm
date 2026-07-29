package de.jrpie.android.launcher.ui.settings.launcher

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.openAppsList
import de.jrpie.android.launcher.preferences.LauncherPreferences


/**
 * The [SettingsFragmentLauncher] is a used as a tab in the SettingsActivity.
 */
class SettingsFragmentLauncher : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val hiddenApps = findPreference<androidx.preference.Preference>(
            LauncherPreferences.apps().keys().hidden()
        )
        hiddenApps?.setOnPreferenceClickListener {
            openAppsList(requireContext(), hidden = true)
            true
        }
    }
}
