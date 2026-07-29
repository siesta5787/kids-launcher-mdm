package com.kidslauncher.mdm.ui.settings.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.copyToClipboard
import com.kidslauncher.mdm.getDeviceInfo
import com.kidslauncher.mdm.headwind.createMdmApi
import com.kidslauncher.mdm.headwind.dto.EnrollRequest
import com.kidslauncher.mdm.openAppsList
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.ui.LegalInfoActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * The [SettingsFragmentLauncher] holds all of the app's settings on a single screen.
 */
class SettingsFragmentLauncher : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val hiddenApps = findPreference<Preference>(
            LauncherPreferences.apps().keys().hidden()
        )
        hiddenApps?.setOnPreferenceClickListener {
            openAppsList(requireContext(), hidden = true)
            true
        }

        val licenses = findPreference<Preference>("settings_meta_licenses")
        licenses?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), LegalInfoActivity::class.java))
            true
        }

        val version = findPreference<Preference>("settings_meta_version")
        version?.summary = BuildConfig.VERSION_NAME
        version?.setOnPreferenceClickListener {
            copyToClipboard(requireContext(), getDeviceInfo(requireContext()))
            true
        }

        val enrollNow = findPreference<Preference>("settings_mdm_enroll_now")
        enrollNow?.setOnPreferenceClickListener {
            enrollWithHeadwindServer(requireContext())
            true
        }
    }

    /**
     * Dev-testing shortcut: enrolls directly against the server URL/device number typed into the
     * two preference fields above, over the local network, without needing the full
     * factory-reset -> scan-QR provisioning flow. Only touches Headwind's config/enroll endpoint -
     * it doesn't grant Device Owner (that still needs `adb shell dpm set-device-owner` or real
     * provisioning).
     */
    private fun enrollWithHeadwindServer(context: Context) {
        val mdm = LauncherPreferences.mdm()
        val serverUrl = mdm.serverUrl()
        val deviceNumber = mdm.deviceNumber()

        if (serverUrl.isNullOrBlank() || deviceNumber.isNullOrBlank()) {
            Toast.makeText(context, R.string.toast_mdm_enroll_missing_fields, Toast.LENGTH_LONG)
                .show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val outcome = try {
                val response = createMdmApi(serverUrl).enroll(deviceNumber, EnrollRequest())
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }

            withContext(Dispatchers.Main) {
                outcome.onSuccess {
                    mdm.enrolled(true)
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_mdm_enroll_success, deviceNumber),
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { e ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_mdm_enroll_failure, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
