package com.kidslauncher.mdm.server

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

private const val LOG_TAG = "LauncherUpdater"
const val LAUNCHER_UPDATE_INSTALL_ACTION = "com.kidslauncher.mdm.LAUNCHER_UPDATE_INSTALL_RESULT"
const val LAUNCHER_UPDATE_APK_PATH_EXTRA = "apk_path"
const val LAUNCHER_UPDATE_VERSION_CODE_EXTRA = "version_code"

/**
 * Silently installs a downloaded launcher APK via Device Owner's [PackageInstaller] privilege -
 * no "allow unknown sources" prompt, no confirmation dialog, unlike a normal app self-updating.
 * The actual success/failure callback arrives later via [LauncherUpdateInstallReceiver], since
 * [PackageInstaller.Session.commit] is asynchronous.
 */
object LauncherUpdater {

    fun installSilently(context: Context, apkFile: File, versionCode: Int) {
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
                    session.openWrite("launcher_update", 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                val resultIntent = Intent(LAUNCHER_UPDATE_INSTALL_ACTION)
                    .setPackage(context.packageName)
                    .putExtra(LAUNCHER_UPDATE_APK_PATH_EXTRA, apkFile.absolutePath)
                    .putExtra(LAUNCHER_UPDATE_VERSION_CODE_EXTRA, versionCode)
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
            Log.w(LOG_TAG, "Failed to start silent install", e)
            apkFile.delete()
        }
    }
}
