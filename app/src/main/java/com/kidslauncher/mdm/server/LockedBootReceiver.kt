package com.kidslauncher.mdm.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val LOG_TAG = "LockedBootReceiver"

/**
 * Starts [CommandListenerService] in locked mode on `LOCKED_BOOT_COMPLETED` - the earliest point
 * after a reboot, before the first unlock - so Find My Device keeps working on a phone that has
 * rebooted and is sitting at its lock screen. Deliberately not started from
 * [com.kidslauncher.mdm.Application.onCreate] instead: that runs during boot itself (HomeActivity is
 * the HOME screen boot waits on), and tsnet's native code crashing there could stall boot, whereas
 * by `LOCKED_BOOT_COMPLETED` boot has already finished and a crash only costs this service (with
 * [TsnetClient]'s crash-loop guard stopping retries after two).
 *
 * Only for an enrolled device that has already joined the tailnet - without both there's nothing
 * to connect to yet, and normal startup after unlock covers setup as before.
 */
class LockedBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        if (BootState.credentials(context) == null || !TsnetClient.hasSavedIdentity(context)) {
            Log.i(LOG_TAG, "Not enrolled or not joined to the tailnet yet - nothing to start")
            return
        }
        CommandListenerService.start(context)
    }
}
