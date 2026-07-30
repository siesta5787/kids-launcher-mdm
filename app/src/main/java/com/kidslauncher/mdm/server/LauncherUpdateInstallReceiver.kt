package com.kidslauncher.mdm.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import java.io.File

private const val LOG_TAG = "LauncherUpdateInstallReceiver"

/**
 * Receives the async result of [LauncherUpdater.installSilently]. Manifest-registered (not
 * dynamically) so delivery doesn't depend on this app's process still being alive when the
 * install actually completes.
 */
class LauncherUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val apkPath = intent.getStringExtra(LAUNCHER_UPDATE_APK_PATH_EXTRA)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(LOG_TAG, "Launcher self-update installed successfully")
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Shouldn't happen as device owner with USER_ACTION_NOT_REQUIRED, but if it does
                // there's nothing this receiver can silently do about it - just log for
                // visibility rather than leaving the downloaded file behind unexplained.
                Log.w(LOG_TAG, "Launcher update requires user action unexpectedly: $message")
            }
            else -> {
                Log.w(LOG_TAG, "Launcher self-update install failed: status=$status message=$message")
            }
        }

        // Not deleted on success too: the running process is about to be replaced anyway, and
        // there's a real chance this callback never gets to run before that happens.
        if (status != PackageInstaller.STATUS_SUCCESS) {
            apkPath?.let { File(it).delete() }
        }
    }
}
