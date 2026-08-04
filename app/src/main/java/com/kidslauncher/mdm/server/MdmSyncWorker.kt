package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.server.dto.CommandResultRequest
import com.kidslauncher.mdm.server.dto.InstalledApp
import com.kidslauncher.mdm.server.dto.LocationReport
import com.kidslauncher.mdm.server.dto.PendingCommand
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import com.kidslauncher.mdm.preferences.LauncherPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.util.Calendar
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "MdmSyncWorker"
private const val SYNC_INTERVAL_MINUTES = 2L
private const val WORK_NAME = "mdm_sync"

/**
 * Combined heartbeat + policy sync: policy fetch/cache/evaluate, app allowlist + kiosk
 * enforcement, best-effort status report. Shared by [MdmSyncWorker]'s periodic run and the
 * Settings screen's "Sync now" dev action, so both go through the exact same logic.
 *
 * Returns true only if the server was actually reached this cycle (a fresh policy fetch
 * succeeded) - every sub-step below (status report, update check) already fails silently and
 * falls back to cached state on its own, so this is the one signal that reflects whether real
 * network contact happened, for callers like the "Sync now" button that want to tell the user
 * the truth about whether it worked.
 */
suspend fun performMdmSync(context: Context): Boolean {
    val mdm = LauncherPreferences.mdm()
    val serverUrl = mdm.serverUrl()
    val deviceToken = mdm.deviceToken()
    if (serverUrl.isNullOrBlank() || deviceToken.isNullOrBlank()) {
        return false
    }

    val api = createMdmApi(serverUrl, deviceToken)
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)

    val freshPolicy = fetchPolicy(api)
    if (freshPolicy != null) {
        // Real server contact just succeeded - the offline override's whole job (bridging the gap
        // until the device can hear from the server again) is done, so let real policy reassert
        // immediately rather than waiting out the rest of its time window.
        OfflineOverride.clear()
        // Cache the hash+salt into their own preference slots (not just inside the serialized
        // kid_mode_policy blob) - this is what lets OfflineOverride verify a locally-entered PIN
        // with zero network at all, which is the entire point of the offline failsafe.
        mdm.overridePinHash(freshPolicy.overridePinHash)
        mdm.overridePinSalt(freshPolicy.overridePinSalt)
        // Only ever dispatched off a genuinely fresh fetch, never the cached fallback below - the
        // cached policy blob can still hold a `pendingCommand` from a past cycle that's already
        // been delivered and consumed server-side, and replaying it from cache while offline would
        // re-run an old command (harmless for ring, not for lock/wipe).
        dispatchPendingCommand(context, api, dpm, admin, freshPolicy.pendingCommand)
    }
    val policy = freshPolicy ?: mdm.kidModePolicy()?.let { decodeCachedPolicy(it) }

    val reason = if (OfflineOverride.isActive() || mdm.restrictionsPaused()) LockReason.NONE else {
        KidModeEnforcer.evaluate(policy, Calendar.getInstance())
    }
    mdm.lockReason(reason)

    AppEnforcer.apply(context, policy)

    // Best-effort - a failed report must never affect the lock decision above.
    try {
        api.sendStatus(
            StatusReportRequest(
                lockReason = reason.name,
                kioskEngaged = mdm.kioskEnabled(),
                installedApps = collectInstalledApps(context),
                appVersion = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
                offlineOverrideUsed = mdm.offlineOverrideUsedPendingReport(),
                location = currentLocationReport(context, dpm, admin),
            )
        )
        // The report just landed, so this doesn't need to stay pending - if it was never used,
        // this is a harmless false->false write.
        mdm.offlineOverrideUsedPendingReport(false)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Status report failed", e)
    }

    checkForTrackedAppUpdates(context, api)

    return freshPolicy != null
}

/**
 * Find My Device's remote-command dispatch - ring/lock/wipe, or `locate` (a no-op here; a location
 * reading is already attached to every status report regardless, via [currentLocationReport] below,
 * so `locate` exists purely as a way for the admin site to nudge an out-of-cycle report sooner, not
 * a distinct on-device action). No result is ever reported for `wipe` - the device is gone by the
 * time it would report back.
 */
private suspend fun dispatchPendingCommand(
    context: Context,
    api: MdmApi,
    dpm: DevicePolicyManager,
    admin: ComponentName,
    pending: PendingCommand?,
) {
    if (pending == null || !dpm.isDeviceOwnerApp(context.packageName)) return

    when (pending.command) {
        "ring" -> {
            LocateCommands.ring(context)
            reportCommandResult(api, pending.id, success = true, message = "ringing")
        }

        "lock" -> {
            val ok = LocateCommands.lock(dpm, admin)
            reportCommandResult(api, pending.id, ok, if (ok) "locked" else "failed to lock")
        }

        "wipe" -> LocateCommands.wipe(dpm, admin)

        "locate" -> reportCommandResult(api, pending.id, success = true, message = "attached to next report")

        else -> Log.w(LOG_TAG, "Unknown pending command: ${pending.command}")
    }
}

private suspend fun reportCommandResult(api: MdmApi, commandId: Long, success: Boolean, message: String?) {
    try {
        api.sendCommandResult(CommandResultRequest(commandId, success, message))
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Failed to report command result for id=$commandId", e)
    }
}

private fun currentLocationReport(
    context: Context,
    dpm: DevicePolicyManager,
    admin: ComponentName,
): LocationReport? {
    if (!dpm.isDeviceOwnerApp(context.packageName)) return null
    val location = LocateCommands.currentLocation(context, dpm, admin) ?: return null
    return LocationReport(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
        capturedAt = Instant.ofEpochMilli(location.time).toString(),
    )
}

/**
 * Downloads and silently installs a newer release for every app tracked server-side (see
 * kid-phone-server's `handlers::tracked_apps`) - either from a GitHub repo's Releases (e.g.
 * Tailscale) or manually uploaded by an admin. The launcher's own self-update goes through this
 * exact same path now too - it's just another tracked app server-side, package name
 * [BuildConfig.APPLICATION_ID] - so there's no special-cased launcher-update code here at all.
 * The one real place self-update still differs is [AppInstallReceiver] skipping its cache-file
 * cleanup on success, since installing over yourself risks the process dying before that line
 * runs. One app's failure never affects another's, or the rest of the sync.
 */
private suspend fun checkForTrackedAppUpdates(context: Context, api: MdmApi) {
    val updates = try {
        api.getTrackedAppUpdates().body() ?: return
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Tracked app update check failed", e)
        return
    }

    val state = TrackedAppUpdateState.load()
    for (update in updates) {
        val known = state[update.packageName]
        if (update.releaseTag == known?.lastInstalledTag || update.releaseTag == known?.lastFailedTag) {
            continue
        }

        try {
            val body = api.downloadTrackedApp(update.downloadUrl).body() ?: continue
            val apkFile = File(context.cacheDir, "tracked_app_${update.packageName}.apk")
            body.byteStream().use { input ->
                apkFile.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(LOG_TAG, "Downloaded ${update.packageName} ${update.releaseTag}, installing")
            AppInstaller.installSilently(context, apkFile, update.packageName, update.releaseTag)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Update check failed for ${update.packageName}", e)
        }
    }
}

/**
 * Reports {packageName, label} for every app [AppEnforcer] is actually willing to suspend/hide,
 * so the admin site's allowlist checkboxes exactly match what checking one of them can affect -
 * see [controllablePackages] for why this is neither the launcher's own `Application.apps` list
 * (excludes already-hidden apps, a permanent lockout) nor a raw unfiltered PackageManager query
 * (would include core OS packages unsafe to ever suspend).
 */
private fun collectInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    return controllablePackages(pm)
        .filter { it != context.packageName }
        .mapNotNull { packageName ->
            try {
                // Same MATCH_UNINSTALLED_PACKAGES requirement as controllablePackages() - flags=0
                // throws NameNotFoundException for a hidden package just like it gets silently
                // excluded from getInstalledApplications(0), which would otherwise drop any
                // currently-unchecked app right back out of this report.
                val info = pm.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                InstalledApp(packageName = packageName, label = pm.getApplicationLabel(info).toString())
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
        .distinctBy { it.packageName }
}

private suspend fun fetchPolicy(api: MdmApi): PolicyResponse? {
    return try {
        val policy = api.getPolicy().body()
        if (policy != null) {
            LauncherPreferences.mdm().kidModePolicy(ServerJson.encodeToString(policy))
        }
        policy
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Policy fetch failed, falling back to cache", e)
        null
    }
}

private fun decodeCachedPolicy(json: String): PolicyResponse? {
    return try {
        ServerJson.decodeFromString(json)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Failed to decode cached policy", e)
        null
    }
}

/**
 * The last policy fetched from the server, straight from the local cache - no network call.
 * Lets a purely-local toggle (e.g. Settings' "pause all restrictions" switch) re-run
 * [AppEnforcer.apply] immediately against the real policy instead of either waiting for the next
 * sync or passing `null` (which [AppEnforcer.apply] would otherwise read as "no restrictions" -
 * correct while the pause is being turned ON, but wrong the moment it's turned back OFF).
 */
fun cachedPolicy(): PolicyResponse? =
    LauncherPreferences.mdm().kidModePolicy()?.let { decodeCachedPolicy(it) }

/**
 * Re-checks the bedtime/screen-time lock decision against the last-cached policy and the
 * device's own clock - no network call, so it works offline and doesn't wait for the next sync.
 * The home screen and lock screen both call this on a local timer while visible so the schedule
 * engages and releases promptly on both edges, not just whenever a sync happens to land.
 */
fun reevaluateLockReasonFromCache() {
    val mdm = LauncherPreferences.mdm()
    val policy = mdm.kidModePolicy()?.let { decodeCachedPolicy(it) }
    val reason = if (OfflineOverride.isActive() || mdm.restrictionsPaused()) LockReason.NONE else {
        KidModeEnforcer.evaluate(policy, Calendar.getInstance())
    }
    if (mdm.lockReason() != reason) {
        mdm.lockReason(reason)
    }
}

/**
 * Combined heartbeat + policy sync, every 2 minutes. WorkManager's [androidx.work.PeriodicWorkRequest]
 * has a hard 15-minute floor, too coarse for how quickly a parent expects a change made on the
 * admin site to reach the device - so this self-reschedules as a chain of one-time requests
 * instead, each one enqueuing the next with a 2-minute delay when it finishes. True server push
 * (e.g. UnifiedPush, since this project avoids Google/FCM) would remove the wait entirely and is
 * on the backlog - polling this much tighter is the interim fix.
 *
 * Deliberately has no [androidx.work.Constraints] - the lock decision must still evaluate on
 * schedule even offline ([KidModeEnforcer] falls back to the last-cached policy); only the
 * network calls inside should fail gracefully.
 */
class MdmSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            performMdmSync(applicationContext)
        } finally {
            // In a finally block so the chain can never silently die even if performMdmSync
            // somehow throws unexpectedly - a broken chain would otherwise stop all future syncs
            // until the app next restarts.
            scheduleNext(applicationContext)
        }
        return Result.success()
    }

    companion object {
        /** Call once at app startup - a no-op if the chain is already running. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequest.Builder(MdmSyncWorker::class.java).build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        private fun scheduleNext(context: Context) {
            val request = OneTimeWorkRequest.Builder(MdmSyncWorker::class.java)
                .setInitialDelay(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
