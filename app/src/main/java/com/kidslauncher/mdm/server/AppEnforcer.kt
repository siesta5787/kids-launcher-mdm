package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.ui.HomeActivity

private const val LOG_TAG = "AppEnforcer"

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
 * So: third-party (non-system) apps are always included, and system apps only if they expose a
 * launcher (home-screen) icon - see the function's own doc comment for why this replaced an
 * earlier hand-maintained package-name allowlist.
 */
internal fun controllablePackages(pm: PackageManager): List<String> {
    // MATCH_UNINSTALLED_PACKAGES is required here, or this list silently drops any package this
    // same enforcer has already hidden via setApplicationHidden - PackageManager excludes hidden
    // packages from getInstalledApplications() by default. Without this flag, a hidden app can
    // never be found again to un-hide it: a permanent one-way lock, and exactly the bug this
    // function was written to avoid in the first place (see the class-level doc above).
    //
    // System apps are included only if they expose a launcher (home-screen) icon - a real,
    // user-facing app a kid could actually open - rather than via a hand-maintained package-name
    // allowlist. The allowlist approach (this file's history) worked for the GrapheneOS test
    // device but left a GMS/OEM device (Chrome, Play Store, Gmail, YouTube, Maps, the
    // manufacturer's own Camera/Gallery/Messages, ...) with almost nothing controllable, since
    // none of those package names were in the list. A launcher-intent check is device-agnostic:
    // it naturally includes exactly the apps a kid can tap open, and naturally excludes headless
    // system services/components (SystemUI, telephony internals, resource overlays, ...) since
    // those don't expose a launcher activity in the first place - without needing to enumerate
    // every OEM's package names by hand. MATCH_UNINSTALLED_PACKAGES on this query too, for the
    // same already-hidden-app reason as above.
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val launchablePackages = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        .mapNotNull { it.activityInfo?.packageName }
        .toSet()

    return pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        .filter { info ->
            (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                info.packageName in launchablePackages
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
                // Both calls can fail *without* throwing - setPackagesSuspended returns the
                // subset of package names it couldn't act on (some privileged/protected system
                // packages silently refuse suspension by platform policy) and
                // setApplicationHidden returns a plain boolean. Discarding these previously made
                // that failure mode invisible - confirmed live with a carrier-privileged /product-
                // partition system app that stayed reachable despite being correctly excluded
                // from the allowlist, with nothing in logs to explain why.
                val notSuspended = dpm.setPackagesSuspended(admin, arrayOf(packageName), shouldBeSuspended)
                if (!notSuspended.isNullOrEmpty()) {
                    Log.w(LOG_TAG, "Platform refused to ${if (shouldBeSuspended) "suspend" else "unsuspend"} $packageName (setPackagesSuspended)")
                }
                val hiddenOk = dpm.setApplicationHidden(admin, packageName, shouldBeSuspended)
                if (!hiddenOk) {
                    Log.w(LOG_TAG, "Platform refused to ${if (shouldBeSuspended) "hide" else "unhide"} $packageName (setApplicationHidden)")
                }
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

        // Deliberately reads vpnFilterEnabled off the raw policy (fresh or cached-last-known - see
        // MdmSyncWorker), not effectivePolicy, which goes null during an active offline override or
        // the pause-restrictions kill-switch. Those exist to lift *access* restrictions (allowlist,
        // kiosk, radios) when something's broken - they were never meant to also override a parent's
        // separate, deliberate content-filtering choice. Only defaults to true (filtering on) for a
        // device that's never completed a single sync, which has no admin choice to respect yet.
        applyVpnRestrictions(context, dpm, admin, policy?.vpnFilterEnabled ?: true)

        applyPrivateDnsLock(dpm, admin)
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
     * Sets Android's always-on-VPN requirement on the launcher's own package via
     * [DevicePolicyManager.setAlwaysOnVpnPackage] - [KidVpnService] is now the device's only VPN
     * (the standalone Tailscale app and its managed-config/exit-node plumbing are retired; tsnet is
     * embedded directly, see [TsnetClient], and doesn't register as a VpnService at all). This is
     * what makes Android auto-start/restart the service as needed, independent of anything this app
     * does itself.
     *
     * Lockdown is deliberately NOT enabled here - unconditional, not gated on any policy field,
     * because it's actively wrong for this VPN's design, not just risky. Confirmed live: with
     * lockdown on, once this VPN becomes the system default network, `dumpsys connectivity` showed
     * its routes as only the one fake-DNS-server address plus an explicit `::/0 unreachable` -
     * [KidVpnService] deliberately never adds a general/default route (see that class's doc comment
     * on why: it's what makes the "everything else flows over the real network untouched" design
     * work at all when NOT locked down). Lockdown forces every app's traffic onto this network
     * regardless of what routes it declares, so with no default route to fall back to, general
     * internet connectivity broke device-wide - not a hypothetical, reproduced on the very first
     * live test of this code. This is the exact same failure mode as the old
     * Tailscale-without-an-exit-node bug (github.com/tailscale/tailscale#12925) that motivated
     * gating lockdown on an exit node being configured for that VPN - except here there's no
     * equivalent "configure a broader route" escape hatch to gate on, since narrow routing is
     * permanent by design, not a transient unconfigured state. Without lockdown, Android's
     * always-on designation still auto-restarts the service and still blocks a kid from disabling
     * it via Settings (both Device-Owner-enforced); the only thing lost is that DNS briefly goes
     * unfiltered through the OS's normal path if the service is ever down, which is an acceptable
     * gap next to bricking the device's entire network.
     *
     * [vpnFilterEnabled] is [PolicyResponse.vpnFilterEnabled] - a per-device admin toggle for the
     * filter itself (independent of the lockdown discussion above). When off, both the always-on
     * designation and the running service are torn down; when on, both are (re)established. Also
     * caches the value so [com.kidslauncher.mdm.Application.onCreate]'s cold-start
     * [KidVpnService.start] call - which runs before any policy has ever been fetched - knows
     * whether to start the service at all, rather than always starting and then immediately
     * stopping it again once this function runs on the first sync.
     */
    private fun applyVpnRestrictions(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        vpnFilterEnabled: Boolean,
    ) {
        LauncherPreferences.mdm().vpnFilterEnabled(vpnFilterEnabled)
        try {
            if (vpnFilterEnabled) {
                dpm.setAlwaysOnVpnPackage(admin, context.packageName, false)
                KidVpnService.start(context)
            } else {
                dpm.setAlwaysOnVpnPackage(admin, null, false)
                KidVpnService.stop(context)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to apply VPN filter enabled state", e)
        }
    }

    /**
     * Locks Android's system-wide Private DNS to Opportunistic (never a specific host - the retired
     * DoT-to-Pi approach's `setGlobalPrivateDnsModeSpecifiedHost` call lived here previously, see
     * this repo's CLAUDE.md) and prevents it being switched away via
     * [UserManager.DISALLOW_CONFIG_PRIVATE_DNS]. Unconditional on every `apply()` call, not gated on
     * any policy field - closes the one gap [KidVpnService]'s DNS filtering can't otherwise cover on
     * its own: a kid manually switching Private DNS to Strict mode against some other resolver would
     * produce encrypted DoT traffic on port 853 that this app can't inspect, silently bypassing
     * filtering entirely. Opportunistic mode, by contrast, only *attempts* DoT and transparently
     * falls back to plain port-53 DNS if that fails - which is exactly what happens against
     * [KidVpnService]'s own fake DNS server, since it deliberately doesn't answer on port 853 (see
     * that class's doc comment on why it must stay DoT-silent for this to work).
     */
    private fun applyPrivateDnsLock(
        dpm: DevicePolicyManager,
        admin: ComponentName,
    ) {
        try {
            dpm.setGlobalPrivateDnsModeOpportunistic(admin)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to apply Private DNS lock", e)
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
