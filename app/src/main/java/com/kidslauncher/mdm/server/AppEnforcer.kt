package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.kidslauncher.mdm.Application
import com.kidslauncher.mdm.apps.AppInfo
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.ui.HomeActivity

private const val LOG_TAG = "AppEnforcer"

/**
 * Suspends/unsuspends installed apps to match [PolicyResponse.allowlist] (null/empty means no
 * restriction - nothing suspended), and - only when the server says so via
 * [PolicyResponse.kioskDesired] - pins the device to the allowed packages via Android's Device
 * Owner lock-task API, plus the WiFi/Bluetooth radio restrictions below. Only acts when this app
 * is device owner - a no-op otherwise, so it's safe to ship before the phone is actually
 * re-provisioned.
 */
object AppEnforcer {

    fun apply(context: Context, policy: PolicyResponse?) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return
        }
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)

        // While a locally-entered offline override is active, treat the device exactly as if
        // the server sent no policy at all - every branch below already means "fully open" for
        // a null policy (no allowlist, kiosk not desired, WiFi/Bluetooth "open"), so this reuses
        // the same code path rather than duplicating an "unlock everything" special case.
        val effectivePolicy = if (OfflineOverride.isActive()) null else policy

        enforceDefaultHome(dpm, admin, context)

        val allowedPackages = effectivePolicy?.allowlist?.takeIf { it.isNotEmpty() }?.toSet()
        val ownPackage = context.packageName
        val pm = context.packageManager

        val installedPackages = (context.applicationContext as Application).apps.value
            ?.mapNotNull { (it.getRawInfo() as? AppInfo)?.packageName }
            ?.distinct()
            ?: return

        for (packageName in installedPackages) {
            if (packageName == ownPackage) continue

            val shouldBeSuspended = allowedPackages != null && packageName !in allowedPackages
            val currentlySuspended = try {
                pm.isPackageSuspended(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                continue
            }
            if (shouldBeSuspended == currentlySuspended) continue

            try {
                dpm.setPackagesSuspended(admin, arrayOf(packageName), shouldBeSuspended)
                dpm.setApplicationHidden(admin, packageName, shouldBeSuspended)
            } catch (e: Exception) {
                Log.w(
                    LOG_TAG,
                    "Failed to ${if (shouldBeSuspended) "suspend" else "unsuspend"} $packageName",
                    e
                )
            }
        }

        applyKioskState(
            dpm, admin, ownPackage, allowedPackages,
            kioskDesired = effectivePolicy?.kioskDesired == true,
            lockTaskFeatures = effectivePolicy?.lockTaskFeatures ?: 0,
        )

        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        applyRadioRestrictions(dpm, admin, wifiManager, effectivePolicy)
    }

    /**
     * Only engages lock-task/kiosk pinning when BOTH an allowlist is configured AND the server
     * says to via [PolicyResponse.kioskDesired] - the admin site is the sole source of truth for
     * this, there is no on-device switch. Still gated on an allowlist existing at all: pinning
     * with zero allowed packages would strand the device on nothing but the launcher itself.
     */
    private fun applyKioskState(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        ownPackage: String,
        allowedPackages: Set<String>?,
        kioskDesired: Boolean,
        lockTaskFeatures: Long,
    ) {
        val mdm = LauncherPreferences.mdm()
        val shouldEngageKiosk = allowedPackages != null && kioskDesired

        if (shouldEngageKiosk) {
            dpm.setLockTaskPackages(admin, (allowedPackages + ownPackage).toTypedArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setLockTaskFeatures(admin, lockTaskFeatures.toInt())
            }
            mdm.kioskEnabled(true)
        } else {
            dpm.setLockTaskPackages(admin, emptyArray())
            mdm.kioskEnabled(false)
        }
    }

    /**
     * WiFi/Bluetooth restriction levels ("open"/"restricted"/"disabled" - see
     * [PolicyResponse.wifiMode]/[PolicyResponse.bluetoothMode]) via [UserManager] restrictions,
     * device-owner-only APIs. "disabled" additionally force-toggles the WiFi radio itself, since
     * unlike Bluetooth's DISALLOW_BLUETOOTH there's no single WiFi restriction that both disables
     * the radio and blocks re-enabling - DISALLOW_CHANGE_WIFI_STATE (min API 34, already this
     * app's floor) only blocks the *user* from changing it, so the radio still needs an explicit
     * setWifiEnabled(false) call here (a device-owner-exempt API even on modern Android).
     */
    private fun applyRadioRestrictions(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        wifiManager: WifiManager,
        policy: PolicyResponse?,
    ) {
        when (policy?.wifiMode ?: "open") {
            "disabled" -> {
                try {
                    wifiManager.setWifiEnabled(false)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Failed to disable WiFi", e)
                }
                setRestriction(dpm, admin, UserManager.DISALLOW_CHANGE_WIFI_STATE, true)
                setRestriction(dpm, admin, UserManager.DISALLOW_ADD_WIFI_CONFIG, false)
            }
            "restricted" -> {
                setRestriction(dpm, admin, UserManager.DISALLOW_CHANGE_WIFI_STATE, false)
                setRestriction(dpm, admin, UserManager.DISALLOW_ADD_WIFI_CONFIG, true)
            }
            else -> {
                setRestriction(dpm, admin, UserManager.DISALLOW_CHANGE_WIFI_STATE, false)
                setRestriction(dpm, admin, UserManager.DISALLOW_ADD_WIFI_CONFIG, false)
            }
        }

        when (policy?.bluetoothMode ?: "open") {
            "disabled" -> {
                setRestriction(dpm, admin, UserManager.DISALLOW_BLUETOOTH, true)
                setRestriction(dpm, admin, UserManager.DISALLOW_CONFIG_BLUETOOTH, false)
            }
            "restricted" -> {
                setRestriction(dpm, admin, UserManager.DISALLOW_BLUETOOTH, false)
                setRestriction(dpm, admin, UserManager.DISALLOW_CONFIG_BLUETOOTH, true)
            }
            else -> {
                setRestriction(dpm, admin, UserManager.DISALLOW_BLUETOOTH, false)
                setRestriction(dpm, admin, UserManager.DISALLOW_CONFIG_BLUETOOTH, false)
            }
        }
    }

    private fun setRestriction(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        restriction: String,
        enabled: Boolean,
    ) {
        try {
            if (enabled) {
                dpm.addUserRestriction(admin, restriction)
            } else {
                dpm.clearUserRestriction(admin, restriction)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to ${if (enabled) "add" else "clear"} restriction $restriction", e)
        }
    }

    /**
     * The regular "set as default launcher" flow ([com.kidslauncher.mdm.setDefaultHomeScreen]) is
     * just a user-revocable preference - a reinstall/reboot race, or a kid holding down the home
     * button, can fall back to another HOME-capable app (e.g. the OS's own stock launcher) if one
     * is installed. As device owner we can pin this unconditionally instead.
     */
    private fun enforceDefaultHome(dpm: DevicePolicyManager, admin: ComponentName, context: Context) {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        try {
            dpm.addPersistentPreferredActivity(
                admin, filter, ComponentName(context, HomeActivity::class.java)
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set persistent preferred HOME activity", e)
        }
    }
}
