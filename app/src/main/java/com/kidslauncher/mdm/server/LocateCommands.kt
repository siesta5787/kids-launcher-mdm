package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

private const val LOG_TAG = "LocateCommands"
private const val RING_DURATION_MS = 30_000L

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
    fun currentLocation(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
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

        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
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
            Log.w(LOG_TAG, "Failed to read location", e)
            null
        }
    }

    @Volatile
    private var ringPlayer: MediaPlayer? = null

    /**
     * Forces the alarm stream to max volume and loops the device's own configured alarm sound for
     * [RING_DURATION_MS] - the alarm stream is specifically designed by the platform to bypass
     * silent/DND mode (the same reason a real alarm clock still rings then), so no special
     * permission is needed to reach it, unlike most other "override the user's settings" asks in
     * this app. Uses whatever alarm sound the phone already has configured rather than bundling a
     * new audio asset.
     */
    fun ring(context: Context) {
        try {
            stopRing()
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

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

            Handler(Looper.getMainLooper()).postDelayed({
                stopRing()
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
            }, RING_DURATION_MS)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to ring device", e)
        }
    }

    private fun stopRing() {
        try {
            ringPlayer?.stop()
            ringPlayer?.release()
        } catch (e: Exception) {
            // Already stopped/released - harmless.
        } finally {
            ringPlayer = null
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
