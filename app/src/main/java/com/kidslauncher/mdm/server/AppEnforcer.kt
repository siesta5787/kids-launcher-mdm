package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
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
 * Owner lock-task API. Only acts when this app is device owner - a no-op otherwise, so it's safe
 * to ship before the phone is actually re-provisioned.
 */
object AppEnforcer {

    fun apply(context: Context, policy: PolicyResponse?) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return
        }
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)

        enforceDefaultHome(dpm, admin, context)

        val allowedPackages = policy?.allowlist?.takeIf { it.isNotEmpty() }?.toSet()
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

        applyKioskState(dpm, admin, ownPackage, allowedPackages, policy?.kioskDesired == true)
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
    ) {
        val mdm = LauncherPreferences.mdm()
        val shouldEngageKiosk = allowedPackages != null && kioskDesired

        if (shouldEngageKiosk) {
            dpm.setLockTaskPackages(admin, (allowedPackages + ownPackage).toTypedArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
            mdm.kioskEnabled(true)
        } else {
            dpm.setLockTaskPackages(admin, emptyArray())
            mdm.kioskEnabled(false)
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
