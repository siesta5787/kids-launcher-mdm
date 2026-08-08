package com.kidslauncher.mdm.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import java.io.File

private const val LOG_TAG = "AppInstallReceiver"

/**
 * Receives the async result of [AppInstaller.installSilently], for every tracked app including
 * the launcher's own self-update. Manifest-registered (not dynamically) so delivery doesn't
 * depend on this app's process still being alive when the install actually completes - which
 * matters most for self-update: installing an update over the currently-running app can kill/
 * replace the process mid-flight, so on success this deliberately skips deleting the cache file
 * when the installed package is this app itself, rather than risk racing that replacement. Any
 * other package is always safe to clean up immediately regardless of outcome, since this app's
 * own process is never the one being replaced.
 */
class AppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val apkPath = intent.getStringExtra(APP_INSTALL_APK_PATH_EXTRA)
        val installKey = intent.getStringExtra(APP_INSTALL_KEY_EXTRA)
        val isLauncher = intent.getBooleanExtra(APP_INSTALL_IS_LAUNCHER_EXTRA, false)
        val releaseTag = intent.getStringExtra(APP_INSTALL_RELEASE_TAG_EXTRA)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(LOG_TAG, "Installed $installKey successfully ($releaseTag)")
                if (installKey != null && releaseTag != null) {
                    TrackedAppUpdateState.recordInstalled(context, installKey, releaseTag)
                }
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Shouldn't happen as device owner with USER_ACTION_NOT_REQUIRED, but if it does
                // there's nothing this receiver can silently do about it - just log for
                // visibility rather than leaving the downloaded file behind unexplained.
                Log.w(LOG_TAG, "Install of $installKey requires user action unexpectedly: $message")
            }
            else -> {
                Log.w(LOG_TAG, "Install of $installKey failed: status=$status message=$message")
                // Remembering the failed tag stops the next sync from re-attempting the exact
                // same doomed install every 2 minutes forever.
                if (installKey != null && releaseTag != null) {
                    TrackedAppUpdateState.recordFailed(context, installKey, releaseTag)
                }
            }
        }

        // Not deleted on success for the launcher's own self-update: the running process is about
        // to be replaced anyway, and there's a real chance this callback never gets to finish
        // running before that happens. Any other app's file is always safe to clean up
        // immediately. isLauncher comes from the server (TrackedAppUpdate.isLauncher), not a
        // packageName == context.packageName comparison - a tracked app's package name is optional
        // now (see kid-phone-server's tracked_app_add.html) and can't be trusted for this.
        if (status != PackageInstaller.STATUS_SUCCESS || !isLauncher) {
            apkPath?.let { File(it).delete() }
        }
    }
}
