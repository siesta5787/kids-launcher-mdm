package com.kidslauncher.mdm.server

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log

private const val LOG_TAG = "ProvisioningActivities"

private fun Intent.adminExtrasBundle(): PersistableBundle? =
    getParcelableExtra(
        DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
        PersistableBundle::class.java,
    )

/**
 * `GET_PROVISIONING_MODE` handler - Android 12+'s "admin-integrated" provisioning flow asks the
 * DPC which mode it wants right after downloading/installing it from the QR code's download URL.
 * Without an activity for this (and [ProvisioningComplianceActivity]), GMS setup wizards abort
 * QR provisioning with a bare "Can't set up device" and a factory-reset button - confirmed on a
 * Moto G Play 2024 (Android 14). This app only ever runs as a fully managed Device Owner, so the
 * answer is always the same. The admin extras are passed back through unchanged so they reach
 * [ProvisioningComplianceActivity] regardless of whether the platform forwards the original copy.
 */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(LOG_TAG, "GET_PROVISIONING_MODE - answering fully managed device")
        val result = Intent().putExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_MODE,
            DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
        )
        intent.adminExtrasBundle()?.let {
            result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, it)
        }
        setResult(RESULT_OK, result)
        finish()
    }
}

/**
 * `ADMIN_POLICY_COMPLIANCE` handler - the setup wizard launches this once Device Owner has been
 * granted, and won't finish provisioning until it returns [RESULT_OK]. Kicks off the same
 * auto-enroll [MdmDeviceAdminReceiver.onProfileProvisioningComplete] does (deduplicated in
 * [applyNativeProvisioningExtrasOnce]) and returns immediately rather than holding the setup
 * wizard on the tailnet connect + enroll round-trip - policy is applied on the launcher's normal
 * first sync after setup finishes anyway.
 */
class ProvisioningComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(LOG_TAG, "ADMIN_POLICY_COMPLIANCE - starting auto-enroll")
        applyNativeProvisioningExtrasOnce(this, intent.adminExtrasBundle())
        setResult(RESULT_OK)
        finish()
    }
}
