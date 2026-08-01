package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log

private const val LOG_TAG = "QuickControls"

/**
 * Bits for [com.kidslauncher.mdm.server.dto.PolicyResponse.quickControlsMask] - which switches
 * show up on [com.kidslauncher.mdm.ui.quickcontrols.QuickControlsActivity], the launcher's
 * swipe-left-from-home replacement for Android's native Quick Settings shade. Must match the
 * server's `QUICK_CONTROL_*` constants in `handlers/devices.rs`.
 */
object QuickControlFeature {
    const val WIFI: Long = 1
    const val BLUETOOTH: Long = 2
    const val BRIGHTNESS: Long = 4
}

/**
 * Direct, silent Device-Owner API calls backing the Quick Controls screen - deliberately not
 * Android's own Quick Settings tiles or panel intents (`Settings.Panel.ACTION_WIFI` etc.), since
 * this whole screen exists specifically to give a kid safe toggle access without depending on any
 * Android UI surface a third-party app could also publish into (see
 * [AppEnforcer.applyVpnRestrictions] for the concrete case - Tailscale's own Quick Settings tile
 * bypassing its managed-config restriction - that motivated this).
 */
object QuickControls {

    /**
     * [WifiManager.setWifiEnabled] is deprecated for regular apps since Android 10, which always
     * fails it silently (returns false, does nothing) - but Device Owner apps are explicitly
     * exempted from that restriction, so this still works for us with zero prompts.
     */
    fun setWifiEnabled(context: Context, enabled: Boolean): Boolean {
        return try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiManager.setWifiEnabled(enabled)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set WiFi enabled=$enabled", e)
            false
        }
    }

    fun isWifiEnabled(context: Context): Boolean {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.isWifiEnabled
    }

    /**
     * Same Device-Owner exemption as WiFi above, this time for the Android 13+ restriction on
     * [BluetoothAdapter.enable]/[BluetoothAdapter.disable]. Also silently self-grants
     * `BLUETOOTH_CONNECT` first (a normal runtime permission, not blocked from Device-Owner
     * self-granting the way `CAMERA` is on Android 12+) since that's required to call these on
     * API 31+, and a kid should never see a system permission dialog on this screen.
     */
    fun setBluetoothEnabled(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        enabled: Boolean,
    ): Boolean {
        return try {
            grantBluetoothConnectIfNeeded(context, dpm, admin)
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            @Suppress("DEPRECATION", "MissingPermission")
            if (enabled) adapter.enable() else adapter.disable()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set Bluetooth enabled=$enabled", e)
            false
        }
    }

    @Suppress("MissingPermission")
    fun isBluetoothEnabled(): Boolean {
        return BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
    }

    private fun grantBluetoothConnectIfNeeded(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            dpm.setPermissionGrantState(
                admin,
                context.packageName,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to self-grant BLUETOOTH_CONNECT", e)
        }
    }

    /**
     * [DevicePolicyManager.setSystemSetting] is a Device-Owner-only API restricted to a small
     * whitelist of settings keys - [Settings.System.SCREEN_BRIGHTNESS] is one of them, so this
     * works silently with no permission prompt (unlike a regular app, which needs the special
     * `WRITE_SETTINGS` app-op the user has to grant manually). Forces manual brightness mode
     * first, or auto-brightness would immediately override a slider drag.
     */
    fun setBrightness(dpm: DevicePolicyManager, admin: ComponentName, value: Int) {
        val clamped = value.coerceIn(0, 255)
        try {
            dpm.setSystemSetting(
                admin,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString(),
            )
            dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS, clamped.toString())
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set brightness=$clamped", e)
        }
    }

    fun currentBrightness(context: Context): Int {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            128
        }
    }
}
