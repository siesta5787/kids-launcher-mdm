package com.kidslauncher.mdm.server

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

private const val LOG_TAG = "AppInstaller"
const val APP_INSTALL_ACTION = "com.kidslauncher.mdm.APP_INSTALL_RESULT"
const val APP_INSTALL_APK_PATH_EXTRA = "apk_path"
const val APP_INSTALL_PACKAGE_NAME_EXTRA = "package_name"
const val APP_INSTALL_RELEASE_TAG_EXTRA = "release_tag"

/**
 * Silently installs a downloaded APK for any tracked app - a third-party app (e.g. Tailscale) or
 * the launcher's own self-update, both go through this exact same function now - via Device
 * Owner's [PackageInstaller] privilege. [PackageInstaller.SessionParams.MODE_FULL_INSTALL]
 * determines install-vs-update purely from the APK's own embedded package name, so no special
 * handling is needed either way at this layer; the one place self-update genuinely differs is
 * [AppInstallReceiver] skipping its cache-file cleanup on success, since installing over yourself
 * risks the process dying before that line gets to run.
 */
object AppInstaller {

    fun installSilently(context: Context, apkFile: File, packageName: String, releaseTag: String) {
        val packageInstaller = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        try {
            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite(packageName, 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                val resultIntent = Intent(APP_INSTALL_ACTION)
                    .setPackage(context.packageName)
                    .putExtra(APP_INSTALL_APK_PATH_EXTRA, apkFile.absolutePath)
                    .putExtra(APP_INSTALL_PACKAGE_NAME_EXTRA, packageName)
                    .putExtra(APP_INSTALL_RELEASE_TAG_EXTRA, releaseTag)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                val pendingIntent =
                    PendingIntent.getBroadcast(context, sessionId, resultIntent, flags)
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to start silent install of $packageName", e)
            apkFile.delete()
        }
    }
}
