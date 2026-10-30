package com.kidslauncher.mdm.server

import android.content.Context
import android.content.SharedPreferences
import com.kidslauncher.mdm.launcherPreferencesReady
import com.kidslauncher.mdm.preferences.LauncherPreferences

/**
 * The two values Find My Device needs before the first unlock after a reboot - the server URL and
 * this device's API token - mirrored into device-protected storage, which (unlike
 * [LauncherPreferences]' credential-encrypted storage) is readable from boot. Deliberately nothing
 * else: device-protected storage is encrypted with a hardware-bound key that doesn't depend on the
 * user's PIN, so anything here is only as safe as the phone is against a before-first-unlock
 * exploit. The Tailscale *auth key* in particular never comes here - reconnecting before unlock
 * only needs tsnet's saved node identity (see [TsnetClient]), and the auth key is deleted from the
 * phone entirely once it has joined.
 *
 * [LauncherPreferences] stays the source of truth; [mirror] copies from it whenever the app runs
 * unlocked ([com.kidslauncher.mdm.Application.initAfterUnlock] and every [performMdmSync]), so a
 * re-enroll or server URL change reaches this copy within one sync.
 */
object BootState {
    private const val PREFS_NAME = "boot_state"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_DEVICE_TOKEN = "device_token"

    data class Credentials(val serverUrl: String, val deviceToken: String)

    /** Device-protected [SharedPreferences] - safe to use before the first unlock. */
    fun prefs(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Unlocked only - reads [LauncherPreferences]. */
    fun mirror(context: Context) {
        val mdm = LauncherPreferences.mdm()
        val serverUrl = mdm.serverUrl()
        val deviceToken = mdm.deviceToken()
        val prefs = prefs(context)
        if (prefs.getString(KEY_SERVER_URL, null) == serverUrl &&
            prefs.getString(KEY_DEVICE_TOKEN, null) == deviceToken
        ) {
            return
        }
        prefs.edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .apply()
    }

    /**
     * The server URL + device token from whichever store is readable right now - null if this
     * device isn't enrolled. Safe to call before or after the first unlock.
     */
    fun credentials(context: Context): Credentials? {
        val serverUrl: String?
        val deviceToken: String?
        if (launcherPreferencesReady) {
            val mdm = LauncherPreferences.mdm()
            serverUrl = mdm.serverUrl()
            deviceToken = mdm.deviceToken()
        } else {
            val prefs = prefs(context)
            serverUrl = prefs.getString(KEY_SERVER_URL, null)
            deviceToken = prefs.getString(KEY_DEVICE_TOKEN, null)
        }
        if (serverUrl.isNullOrBlank() || deviceToken.isNullOrBlank()) return null
        return Credentials(serverUrl, deviceToken)
    }
}
