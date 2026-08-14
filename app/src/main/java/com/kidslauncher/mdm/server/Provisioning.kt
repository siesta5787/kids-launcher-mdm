package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.EnrollRequest
import com.kidslauncher.mdm.server.dto.ProvisioningExtras

/**
 * Saves the server URL/Tailscale key and enrolls in one shot from a [ProvisioningExtras] -
 * shared by [com.kidslauncher.mdm.server.MdmDeviceAdminReceiver.onProfileProvisioningComplete]
 * (Android's native zero-touch flow) and the in-app QR scanner (for devices like GrapheneOS where
 * that native flow has no trigger at all), so there is exactly one enroll code path regardless of
 * how Device Owner was granted - by this point it already has been, either way, so this is a pure
 * network call.
 */
suspend fun applyProvisioningExtras(extras: ProvisioningExtras): Result<Unit> {
    val mdm = LauncherPreferences.mdm()
    mdm.serverUrl(extras.serverUrl)
    if (extras.tailscaleAuthKey.isNotBlank()) {
        mdm.tailscaleAuthKey(extras.tailscaleAuthKey)
    }

    return try {
        val response = createMdmApi(extras.serverUrl).enroll(EnrollRequest(extras.enrollmentCode))
        val body = response.body()
        if (response.isSuccessful && body != null) {
            mdm.deviceToken(body.deviceToken)
            mdm.enrolled(true)
            Result.success(Unit)
        } else {
            Result.failure(Exception("HTTP ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
