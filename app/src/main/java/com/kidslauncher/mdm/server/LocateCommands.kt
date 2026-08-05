package com.kidslauncher.mdm.server

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_RING
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.RING_NOTIFICATION_ID
import com.kidslauncher.mdm.preferences.LauncherPreferences
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private const val LOG_TAG = "LocateCommands"
private const val RING_DURATION_MS = 30_000L
private const val FRESH_LOCATION_TIMEOUT_MS = 15_000L

// An active fetch (requestFreshFix) shows Android's location-in-use indicator and visibly slows
// down the sync it runs in - confirmed live, reported as "Sync now takes longer and the green
// location dot shows up every time." Doing that on every single 2-minute/manual sync is overkill
// for a trail that's meant to update every so often, not continuously - so it's throttled to once
// per this interval, except when a `ring`/`locate` command explicitly asks for a fresh fix right
// now (see MdmSyncWorker.currentLocationReport's forceFresh argument).
private const val ACTIVE_LOCATION_FETCH_THROTTLE_MS = 10 * 60 * 1000L

/**
 * Find My Device: locate/ring/lock/wipe, dispatched from [MdmSyncWorker] whenever the server's
 * `pendingCommand` is present - never a push, same 2-minute-polling tradeoff as everything else in
 * this app. Same "direct, silent Device-Owner API" style as [QuickControls].
 */
object LocateCommands {

    /**
     * Plain [android.location.LocationManager], never `FusedLocationProviderClient` - this app has
     * no Google Play Services anywhere (GrapheneOS has none). Self-grants the location permissions
     * first, and - separately - ensures the phone's system-wide Location toggle is on, since
     * `LocationManager` returns nothing at all while it's off, regardless of any permission held
     * (the same platform-level gate discovered tonight while building the WiFi network picker,
     * except there it was a surprising side effect of scanning APIs; here it's simply what the
     * Location toggle fundamentally controls). Unlike the WiFi picker's screen-scoped enable/restore,
     * this leaves Location on rather than toggling it back off - Find My Device is an ongoing
     * background feature, not a single screen visit, so there's nothing meaningful to "restore" to.
     */
    suspend fun currentLocation(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        forceFresh: Boolean = false,
    ): Location? {
        QuickControls.selfGrantPermission(
            context,
            dpm,
            admin,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        QuickControls.selfGrantPermission(
            context,
            dpm,
            admin,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        QuickControls.selfGrantPermission(
            context,
            dpm,
            admin,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )

        // Diagnostic - unlike every other permission self-granted in this app,
        // ACCESS_BACKGROUND_LOCATION hasn't been confirmed to actually work via Device-Owner
        // self-grant. If it silently fails, this makes that visible in logs immediately instead of
        // another guessing cycle like tonight's WiFi/Bluetooth scan diagnosis.
        val backgroundGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        Log.i(LOG_TAG, "currentLocation: ACCESS_BACKGROUND_LOCATION granted=$backgroundGranted")

        if (!QuickControls.isLocationEnabled(context)) {
            QuickControls.setLocationEnabled(dpm, admin, true)
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // getLastKnownLocation is purely passive - it only ever returns a location if something
        // else on the device already requested a fresh fix recently. Confirmed live: with
        // permission granted and Location on, every provider still returned null forever because
        // nothing had actively triggered GPS/network on this device. requestFreshFix() below
        // actively asks for one, but only when forced (a `ring`/`locate` command) or the throttle
        // window has elapsed - every sync doing an active fetch was confirmed live to visibly slow
        // the sync down and show Android's location-in-use indicator every single time, which isn't
        // needed for a trail that's meant to update periodically, not continuously.
        val mdm = LauncherPreferences.mdm()
        val throttleElapsed =
            System.currentTimeMillis() - mdm.lastActiveLocationFetchAtMs() >= ACTIVE_LOCATION_FETCH_THROTTLE_MS
        if (forceFresh || throttleElapsed) {
            // Recorded regardless of outcome - a missed fix (e.g. deep indoors) shouldn't retry on
            // every single sync until the next window, or this defeats the point of throttling.
            mdm.lastActiveLocationFetchAtMs(System.currentTimeMillis())
            val fresh = requestFreshFix(context, lm)
            if (fresh != null) return fresh
        }

        return try {
            val providers = mutableListOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                providers.add(LocationManager.FUSED_PROVIDER)
            }
            providers
                .mapNotNull { provider ->
                    try {
                        lm.getLastKnownLocation(provider)
                    } catch (e: Exception) {
                        null
                    }
                }
                .maxByOrNull { it.time }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to read cached location", e)
            null
        }
    }

    /**
     * Actively requests a fresh fix via the modern one-shot [LocationManager.getCurrentLocation]
     * API (no Google Play Services needed, unlike `FusedLocationProviderClient`), bridged into a
     * suspend call since [MdmSyncWorker] already runs in a coroutine. Bounded by
     * [FRESH_LOCATION_TIMEOUT_MS] so a GPS fix that can't be acquired (e.g. deep indoors) can never
     * stall a sync cycle - `getCurrentLocation` is documented to call back with `null` on its own
     * internal timeout, but this is a second, app-level bound just in case that's ever longer.
     */
    private suspend fun requestFreshFix(context: Context, lm: LocationManager): Location? {
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        return withTimeoutOrNull(FRESH_LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val cancellationSignal = CancellationSignal()
                cont.invokeOnCancellation { cancellationSignal.cancel() }
                try {
                    lm.getCurrentLocation(provider, cancellationSignal, context.mainExecutor) { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Failed to request fresh location fix", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    @Volatile
    private var ringPlayer: MediaPlayer? = null

    private var originalRingerMode: Int? = null
    private val restoredStreams = mutableMapOf<Int, Int>()
    private val ringHandler = Handler(Looper.getMainLooper())
    private var ringTimeoutRunnable: Runnable? = null

    /**
     * Forces every stream that could plausibly gate audible output to max volume and loops the
     * device's own configured alarm sound for [RING_DURATION_MS] (or until [stopRingAndRestore] is
     * called first - via the notification's "Stop Ringing" action, or a `stop_ring` command from
     * the server). The alarm stream alone is *supposed* to be enough - it's specifically designed
     * by the platform to bypass silent/DND mode, the same reason a real alarm clock still rings
     * then - but confirmed live that it wasn't loud enough on its own, so this also explicitly
     * forces the ringer mode off silent (belt-and-suspenders against any OEM/ROM-level interaction
     * between ringer mode and perceived alarm loudness) and raises RING/NOTIFICATION/MUSIC
     * alongside ALARM in case actual playback ends up routed differently than
     * `AudioAttributes.USAGE_ALARM` alone implies on this hardware. No special permission needed
     * for any of this, unlike most other "override the user's settings" asks in this app. Uses
     * whatever alarm sound the phone already has configured rather than bundling a new audio asset.
     */
    fun ring(context: Context) {
        try {
            stopRingPlayback()
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Best-effort - changing ringer mode needs Do-Not-Disturb/notification-policy access,
            // which isn't something Device Owner can silently grant itself the way runtime
            // permissions are elsewhere in this app. Its own try/catch so a failure here (no DND
            // access) can't take down the alarm-stream playback below, which needs no permission
            // at all and is the part that actually matters.
            try {
                originalRingerMode = audioManager.ringerMode
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to set ringer mode (likely missing DND access)", e)
            }

            restoredStreams.clear()
            listOf(
                AudioManager.STREAM_ALARM,
                AudioManager.STREAM_RING,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.STREAM_MUSIC,
            ).forEach { stream ->
                restoredStreams[stream] = audioManager.getStreamVolume(stream)
                try {
                    audioManager.setStreamVolume(stream, audioManager.getStreamMaxVolume(stream), 0)
                } catch (e: Exception) {
                    // Some streams (e.g. STREAM_RING on a device with no telephony) can reject
                    // this - never let one failing stream block the others.
                    Log.w(LOG_TAG, "Failed to raise stream $stream", e)
                }
            }

            val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getValidRingtoneUri(context)

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, alarmUri)
                isLooping = true
                prepare()
                start()
            }
            ringPlayer = player

            showStopRingNotification(context)

            val timeout = Runnable { stopRingAndRestore(context) }
            ringTimeoutRunnable = timeout
            ringHandler.postDelayed(timeout, RING_DURATION_MS)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to ring device", e)
        }
    }

    /**
     * Stops playback, restores every volume/ringer-mode change [ring] made, and cancels the
     * notification - called either by [RING_DURATION_MS] naturally elapsing, the notification's
     * "Stop Ringing" action ([StopRingReceiver]), or a `stop_ring` command from the server
     * ([MdmSyncWorker]'s dispatch). Safe to call more than once (e.g. both the timeout and a manual
     * stop racing) - each step is independently guarded.
     */
    fun stopRingAndRestore(context: Context) {
        ringTimeoutRunnable?.let { ringHandler.removeCallbacks(it) }
        ringTimeoutRunnable = null
        stopRingPlayback()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        restoredStreams.forEach { (stream, volume) ->
            try {
                audioManager.setStreamVolume(stream, volume, 0)
            } catch (e: Exception) {
                // Best-effort restore - harmless if it fails.
            }
        }
        restoredStreams.clear()
        try {
            originalRingerMode?.let { audioManager.ringerMode = it }
        } catch (e: Exception) {
            // Best-effort restore - harmless if it fails.
        }
        originalRingerMode = null

        cancelStopRingNotification(context)
    }

    private fun stopRingPlayback() {
        try {
            ringPlayer?.stop()
            ringPlayer?.release()
        } catch (e: Exception) {
            // Already stopped/released - harmless.
        } finally {
            ringPlayer = null
        }
    }

    /**
     * A HIGH-importance notification (its own channel, see [NOTIFICATION_CHANNEL_RING]) with a
     * "Stop Ringing" action - the whole reason this exists is to give the kid an obvious,
     * immediate way to silence the alarm once they unlock the device, rather than it just blaring
     * with no visible control. `setOngoing(true)` so it can't be swiped away by accident while the
     * ring is still active - it's cancelled properly by [cancelStopRingNotification] once the ring
     * actually stops, whether via timeout, the notification's own button, or a server command.
     */
    private fun showStopRingNotification(context: Context) {
        val stopIntent = Intent(context, StopRingReceiver::class.java)
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_RING)
            .setSmallIcon(R.drawable.baseline_settings_24)
            .setContentTitle(context.getString(R.string.notification_ring_title))
            .setContentText(context.getString(R.string.notification_ring_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(
                R.drawable.baseline_close_24,
                context.getString(R.string.notification_ring_stop_action),
                stopPendingIntent,
            )
            .build()

        try {
            NotificationManagerCompat.from(context).notify(RING_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "Failed to show stop-ring notification (missing POST_NOTIFICATIONS?)", e)
        }
    }

    private fun cancelStopRingNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(RING_NOTIFICATION_ID)
        } catch (e: Exception) {
            // Harmless - notification may already be gone.
        }
    }

    fun lock(dpm: DevicePolicyManager, admin: ComponentName): Boolean {
        return try {
            dpm.lockNow()
            true
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to lock device", e)
            false
        }
    }

    /** Irreversible - the caller never gets an acknowledgement back, since the device is gone. */
    fun wipe(dpm: DevicePolicyManager, admin: ComponentName) {
        try {
            dpm.wipeData(0)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to wipe device", e)
        }
    }
}
