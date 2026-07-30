package com.kidslauncher.mdm.headwind

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
import com.kidslauncher.mdm.headwind.dto.KidModePolicy
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.ui.HomeActivity
import kotlinx.serialization.decodeFromString

private const val LOG_TAG = "AppEnforcer"

/**
 * Suspends/unsuspends installed apps to match [KidModePolicy.allowlistJson] (a JSON list of
 * package names; null/empty means no restriction - nothing suspended), and - only when the user
 * has explicitly enabled it via Settings ("Enable kiosk mode") - pins the device to the allowed
 * packages via Android's Device Owner lock-task API. Only acts when this app is device owner - a
 * no-op otherwise, so it's safe to ship before the phone is actually re-provisioned.
 */
object AppEnforcer {

    fun apply(context: Context, policy: KidModePolicy?) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return
        }
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)

        enforceDefaultHome(dpm, admin, context)

        val allowedPackages = parseAllowlist(policy?.allowlistJson)
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

        applyKioskState(dpm, admin, ownPackage, allowedPackages)
    }

    /**
     * Only engages lock-task/kiosk pinning when BOTH an allowlist is configured AND the user has
     * explicitly opted in via "Enable kiosk mode" in Settings. Deliberately not tied to the
     * allowlist alone: once pinned with every LOCK_TASK_FEATURE_* off there is no on-device way
     * out, so it must never engage without a deliberate, separate user action.
     */
    private fun applyKioskState(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        ownPackage: String,
        allowedPackages: Set<String>?,
    ) {
        val mdm = LauncherPreferences.mdm()
        val shouldEngageKiosk = allowedPackages != null && mdm.kioskModeEnabled()

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

    private fun parseAllowlist(allowlistJson: String?): Set<String>? {
        if (allowlistJson.isNullOrBlank()) return null
        return try {
            HeadwindJson.decodeFromString<List<String>>(allowlistJson).toSet()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to parse allowlistJson: $allowlistJson", e)
            null
        }
    }
}
