package de.jrpie.android.launcher.ui.settings.launcher

import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.actions.lock.LockMethod
import de.jrpie.android.launcher.actions.openAppsList
import de.jrpie.android.launcher.preferences.HomeMode
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.preferences.theme.ColorTheme
import de.jrpie.android.launcher.setDefaultHomeScreen


/**
 * The [SettingsFragmentLauncher] is a used as a tab in the SettingsActivity.
 *
 * It is used to change themes, select wallpapers ... theme related stuff
 */
class SettingsFragmentLauncher : PreferenceFragmentCompat() {


    private var sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, prefKey ->
            if (prefKey == LauncherPreferences.general().keys().homeMode()) {
                updateVisibility()
            }
        }

    private fun updateVisibility() {
        val hidePausedApps = findPreference<androidx.preference.Preference>(
            LauncherPreferences.apps().keys().hidePausedApps()
        )
        hidePausedApps?.isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

        val homeMode = LauncherPreferences.general().homeMode()

        val allowGestures = findPreference<androidx.preference.Preference>(
            LauncherPreferences.minimalist().keys().allowGestures()
        )
        allowGestures?.isVisible = homeMode == HomeMode.MINIMAL

        val minimalistApps = findPreference<androidx.preference.Preference>(
            LauncherPreferences.minimalist().keys().apps()
        )
        minimalistApps?.isVisible = homeMode == HomeMode.MINIMAL
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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val chooseHomeScreen = findPreference<androidx.preference.Preference>(
            LauncherPreferences.general().keys().chooseHomeScreen()
        )
        chooseHomeScreen?.setOnPreferenceClickListener {
            setDefaultHomeScreen(requireContext(), checkDefault = false)
            true
        }

        val hiddenApps = findPreference<androidx.preference.Preference>(
            LauncherPreferences.apps().keys().hidden()
        )
        hiddenApps?.setOnPreferenceClickListener {
            openAppsList(requireContext(), hidden = true)
            true
        }

        val minimalistApps = findPreference<androidx.preference.Preference>(
            LauncherPreferences.minimalist().keys().apps()
        )
        minimalistApps?.setOnPreferenceClickListener {
            openAppsList(requireContext())
            true
        }

        val lockMethod = findPreference<androidx.preference.Preference>(
            LauncherPreferences.actions().keys().lockMethod()
        )

        lockMethod?.setOnPreferenceClickListener {
            LockMethod.chooseMethod(requireContext())
            true
        }

        findPreference<androidx.preference.DropDownPreference>(
            LauncherPreferences.theme().keys().colorTheme()
        )?.apply {
            entries = ColorTheme.entries.filter { x -> x.isAvailable() }
                .map { x -> x.getLabel(requireContext()) }.toTypedArray()
            entryValues = ColorTheme.entries.filter { x -> x.isAvailable() }
                .map { x -> x.name }.toTypedArray()
        }


        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            lockMethod?.isVisible = false
        }

        updateVisibility()
    }
}
