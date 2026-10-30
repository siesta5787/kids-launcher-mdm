package com.kidslauncher.mdm.server

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.COMMAND_LISTENER_NOTIFICATION_ID
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_LISTENER
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.launcherPreferencesReady
import com.kidslauncher.mdm.preferences.LauncherPreferences
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

private const val LOG_TAG = "CommandListenerService"
private const val INITIAL_RECONNECT_DELAY_MS = 5_000L
private const val MAX_RECONNECT_DELAY_MS = 60_000L
private const val NOT_ENROLLED_RETRY_DELAY_MS = 30_000L
private const val PERIODIC_SYNC_INTERVAL_MS = 5 * 60 * 1000L

/**
 * Holds a long-lived SSE connection open to `/api/devices/commands/stream` so Find My Device's
 * ring/lock/stop-ring/wipe arrive in ~1s instead of waiting for the periodic sync below - a
 * supplement to it, not a replacement: every event received here is a content-free nudge, not the
 * command payload itself, and just triggers an immediate [performMdmSync] early, reusing the
 * exact same policy-fetch/dispatch logic as a normal scheduled sync (see
 * `handlers::device_api::commands_stream` on the server for the matching half of this).
 *
 * Also drives the periodic backstop sync directly, via its own timer - this used to be a separate
 * WorkManager `OneTimeWorkRequest` chain, but confirmed live that it could go quiet for hours on
 * an idle phone with the screen off, most likely Android's Doze/battery-optimization deferring
 * the underlying JobScheduler dispatch (WorkManager isn't exempt from that on its own). This
 * service already pays the cost of an always-on foreground service - exempt from Doze by design,
 * that's the entire point of a foreground service - to hold the SSE connection open, so there's no
 * reason to run a second, less-reliable scheduling mechanism alongside it for the periodic case.
 *
 * A foreground service, not a plain background connection - Android would otherwise throttle or
 * kill a long-lived socket once the app isn't in the foreground, which would defeat the entire
 * point (a lost/screen-off phone is exactly when this matters most). The tradeoff, and there's no
 * way around it, is Android's own mandatory persistent notification for any foreground service -
 * kept at MIN importance and silent, since it's not meant to draw attention the way the ring
 * notification deliberately does.
 *
 * Since this service already pays that foreground-service cost, it also optionally owns
 * [UnifiedPushRelay] (a UnifiedPush distributor for *other* apps on the device, opt-in from
 * Settings, off by default) - one persistent connection/notification doing two jobs instead of
 * a second dedicated distributor app running its own.
 *
 * Also drives [performJournalSync] and [performBrowserHistorySync] off the same two triggers as
 * [performMdmSync] - see [performJournalSync]'s own doc comment for why each is a separate
 * mutex/coroutine rather than folded into [performMdmSync] itself.
 *
 * **Locked mode (v0.52.0+):** direct-boot-aware, started by [LockedBootReceiver] before the first
 * unlock after a reboot, so a lost phone that has rebooted (GrapheneOS's inactivity auto-reboot
 * makes that the common case) is still reachable. While [lockedMode] is true it reads its
 * credentials from [BootState], brings up tsnet itself from the saved identity, and runs
 * [performLockedCheck] instead of the full syncs. [com.kidslauncher.mdm.Application.initAfterUnlock]
 * calls [start] again on unlock, and [onStartCommand] switches to normal mode in place - same
 * process, same tsnet connection. Nothing reachable in locked mode may touch
 * [LauncherPreferences], `filesDir` or `cacheDir`.
 */
class CommandListenerService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val handler = Handler(Looper.getMainLooper())
    private var eventSource: EventSource? = null
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    private var stopped = false
    private var lockedMode = false
    private var bootLocationSent = false

    // Bumped whenever the current stream is deliberately abandoned (mode switch), so callbacks
    // from the old one - including the onFailure its own cancel() triggers - are ignored rather
    // than scheduling a second, duplicate reconnect loop.
    private var streamGeneration = 0

    /**
     * Built fresh on every [connect] call, not cached - the server is now only reachable over the
     * embedded tailnet (see CLAUDE.md's on-device-filtering/embedded-tsnet migration: KidVpnService
     * is the device's sole always-on VPN as of Phase D, so there's no more OS-level MagicDNS/routing
     * from a standalone Tailscale app for a plain client to piggyback on), so this needs
     * [TsnetClient]'s SOCKS5 proxy exactly like [createMdmApi] already uses - and a cached client
     * built before that connects would stay proxy-less forever, the same staleness bug already once
     * fixed in [TsnetClient.connect] itself. [connect] already retries via [scheduleReconnect] until
     * this succeeds, so rebuilding here each time costs nothing extra.
     */
    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            // SSE connections are meant to stay open indefinitely - a normal read timeout would
            // tear this down and force a reconnect every time it elapsed.
            .readTimeout(0, TimeUnit.MILLISECONDS)
        TsnetClient.proxy()?.let { builder.proxy(it) }
        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Must be called promptly after a startForegroundService() launch, before anything else -
        // shown unconditionally (even before enrollment completes) since Android crashes the app
        // if this doesn't happen in time; connect() below handles "not enrolled yet" on its own by
        // retrying rather than needing this to wait for that state first.
        startForeground(COMMAND_LISTENER_NOTIFICATION_ID, buildNotification())
        lockedMode = !launcherPreferencesReady
        if (lockedMode) Log.i(LOG_TAG, "Starting in locked mode (before first unlock)")
        connect()
        schedulePeriodicSync()
        if (!lockedMode) startUnifiedPushIfEnabled()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (lockedMode && launcherPreferencesReady) switchToUnlockedMode()
        return START_STICKY
    }

    /** Piggybacks on this same foreground service/notification rather than running as a second
     * one - see UnifiedPushRelay's own doc comment for why. Off by default (a parent has to opt in
     * from Settings), so this is a no-op on a device where that's never been touched. */
    private fun startUnifiedPushIfEnabled() {
        if (LauncherPreferences.mdm().unifiedpushDistributorEnabled()) {
            UnifiedPushRelay.start(applicationContext)
        }
    }

    private fun switchToUnlockedMode() {
        Log.i(LOG_TAG, "Unlocked - switching from locked mode to normal syncing")
        lockedMode = false
        handler.removeCallbacksAndMessages(null)
        streamGeneration++
        eventSource?.cancel()
        eventSource = null
        reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
        connect()
        schedulePeriodicSync()
        startUnifiedPushIfEnabled()
        // Catch up right away on everything locked mode skipped (policy, enforcement, status).
        scope.launch { performMdmSync(applicationContext) }
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        eventSource?.cancel()
        UnifiedPushRelay.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_LISTENER)
            .setSmallIcon(R.drawable.baseline_settings_24)
            .setContentTitle(getString(R.string.notification_listener_title))
            .setContentText(getString(R.string.notification_listener_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun connect() {
        if (stopped) return

        val credentials = BootState.credentials(this)
        if (credentials == null) {
            handler.postDelayed({ connect() }, NOT_ENROLLED_RETRY_DELAY_MS)
            return
        }

        // In normal mode HomeActivity brings tsnet up; in locked mode nothing else will, so this
        // does it first (off the main thread - it blocks for up to 30s). A failure just means the
        // stream below fails too and scheduleReconnect() retries both.
        if (lockedMode && !TsnetClient.connected) {
            val generation = streamGeneration
            scope.launch {
                TsnetClient.connectFromPreferences(applicationContext)
                handler.post { if (generation == streamGeneration) openStream(credentials) }
            }
            return
        }
        openStream(credentials)
    }

    private fun openStream(credentials: BootState.Credentials) {
        if (stopped) return
        val generation = streamGeneration
        val base = credentials.serverUrl.let { if (it.endsWith("/")) it else "$it/" }
        val request = Request.Builder()
            .url("${base}api/devices/commands/stream")
            .header("Authorization", "Bearer ${credentials.deviceToken}")
            .build()

        eventSource = EventSources.createFactory(buildClient()).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    if (generation != streamGeneration) return
                    Log.i(LOG_TAG, "Command stream connected")
                    reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                    // A lost phone that just rebooted should show up on the map without anyone
                    // having to send a locate first - once per boot, not on every reconnect.
                    if (lockedMode && !bootLocationSent) {
                        bootLocationSent = true
                        scope.launch { performLockedCheck(applicationContext, reportLocation = true) }
                    }
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (generation != streamGeneration) return
                    Log.i(LOG_TAG, "Command stream nudge received, syncing early")
                    if (lockedMode) {
                        scope.launch { performLockedCheck(applicationContext, reportLocation = false) }
                        return
                    }
                    scope.launch { performMdmSync(applicationContext) }
                    // Own coroutine, not chained after performMdmSync above - journalSyncMutex
                    // already keeps this from overlapping itself, and a slow media upload
                    // shouldn't delay the next policy fetch.
                    scope.launch { performJournalSync(applicationContext) }
                    scope.launch { performBrowserHistorySync(applicationContext) }
                }

                override fun onClosed(eventSource: EventSource) {
                    if (generation != streamGeneration) return
                    Log.i(LOG_TAG, "Command stream closed, reconnecting")
                    scheduleReconnect()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    if (generation != streamGeneration) return
                    Log.w(LOG_TAG, "Command stream connection failed, reconnecting", t)
                    scheduleReconnect()
                }
            },
        )
    }

    private fun schedulePeriodicSync() {
        if (stopped) return
        handler.postDelayed(
            {
                if (lockedMode) {
                    scope.launch { performLockedCheck(applicationContext, reportLocation = false) }
                } else {
                    scope.launch { performMdmSync(applicationContext) }
                    scope.launch { performJournalSync(applicationContext) }
                    scope.launch { performBrowserHistorySync(applicationContext) }
                }
                schedulePeriodicSync()
            },
            PERIODIC_SYNC_INTERVAL_MS,
        )
    }

    private fun scheduleReconnect() {
        if (stopped) return
        handler.postDelayed({ connect() }, reconnectDelayMs)
        // Simple exponential backoff so a server that's genuinely down doesn't get hammered with
        // reconnect attempts every 5 seconds forever.
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    companion object {
        /** Call once at app startup - safe to call repeatedly, Android no-ops a redundant start. */
        fun start(context: Context) {
            val intent = Intent(context, CommandListenerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
