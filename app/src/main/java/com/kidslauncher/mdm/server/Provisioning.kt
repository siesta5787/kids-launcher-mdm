package com.kidslauncher.mdm.server

import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.EnrollRequest
import com.kidslauncher.mdm.server.dto.ProvisioningExtras
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val LOG_TAG = "Provisioning"

/**
 * Saves the server URL/Tailscale key and enrolls in one shot from a [ProvisioningExtras] -
 * shared by [com.kidslauncher.mdm.server.MdmDeviceAdminReceiver.onProfileProvisioningComplete]
 * (Android's native zero-touch flow) and the in-app QR scanner (for devices like GrapheneOS where
 * that native flow has no trigger at all), so there is exactly one enroll code path regardless of
 * how Device Owner was granted - by this point it already has been, either way.
 *
 * Must be called off the main thread - [TsnetClient.connectFromPreferences] blocks (up to 30s)
 * establishing the tailnet connection before the enroll call runs. This is deliberate, not just a
 * blocking-call accident: a `server_url` on the `*.ts.net` domain is only resolvable *through*
 * that connection - [createMdmApi] silently falls back to the device's normal (public) DNS/network
 * path if tsnet hasn't connected yet, which can never resolve a MagicDNS hostname and previously
 * surfaced as a confusing "can't resolve host" failure on the very first scan, before tsnet had
 * any chance to come up at all.
 */
suspend fun applyProvisioningExtras(context: Context, extras: ProvisioningExtras): Result<Unit> {
    val mdm = LauncherPreferences.mdm()
    mdm.serverUrl(extras.serverUrl)
    if (extras.tailscaleAuthKey.isNotBlank()) {
        mdm.tailscaleAuthKey(extras.tailscaleAuthKey)
    }

    // No-ops immediately if no auth key is configured (nothing to connect) or a connection from
    // earlier already exists - safe to call unconditionally rather than checking either case here.
    val tailnetIp = TsnetClient.connectFromPreferences(context)
    if (extras.tailscaleAuthKey.isNotBlank() && tailnetIp == null && !TsnetClient.connected) {
        Log.w(LOG_TAG, "Tailnet connection failed or timed out before enroll attempt")
    }

    return try {
        val response = createMdmApi(extras.serverUrl).enroll(EnrollRequest(extras.enrollmentCode))
        val body = response.body()
        if (response.isSuccessful && body != null) {
            mdm.deviceToken(body.deviceToken)
            mdm.enrolled(true)
            Result.success(Unit)
        } else {
            Result.failure(Exception("HTTP ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private val nativeProvisioningExtrasHandled = AtomicBoolean(false)

/**
 * Entry point for Android's native QR/zero-touch flow specifically. On Android 12+ the same
 * admin extras bundle reaches this app twice - once via [ProvisioningComplianceActivity]
 * (`ADMIN_POLICY_COMPLIANCE`) and once via
 * [MdmDeviceAdminReceiver.onProfileProvisioningComplete] - and which one fires (or whether both
 * do) varies by Android version and OEM setup wizard. Enrolling twice with the same single-use
 * enrollment code would at best log a spurious failure and at worst race two device tokens, so
 * only the first caller per process actually enrolls; the rest no-op.
 *
 * Fire-and-forget on [Dispatchers.IO] - callers are a setup-wizard-blocking activity and a
 * broadcast receiver, neither of which should wait out [TsnetClient]'s up-to-30s connect. [onDone]
 * runs after the attempt either way (or immediately if skipped), for the receiver's `goAsync()`.
 */
fun applyNativeProvisioningExtrasOnce(
    context: Context,
    bundle: PersistableBundle?,
    onDone: () -> Unit = {},
) {
    val extras = ProvisioningExtras.fromAdminExtrasBundle(bundle)
    if (extras == null) {
        Log.i(LOG_TAG, "No admin extras bundle from provisioning - skipping auto-enroll")
        onDone()
        return
    }
    if (!nativeProvisioningExtrasHandled.compareAndSet(false, true)) {
        Log.i(LOG_TAG, "Provisioning extras already applied this process - skipping duplicate")
        onDone()
        return
    }

    val appContext = context.applicationContext
    CoroutineScope(Dispatchers.IO).launch {
        try {
            applyProvisioningExtras(appContext, extras)
                .onSuccess { Log.i(LOG_TAG, "Auto-enrolled from provisioning QR") }
                .onFailure { e -> Log.w(LOG_TAG, "Auto-enroll from provisioning QR failed", e) }
        } finally {
            onDone()
        }
    }
}
