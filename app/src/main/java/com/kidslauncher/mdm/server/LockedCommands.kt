package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.server.dto.LockedReportRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val LOG_TAG = "LockedCommands"

private val lockedCheckMutex = Mutex()

/**
 * Find My Device before the first unlock after a reboot - the cut-down counterpart of
 * [performMdmSync], driven by [CommandListenerService] while it runs in locked mode. Fetches the
 * policy only for its `pendingCommand` (the server marks a command delivered on that fetch, same
 * as a normal sync), runs it through the same [dispatchPendingCommand] (ring/stop_ring/lock/wipe/
 * locate - none of which read credential-encrypted storage), and reports a location via
 * [LockedReportRequest] when asked for one.
 *
 * Everything else a normal sync does - app enforcement, kiosk, lock schedule, updates, journal
 * uploads - needs credential-encrypted storage and simply waits for the unlock; the policy fetched
 * here is deliberately thrown away rather than cached or applied.
 *
 * Must never touch [com.kidslauncher.mdm.preferences.LauncherPreferences], `filesDir` or
 * `cacheDir`, directly or through anything it calls.
 *
 * @param reportLocation also send a fresh location even without a ring/locate command - used once
 *   right after boot, so a lost phone that just rebooted shows up on the map without anyone
 *   having to ask.
 */
suspend fun performLockedCheck(context: Context, reportLocation: Boolean) = lockedCheckMutex.withLock {
    val credentials = BootState.credentials(context) ?: return@withLock
    TsnetClient.connectFromPreferences(context)

    val api = createMdmApi(credentials.serverUrl, credentials.deviceToken)
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)

    val pending = try {
        api.getPolicy().body()?.pendingCommand
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Policy fetch failed", e)
        return@withLock
    }
    if (pending != null) Log.i(LOG_TAG, "Running ${pending.command} before first unlock")
    dispatchPendingCommand(context, api, dpm, admin, pending)

    if (reportLocation || pending?.command in setOf("ring", "locate")) {
        val location = currentLocationReport(context, dpm, admin, forceFresh = true)
        try {
            val response = api.sendLockedReport(LockedReportRequest(location))
            if (!response.isSuccessful) {
                Log.w(LOG_TAG, "Locked report rejected: HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Locked report failed", e)
        }
    }
}
