package com.kidslauncher.mdm.headwind

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.kidslauncher.mdm.Application
import com.kidslauncher.mdm.apps.AppInfo
import com.kidslauncher.mdm.headwind.dto.KidModePolicy
import kotlinx.serialization.decodeFromString

private const val LOG_TAG = "AppEnforcer"

/**
 * Suspends/unsuspends installed apps to match [KidModePolicy.allowlistJson] (a JSON list of
 * package names; null/empty means no restriction - nothing suspended). Only acts when this app
 * is device owner - a no-op otherwise, so it's safe to ship before the phone is actually
 * re-provisioned.
 */
object AppEnforcer {

    fun apply(context: Context, policy: KidModePolicy?) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return
        }
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)

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
