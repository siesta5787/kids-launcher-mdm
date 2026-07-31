package com.kidslauncher.mdm.server

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.server.dto.InstalledApp
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import com.kidslauncher.mdm.preferences.LauncherPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
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
            )
        )
        // The report just landed, so this doesn't need to stay pending - if it was never used,
        // this is a harmless false->false write.
        mdm.offlineOverrideUsedPendingReport(false)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Status report failed", e)
    }

    checkForLauncherUpdate(context, api)

    return freshPolicy != null
}

/**
 * Downloads and silently installs a newer launcher build if the server has one, via
 * [LauncherUpdater]. Best-effort like the status report above - a failed check/download just
 * tries again next cycle, it never affects anything else in this sync.
 */
private suspend fun checkForLauncherUpdate(context: Context, api: MdmApi) {
    try {
        val update = api.getLauncherUpdate().body() ?: return
        if (update.versionCode <= BuildConfig.VERSION_CODE) return
        if (update.versionCode == LauncherPreferences.mdm().lastFailedUpdateVersionCode()) {
            // Already tried and failed (e.g. signing-certificate mismatch, which will never
            // resolve itself) - don't turn a permanently-broken update into an infinite
            // download+install loop every 2 minutes. A different (newer) version is still tried.
            return
        }

        val body = api.downloadLauncherUpdate().body() ?: return
        val apkFile = File(context.cacheDir, "launcher_update.apk")
        body.byteStream().use { input ->
            apkFile.outputStream().use { output -> input.copyTo(output) }
        }
        Log.i(LOG_TAG, "Downloaded launcher update ${update.versionName} (code ${update.versionCode}), installing")
        LauncherUpdater.installSilently(context, apkFile, update.versionCode)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Launcher update check failed", e)
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
                val info = pm.getApplicationInfo(packageName, 0)
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
