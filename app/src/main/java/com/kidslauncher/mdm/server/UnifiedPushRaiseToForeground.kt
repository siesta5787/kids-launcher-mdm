/*
 * Ported from ntfy-android (https://github.com/binwiederhier/ntfy-android),
 * app/src/main/java/io/heckel/ntfy/up/RaiseAppToForeground.kt and RaiseAppToForegroundFactory.kt.
 * Copyright (c) Philipp C. Heckel and contributors, licensed under the Apache License 2.0.
 * Changes: package/constants adapted to this app, log tags, comments trimmed.
 */

package com.kidslauncher.mdm.server

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val RAISE_LOG_TAG = "UnifiedPushRaiseFg"
private const val ACTION_RAISE_TO_FOREGROUND = "org.unifiedpush.android.connector.RAISE_TO_FOREGROUND"

/**
 * Raises the target app to foreground importance before delivering a UnifiedPush MESSAGE, as the
 * UnifiedPush Android spec (AND_3) requires: "MUST raise the application to the foreground
 * importance for 5 seconds when sending this intent", here by binding to the app's
 * RAISE_TO_FOREGROUND service (which the UnifiedPush connector library provides). A foreground
 * process binding to a service lends that process its importance, so the app can start its own
 * foreground service from the background (Android 12+ restriction) to fetch the message. Without
 * this, e.g. Molly/Kids MDM IM can't start their fetch service and notifications lag.
 *
 * This app is "foreground" for this purpose because [CommandListenerService] is a foreground
 * service. Messages are queued while binding and sent once connected; the binding is released 5 s
 * after the last message. If the app has no such service (or we're not foreground), the message
 * is just broadcast, as before.
 */
class UnifiedPushRaiseToForeground(private val context: Context, private val app: String, private val onUnbound: () -> Unit) : ServiceConnection, Runnable {
    class Message(val token: String, val content: ByteArray)

    private enum class Bound { Binding, Bound, Unbound }

    private var bound = Bound.Unbound
    private var scheduledFuture: ScheduledFuture<*>? = null
    private val msgsQueue = mutableListOf<Message>()

    private val foregroundImportance = listOf(
        RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
        RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE,
    )

    /** @return `true` if the app was raised to the foreground for this message. */
    fun raiseAndSend(token: String, message: ByteArray): Boolean {
        val msg = Message(token, message)
        synchronized(this) {
            when (bound) {
                Bound.Bound -> {
                    delayUnbinding()
                    send(msg)
                }

                Bound.Binding -> {
                    delayUnbinding()
                    queue(msg)
                }

                Bound.Unbound -> {
                    val isForeground = checkForeground()
                    val targetHasService = hasRaiseToForegroundService()
                    if (isForeground && targetHasService) {
                        bind()
                        queue(msg)
                    } else {
                        Log.d(RAISE_LOG_TAG, "Cannot raise $app to foreground: isForeground=$isForeground, targetHasService=$targetHasService")
                        send(msg)
                        return false
                    }
                }
            }
            return true
        }
    }

    private fun checkForeground(): Boolean {
        val appProcesses = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses ?: return false
        return appProcesses.any { it.importance in foregroundImportance }
    }

    private fun hasRaiseToForegroundService(): Boolean {
        val intent = Intent(ACTION_RAISE_TO_FOREGROUND).setPackage(app)
        return context.packageManager.queryIntentServices(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER.toLong()),
        ).any { it.serviceInfo.exported }
    }

    private fun send(message: Message) {
        context.sendBroadcast(
            Intent(ACTION_MESSAGE)
                .setPackage(app)
                .putExtra(EXTRA_TOKEN, message.token)
                .putExtra(EXTRA_BYTES_MESSAGE, message.content)
        )
    }

    private fun queue(message: Message) {
        msgsQueue.add(message)
    }

    /** Already bound or binding: push the unbinding back to 5 s from now. */
    private fun delayUnbinding() {
        scheduledFuture?.cancel(true)
        scheduledFuture = unbindExecutor.schedule(this, 5L, TimeUnit.SECONDS)
    }

    private fun bind() {
        Log.d(RAISE_LOG_TAG, "Binding to $app")
        val intent = Intent(ACTION_RAISE_TO_FOREGROUND).setPackage(app)
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
        scheduledFuture = unbindExecutor.schedule(this, 5L, TimeUnit.SECONDS)
        bound = Bound.Binding
    }

    private fun unbind() {
        synchronized(this) {
            if (bound != Bound.Unbound) {
                // Anything still queued never got a connection: deliver it without the raise
                // rather than drop it (ntfy clears the queue here).
                msgsQueue.forEach { send(it) }
                msgsQueue.clear()
                context.unbindService(this)
                bound = Bound.Unbound
                onUnbound()
                Log.d(RAISE_LOG_TAG, "Unbound from $app")
            }
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        synchronized(this) {
            bound = Bound.Bound
            msgsQueue.forEach { send(it) }
            msgsQueue.clear()
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) = unbind()

    override fun onBindingDied(name: ComponentName?) = unbind()

    override fun onNullBinding(name: ComponentName?) = unbind()

    /** The 5 s timeout. */
    override fun run() = unbind()

    private companion object {
        val unbindExecutor = Executors.newSingleThreadScheduledExecutor()
    }
}

/** One [UnifiedPushRaiseToForeground] per target app at a time, instead of one binding per message. */
object UnifiedPushRaiseToForegroundFactory {
    private val instances: MutableMap<String, UnifiedPushRaiseToForeground> = mutableMapOf()

    fun getInstance(context: Context, app: String): UnifiedPushRaiseToForeground {
        synchronized(this) {
            return instances[app] ?: UnifiedPushRaiseToForeground(context.applicationContext, app, onUnbound = { remove(app) })
                .also { instances[app] = it }
        }
    }

    private fun remove(app: String) {
        synchronized(this) {
            instances.remove(app)
        }
    }
}
