package de.jrpie.android.launcher.ui.settings.meta

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import de.jrpie.android.launcher.BuildConfig
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.copyToClipboard
import de.jrpie.android.launcher.databinding.SettingsMetaBinding
import de.jrpie.android.launcher.getDeviceInfo
import de.jrpie.android.launcher.openTutorial
import de.jrpie.android.launcher.preferences.resetPreferences
import de.jrpie.android.launcher.ui.LegalInfoActivity
import de.jrpie.android.launcher.ui.UIObject

/**
 * The [SettingsFragmentMeta] is a used as a tab in the SettingsActivity.
 *
 * It is used to change settings and access resources about Launcher,
 * that are not directly related to the behaviour of the app itself.
 *
 * (greek `meta` = above, next level)
 */
class SettingsFragmentMeta : Fragment(), UIObject {

    private lateinit var binding: SettingsMetaBinding
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsMetaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super<Fragment>.onStart()
        super<UIObject>.onStart()
    }

    override fun setOnClicks() {

        binding.settingsMetaButtonViewTutorial.setOnClickListener {
            openTutorial(requireContext())
        }

        // prompting for settings-reset confirmation
        binding.settingsMetaButtonResetSettings.setOnClickListener {
            AlertDialog.Builder(this.requireContext(), R.style.AlertDialogCustom)
                .setTitle(getString(R.string.settings_meta_reset))
                .setMessage(getString(R.string.settings_meta_reset_confirm))
                .setPositiveButton(
                    android.R.string.ok
                ) { _, _ ->
                    resetPreferences(this.requireContext())
                    requireActivity().finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show()
        }


        // legal info
        binding.settingsMetaButtonLicenses.setOnClickListener {
            startActivity(Intent(this.context, LegalInfoActivity::class.java))
        }

        // version
        binding.settingsMetaTextVersion.text = BuildConfig.VERSION_NAME
        binding.settingsMetaTextVersion.setOnClickListener {
            val deviceInfo = getDeviceInfo()
            copyToClipboard(requireContext(), deviceInfo)
        }

    }
}
