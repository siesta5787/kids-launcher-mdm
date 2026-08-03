package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.ui.HomeActivity

private const val LOG_TAG = "AppEnforcer"

/**
 * Common system apps with a real, user-facing UI that a parent might reasonably want to
 * allow/restrict, despite being [android.content.pm.ApplicationInfo.FLAG_SYSTEM] (so the blanket
 * "never touch system packages" safety filter below wouldn't otherwise include them at all).
 * Deliberately a short, explicit, hand-picked list rather than any broader heuristic (e.g. "has a
 * launcher intent") - suspending the wrong core OS package (SystemUI, telephony/package-manager
 * internals, resource overlays, ...) can crash or boot-loop the device outright, so this errs
 * firmly on the side of under-inclusion. Extend this list deliberately, one known-safe app at a
 * time, never by loosening the FLAG_SYSTEM check itself.
 */
private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

private val SAFE_SYSTEM_PACKAGES = setOf(
    "com.android.settings",
    "com.android.dialer",
    "com.android.messaging",
    "com.android.contacts",
    "com.android.deskclock",
    "com.android.calculator2",
    "com.android.calendar",
    "com.android.camera2",
    "com.android.documentsui",
    "com.android.gallery3d",
    "app.grapheneos.camera",
    "app.grapheneos.pdfviewer",
    "app.vanadium.browser",
)

/**
 * The set of packages [AppEnforcer] will ever consider suspending/hiding, and that
 * [com.kidslauncher.mdm.server.MdmSyncWorker]'s status report offers the admin site as
 * allow/restrict checkboxes - the two must stay in sync, or the admin could check a box for an
 * app this loop then silently never acts on.
 *
 * A direct [PackageManager] query, not the launcher's own `Application.apps` (LauncherApps-based)
 * list: that excludes apps already hidden via [android.app.admin.DevicePolicyManager.setApplicationHidden],
 * so relying on it here would mean a hidden app could never be found again to un-hide it - a
 * permanent one-way lock. But a raw, unfiltered [PackageManager.getInstalledApplications] is
 * actively dangerous: it includes core OS/system packages that must never be suspended (doing so
 * can crash or boot-loop the device - this is not hypothetical, it happened during development).
 * So: third-party (non-system) apps are always included, and system apps only via the explicit
 * [SAFE_SYSTEM_PACKAGES] allowlist above - never a broader "looks launchable" heuristic.
 */
internal fun controllablePackages(pm: PackageManager): List<String> {
    // MATCH_UNINSTALLED_PACKAGES is required here, or this list silently drops any package this
    // same enforcer has already hidden via setApplicationHidden - PackageManager excludes hidden
    // packages from getInstalledApplications() by default. Without this flag, a hidden app can
    // never be found again to un-hide it: a permanent one-way lock, and exactly the bug this
    // function was written to avoid in the first place (see the class-level doc above).
    return pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        .filter { info ->
            (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                info.packageName in SAFE_SYSTEM_PACKAGES
        }
        .map { it.packageName }
        .distinct()
}

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

        // While a locally-entered offline override is active, or a parent has flipped the manual
        // "pause all restrictions" kill-switch in Settings, treat the device exactly as if the
        // server sent no policy at all - every branch below already means "fully open" for a null
        // policy (no allowlist, kiosk not desired, WiFi/Bluetooth "open"), so this reuses the same
        // code path rather than duplicating an "unlock everything" special case.
        val effectivePolicy =
            if (OfflineOverride.isActive() || LauncherPreferences.mdm().restrictionsPaused()) null
            else policy

        enforceDefaultHome(dpm, admin, context)

        val allowedPackages = effectivePolicy?.allowlist?.takeIf { it.isNotEmpty() }?.toSet()
        val ownPackage = context.packageName
        val pm = context.packageManager

        val installedPackages = controllablePackages(pm)
        val requireTailscale = effectivePolicy?.requireTailscale == true

        for (packageName in installedPackages) {
            if (packageName == ownPackage) continue
            // Tailscale providing the connectivity that requireTailscale itself depends on must
            // never be suspendable by the same policy that requires it - an admin who forgets to
            // check it in the allowlist would otherwise cut the phone off from ever reaching the
            // server again. Mirrors the ownPackage skip above; see applyVpnRestrictions.
            if (requireTailscale && packageName == TAILSCALE_PACKAGE) continue

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

        applyRadioRestrictions(dpm, admin, effectivePolicy)

        applyVpnRestrictions(dpm, admin, effectivePolicy)
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

        try {
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
        } catch (e: Exception) {
            // Unlike every DPM call above, this one previously wasn't guarded - letting it throw
            // would kill the rest of apply() (and, since apply() itself isn't try/caught by its
            // caller, the whole sync cycle including the status report) over one bad kiosk call.
            Log.w(LOG_TAG, "Failed to apply kiosk state", e)
        }
    }

    /**
     * WiFi/Bluetooth restriction levels via [UserManager] restrictions, device-owner-only APIs.
     * WiFi only supports "open"/"restricted" (no "disabled") - a prior "disabled" WiFi mode that
     * force-toggled the radio via `setWifiEnabled(false)` proved unreliable in testing and had no
     * strong use case, so it was removed rather than fixed; anything other than "restricted"
     * (including a stale "disabled" value in already-stored policy) falls through to "open".
     * Bluetooth keeps its "disabled" option - DISALLOW_BLUETOOTH alone both disables the radio and
     * blocks re-enabling, unlike WiFi where no single restriction does both.
     */
    private fun applyRadioRestrictions(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        policy: PolicyResponse?,
    ) {
        when (policy?.wifiMode ?: "open") {
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

    /**
     * Pushes [PolicyResponse.requireTailscale]/[PolicyResponse.tailscaleExitNodeId] to the
     * Tailscale app as Android managed-app-restrictions (`ForceEnabled`/`ExitNodeID`) - the same
     * `DevicePolicyManager.setApplicationRestrictions` mechanism used for any MDM-manageable app,
     * confirmed against Tailscale's own `app_restrictions.xml`. `ForceEnabled` prevents the user
     * from disconnecting Tailscale from within its own app; `ExitNodeID` forces routing through a
     * specific exit node once one is configured (blank/null means none enforced yet). Always sets
     * the full bundle explicitly, including the "off" case - otherwise a previously-pushed
     * ForceEnabled=true would linger forever after a parent turns the toggle back off, since
     * setApplicationRestrictions replaces the whole bundle rather than merging into it.
     *
     * Also sets Android's always-on-VPN-with-lockdown requirement via
     * [DevicePolicyManager.setAlwaysOnVpnPackage] - a separate, OS-level enforcement layer on top
     * of the managed-config bundle above. `ForceEnabled` only binds Tailscale's own in-app UI; it
     * turned out Tailscale's own Quick Settings tile doesn't check the same flag, so a kid could
     * still disconnect from there. `setAlwaysOnVpnPackage` is enforced by the OS's connectivity
     * stack directly, independent of the VPN app's own cooperation - the real fix, not a
     * replacement for `ForceEnabled` (which is still useful for the in-app UI). Kept as its own
     * try/catch so a failure here (e.g. Tailscale not yet fully registered as a VpnService on
     * first run) can't block the managed-config push above, or vice versa.
     *
     * Lockdown is only ever engaged when an exit node is also configured. Tailscale is a
     * split-tunnel VPN by design - it only routes tailnet-destined traffic through its tunnel, not
     * general internet traffic, unless an exit node is set. Lockdown forces *all* traffic through
     * the VPN interface regardless; without an exit node, Tailscale's tunnel has nowhere to send
     * non-tailnet packets, so they're just dropped - total loss of connectivity, confirmed live
     * (this is Tailscale's own documented behavior, not a bug on our end - see
     * github.com/tailscale/tailscale#12925 and #1568). An admin checking "Require Tailscale"
     * without also setting an exit node must never be able to brick the phone's network - so
     * lockdown only engages once both are present; `ForceEnabled` alone still applies regardless.
     */
    private fun applyVpnRestrictions(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        policy: PolicyResponse?,
    ) {
        val bundle = Bundle().apply {
            putBoolean("ForceEnabled", policy?.requireTailscale == true)
            val exitNodeId = policy?.tailscaleExitNodeId?.trim()
            if (!exitNodeId.isNullOrEmpty()) {
                putString("ExitNodeID", exitNodeId)
            }
        }
        try {
            dpm.setApplicationRestrictions(admin, TAILSCALE_PACKAGE, bundle)
        } catch (e: Exception) {
            // Fails soft (e.g. Tailscale not installed yet) - never let this break the rest of a
            // sync cycle, matching every other DPM call in this file.
            Log.w(LOG_TAG, "Failed to apply Tailscale managed restrictions", e)
        }

        try {
            val exitNodeConfigured = !policy?.tailscaleExitNodeId?.trim().isNullOrEmpty()
            if (policy?.requireTailscale == true && exitNodeConfigured) {
                dpm.setAlwaysOnVpnPackage(admin, TAILSCALE_PACKAGE, true)
            } else {
                dpm.setAlwaysOnVpnPackage(admin, null, false)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set always-on VPN", e)
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
