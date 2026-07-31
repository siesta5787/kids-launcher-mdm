package com.kidslauncher.mdm.ui.settings.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.copyToClipboard
import com.kidslauncher.mdm.getDeviceInfo
import com.kidslauncher.mdm.server.createMdmApi
import com.kidslauncher.mdm.server.dto.EnrollRequest
import com.kidslauncher.mdm.server.performMdmSync
import com.kidslauncher.mdm.openAppsList
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.ui.LegalInfoActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "SettingsFragmentLauncher"


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

        val mdm = LauncherPreferences.mdm()

        val serverUrl = findPreference<Preference>(mdm.keys().serverUrl())
        serverUrl?.summary = mdm.serverUrl().orNotSet()
        serverUrl?.setOnPreferenceClickListener {
            showEditTextDialog(
                requireContext(),
                getString(R.string.settings_mdm_server_url),
                mdm.serverUrl()
            ) { value ->
                mdm.serverUrl(value)
                serverUrl.summary = value.orNotSet()
            }
            true
        }

        val enrollNow = findPreference<Preference>("settings_mdm_enroll_now")
        enrollNow?.setOnPreferenceClickListener {
            showEditTextDialog(
                requireContext(),
                getString(R.string.dialog_enrollment_code_title),
                currentValue = null,
            ) { code ->
                enrollWithServer(requireContext(), code)
            }
            true
        }

        val syncNow = findPreference<Preference>("settings_mdm_sync_now")
        syncNow?.setOnPreferenceClickListener {
            syncNowWithServer(requireContext())
            true
        }
    }

    private fun String?.orNotSet(): String = this?.takeIf { it.isNotBlank() }
        ?: getString(R.string.settings_mdm_not_set)

    /**
     * [androidx.preference.EditTextPreference]'s built-in dialog doesn't pick up this app's
     * custom (dark) theme - it renders with invisible text/buttons. Uses the same themed
     * AlertDialog approach as the app-rename dialog instead.
     */
    private fun showEditTextDialog(
        context: Context,
        title: String,
        currentValue: String?,
        onSave: (String) -> Unit,
    ) {
        val dialog = AlertDialog.Builder(context, R.style.AlertDialogCustom).apply {
            setTitle(title)
            setView(R.layout.dialog_edit_text)
            setNegativeButton(android.R.string.cancel) { d, _ -> d.cancel() }
            setPositiveButton(android.R.string.ok) { d, _ ->
                val input = (d as? AlertDialog)?.findViewById<EditText>(R.id.dialog_edit_text_input)
                onSave(input?.text?.toString().orEmpty())
            }
        }.create()
        dialog.show()
        dialog.findViewById<EditText>(R.id.dialog_edit_text_input)?.setText(currentValue)
    }

    /**
     * Dev-testing shortcut: enrolls directly against the server URL typed into the preference
     * field above and the one-shot code shown on the admin site, over the local network, without
     * needing the full factory-reset -> scan-QR provisioning flow. Only touches the server's
     * enroll endpoint - it doesn't grant Device Owner (that still needs
     * `adb shell dpm set-device-owner` or real provisioning).
     */
    private fun enrollWithServer(context: Context, enrollmentCode: String) {
        val mdm = LauncherPreferences.mdm()
        val serverUrl = mdm.serverUrl()

        if (serverUrl.isNullOrBlank()) {
            Toast.makeText(context, R.string.toast_mdm_enroll_missing_fields, Toast.LENGTH_LONG)
                .show()
            return
        }
        if (enrollmentCode.isBlank()) {
            Toast.makeText(context, R.string.toast_mdm_enroll_missing_code, Toast.LENGTH_LONG)
                .show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val outcome = try {
                val response = createMdmApi(serverUrl).enroll(EnrollRequest(enrollmentCode))
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    Result.success(body)
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }

            withContext(Dispatchers.Main) {
                outcome.onSuccess { enrollResponse ->
                    mdm.deviceToken(enrollResponse.deviceToken)
                    mdm.enrolled(true)
                    Toast.makeText(context, R.string.toast_mdm_enroll_success, Toast.LENGTH_LONG)
                        .show()
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

    /**
     * Dev-testing shortcut: runs the same policy fetch + enforcement cycle
     * [com.kidslauncher.mdm.server.MdmSyncWorker] runs every 15 minutes, immediately - avoids
     * waiting a full cycle per test iteration (e.g. right after changing the allowlist or kiosk
     * setting on the admin site).
     */
    private fun syncNowWithServer(context: Context) {
        val mdm = LauncherPreferences.mdm()
        if (mdm.serverUrl().isNullOrBlank() || mdm.deviceToken().isNullOrBlank()) {
            Toast.makeText(context, R.string.toast_mdm_enroll_missing_fields, Toast.LENGTH_LONG)
                .show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val reachedServer = try {
                performMdmSync(context)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Manual sync failed", e)
                false
            }
            withContext(Dispatchers.Main) {
                val messageRes =
                    if (reachedServer) R.string.toast_mdm_sync_done else R.string.toast_mdm_sync_failed
                Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
