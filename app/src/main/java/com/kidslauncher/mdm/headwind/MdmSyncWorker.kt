package com.kidslauncher.mdm.headwind

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kidslauncher.mdm.headwind.dto.KidModePolicy
import com.kidslauncher.mdm.headwind.dto.KidModeStatusReport
import com.kidslauncher.mdm.preferences.LauncherPreferences
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.Calendar
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "MdmSyncWorker"
private const val SYNC_INTERVAL_MINUTES = 15L
private const val WORK_NAME = "mdm_sync"

/**
 * Combined heartbeat + KidMode sync: config fetch/renumber, KidMode policy fetch/cache/evaluate,
 * app allowlist + kiosk enforcement, best-effort status report. Shared by [MdmSyncWorker]'s
 * periodic run and the Settings screen's "Sync now" dev action, so both go through the exact
 * same logic.
 */
suspend fun performMdmSync(context: Context) {
    val mdm = LauncherPreferences.mdm()
    val serverUrl = mdm.serverUrl()
    val deviceNumber = mdm.deviceNumber()
    if (serverUrl.isNullOrBlank() || deviceNumber.isNullOrBlank()) {
        return
    }

    val api = createMdmApi(serverUrl)

    // Heartbeat, doubling as the device-renumber check.
    try {
        val response = api.getConfig(deviceNumber)
        val newNumber = response.body()?.data?.newNumber
        if (!newNumber.isNullOrBlank() && newNumber != deviceNumber) {
            Log.i(LOG_TAG, "Device renumbered from $deviceNumber to $newNumber")
            mdm.deviceNumber(newNumber)
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Heartbeat/config fetch failed", e)
    }

    val currentDeviceNumber = mdm.deviceNumber() ?: deviceNumber

    val policy = fetchKidModePolicy(api, currentDeviceNumber)
        ?: mdm.kidModePolicy()?.let { decodeCachedPolicy(it) }

    val reason = KidModeEnforcer.evaluate(policy, Calendar.getInstance())
    mdm.lockReason(reason)

    AppEnforcer.apply(context, policy)

    // Best-effort - a failed report must never affect the lock decision above.
    try {
        api.sendKidModeStatus(
            currentDeviceNumber,
            KidModeStatusReport(locked = reason != LockReason.NONE, lockReason = reason.name)
        )
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Status report failed", e)
    }
}

private suspend fun fetchKidModePolicy(api: MdmApi, deviceNumber: String): KidModePolicy? {
    return try {
        val policy = api.getKidModePolicy(deviceNumber).body()?.data
        if (policy != null) {
            LauncherPreferences.mdm().kidModePolicy(HeadwindJson.encodeToString(policy))
        }
        policy
    } catch (e: Exception) {
        Log.w(LOG_TAG, "KidMode policy fetch failed, falling back to cache", e)
        null
    }
}

private fun decodeCachedPolicy(json: String): KidModePolicy? {
    return try {
        HeadwindJson.decodeFromString(json)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Failed to decode cached KidMode policy", e)
        null
    }
}

/**
 * Combined heartbeat + KidMode periodic sync, every 15 minutes (WorkManager's practical floor for
 * periodic work). Deliberately has no [androidx.work.Constraints] - the lock decision must still
 * evaluate on schedule even offline ([KidModeEnforcer] falls back to the last-cached policy);
 * only the network calls inside should fail gracefully.
 */
class MdmSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        performMdmSync(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequest.Builder(
                MdmSyncWorker::class.java,
                SYNC_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
