package com.kidslauncher.mdm.headwind

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val LOG_TAG = "MdmDeviceAdminReceiver"

/**
 * Minimal device-admin/device-owner receiver - enough for `adb shell dpm set-device-owner` to
 * accept this component. Consuming a QR-provisioning bundle in [onProfileProvisioningComplete]
 * is deferred to a later phase (the polished factory-reset -> scan-QR enrollment flow); for now
 * enrollment with the Headwind server happens separately, from the Settings screen's manual
 * "Enroll now" action.
 */
class MdmDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(LOG_TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(LOG_TAG, "Device admin disabled")
    }
}
