package com.kidslauncher.mdm.server

import android.content.Context
import android.os.Build
import android.util.Log
import com.kidslauncher.mdm.isUserUnlocked
import com.kidslauncher.mdm.launcherPreferencesReady
import com.kidslauncher.mdm.preferences.LauncherPreferences
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import tsembed.Client
import tsembed.Tsembed

private const val LOG_TAG = "TsnetClient"

/** Plain string preference keys, not routed through the kapt-generated LauncherPreferences
 * Config system - this is internal crash-detection bookkeeping, not an admin-facing setting, so
 * it doesn't need a donottranslate.xml entry (same reasoning LocateCommands.kt's own cached-state
 * preferences already use). */
private const val CRASH_GUARD_PENDING_KEY = "tsnet_connect_attempt_pending"
private const val CRASH_COUNT_KEY = "tsnet_connect_crash_count"
private const val LAST_CRASH_AT_KEY = "tsnet_connect_last_crash_at"

/** After this many consecutive crashes-during-connect, skip the next attempt entirely rather than
 * retry into the same crash - see [TsnetClient.connectFromPreferences]'s own doc comment. */
private const val MAX_CONSECUTIVE_CRASHES = 2

/** A crash streak older than this doesn't count toward the threshold - the goal is catching a
 * tight boot loop, not permanently disabling tsnet after two crashes that happened days apart. */
private const val CRASH_STREAK_COOLDOWN_MS = 15 * 60 * 1000L

/** Fixed per tsnet.Server.Loopback's own contract - the SOCKS5 proxy it starts always expects
 * this exact username, with the per-connection proxyCred as the password. Not configurable. */
private const val SOCKS_USERNAME = "tsnet"

// tsnet persists this node's identity (node key, login state) here, inside the state dir passed
// to Tsembed.new_ - once it exists, reconnecting never needs the auth key again.
private const val TSNET_STATE_FILE = "tailscaled.state"

/**
 * Thin Kotlin wrapper around the embedded tsnet connection (see this repo's
 * `mobile/tsembed` Go package, bound into `libs/tsnet.aar` by CI via
 * gomobile) - gives the launcher its own tailnet connection to reach the
 * server, replacing the standalone Tailscale app. Unlike that app, this is
 * in-process only (no device-wide VPN/routing of its own - see tsnet's own
 * docs) - [KidVpnService] is what owns the device's actual network path.
 *
 * Reaching the server goes through tsnet's own local SOCKS5 proxy
 * ([proxyAddress]/[proxyCredential], via `tsembed.Client.startProxy`) rather
 * than any direct Dial/socket bridging - gobind can't generate Java bindings
 * for tsnet.Server's own Dial()/net.Conn-returning methods, and a SOCKS5
 * proxy is tsnet's own documented mechanism for exactly this "give a
 * non-Go program tailnet access" case. See `mobile/tsembed/tsembed.go`.
 *
 * Owns exactly one [Client] instance for the app's lifetime - not safe to
 * share across threads without external synchronization.
 */
object TsnetClient {
    private var client: Client? = null

    // The (hostname, authKey, stateDir) the current [client] was constructed with. tsnet.Server's
    // AuthKey is only read at construction time (Tsembed.new_), so without tracking this,
    // connect() below would keep reusing a Client built with a stale key forever after the first
    // call in this process's lifetime - a real bug hit live: updating the auth key in Settings had
    // no effect until the app was force-stopped, since the cached Client's Go-side AuthKey field
    // never changed.
    private var clientParams: Triple<String, String, String>? = null

    @Volatile
    var proxyAddress: String? = null
        private set

    @Volatile
    var proxyCredential: String? = null
        private set

    val connected: Boolean
        get() = proxyAddress != null

    /**
     * Connects to the tailnet and starts the local SOCKS5 proxy - safe to
     * call repeatedly (reuses the existing [Client] once one exists, rather
     * than reconnecting from scratch every call). Returns this device's own
     * Tailscale IP on success, null on failure - logged, never thrown, since
     * "not connected yet" (no tailnet reachability this cycle) is an
     * expected transient state for callers to handle, not a crash.
     */
    fun connect(
        hostname: String,
        authKey: String,
        stateDir: String,
        timeoutSeconds: Long = 30,
    ): String? {
        return try {
            val params = Triple(hostname, authKey, stateDir)
            if (params != clientParams) {
                client?.close()
                proxyAddress = null
                proxyCredential = null
                client = null
            }
            val c = client ?: Tsembed.new_(hostname, authKey, stateDir).also {
                client = it
                clientParams = params
            }
            val ip = c.up(timeoutSeconds)
            val addr = c.startProxy()
            val cred = c.proxyCredential()
            proxyAddress = addr
            proxyCredential = cred
            installAuthenticator(addr, cred)
            ip
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to connect to tailnet", e)
            null
        }
    }

    /**
     * Reads the configured auth key/hostname/state dir from preferences and calls [connect] - the
     * one shared entry point for every caller ([com.kidslauncher.mdm.ui.HomeActivity]'s first
     * post-unlock resume, every [MdmSyncWorker] cycle as a retry-until-connected backstop, and
     * [applyProvisioningExtras]'s QR setup flow), so hostname/state-dir computation lives in
     * exactly one place. No-ops (returns null) if no auth key is configured yet, or if already
     * connected - safe to call as often as needed.
     *
     * Wrapped in a crash-loop guard, not called unconditionally: [connect] runs tsnet's embedded
     * Go/cgo runtime, a real native-crash surface a plain Kotlin try/catch cannot protect against
     * (a hard abort - e.g. from GrapheneOS's hardened_malloc catching a memory-safety violation -
     * bypasses the JVM's exception handling entirely and kills the process at the signal level).
     * This app has no fallback Home app once Device-Owner-pinned, so a connect attempt that
     * crashes on every launch would boot-loop the device with no way to reach a working launcher
     * again short of a hardware-recovery-mode wipe - confirmed as a real, not hypothetical, risk:
     * this exact mechanism (tsnet's Go runtime SIGABRT-crashing the whole process) already
     * happened once before for a different, since-fixed trigger (`no safe place found to store
     * log state`). A persisted "attempt in progress" flag, set synchronously right before and
     * cleared right after, survives a native crash where in-memory state can't - if a launch finds
     * it still true from last time, that's evidence the previous attempt never returned, and after
     * [MAX_CONSECUTIVE_CRASHES] such detections in a row this skips the attempt entirely for that
     * launch, letting the rest of the app boot normally without tailnet connectivity that cycle,
     * rather than retrying straight into the same crash.
     */
    @Synchronized
    fun connectFromPreferences(context: Context): String? {
        // Device-protected, so the crash guard also covers attempts before the first unlock (Find
        // My Device's locked path - see CommandListenerService), where a crash loop matters most.
        val prefs = BootState.prefs(context)
        // Only ever while no client exists in this process yet - never move files tsnet has open.
        if (client == null) migrateStateDirIfNeeded(context)
        val now = System.currentTimeMillis()

        // Consumed immediately, regardless of what happens below - this must never stay
        // stale-true across a cycle that didn't actually attempt a connection (e.g. one skipped
        // by the threshold check further down), or every future call would misread it as evidence
        // of a fresh crash forever, permanently locking tsnet off with no way to self-heal.
        val wasLeftPending = prefs.getBoolean(CRASH_GUARD_PENDING_KEY, false)
        if (wasLeftPending) prefs.edit().putBoolean(CRASH_GUARD_PENDING_KEY, false).commit()

        var crashCount = prefs.getInt(CRASH_COUNT_KEY, 0)
        val lastCrashAt = prefs.getLong(LAST_CRASH_AT_KEY, 0)
        if (crashCount > 0 && now - lastCrashAt > CRASH_STREAK_COOLDOWN_MS) {
            crashCount = 0
        }
        if (wasLeftPending) {
            crashCount += 1
            Log.w(
                LOG_TAG,
                "Previous tsnet connect attempt never completed (process likely crashed) - " +
                    "consecutive count now $crashCount",
            )
            prefs.edit()
                .putInt(CRASH_COUNT_KEY, crashCount)
                .putLong(LAST_CRASH_AT_KEY, now)
                .commit()
        }

        if (crashCount >= MAX_CONSECUTIVE_CRASHES) {
            Log.w(
                LOG_TAG,
                "Skipping tsnet connect this launch - $crashCount consecutive crashes detected " +
                    "within the last ${CRASH_STREAK_COOLDOWN_MS / 60_000}m",
            )
            return null
        }

        // Before the first unlock LauncherPreferences can't be read at all - rejoining from the
        // saved identity (device-protected, see stateDir) is the only option then, which is all
        // an already-enrolled device needs anyway.
        val authKey =
            if (launcherPreferencesReady) LauncherPreferences.mdm().tailscaleAuthKey().orEmpty() else ""
        if (connected) {
            clearAuthKeyIfJoined(context, authKey)
            return null
        }
        // Nothing to connect with: no key to join with, and no saved identity to rejoin with.
        if (authKey.isBlank() && !hasSavedIdentity(context)) return null
        val hostname = "kids-launcher-${Build.MODEL}".replace(Regex("[^A-Za-z0-9-]"), "-")

        prefs.edit().putBoolean(CRASH_GUARD_PENDING_KEY, true).commit()
        return try {
            connect(hostname, authKey, stateDir(context).absolutePath)
                ?.also { clearAuthKeyIfJoined(context, authKey) }
        } finally {
            // Reached by any path that returns normally (success or a caught/logged failure
            // inside connect() itself, which already has its own try/catch) - proves the process
            // survived the attempt, so clear the guard and reset the streak.
            prefs.edit()
                .putBoolean(CRASH_GUARD_PENDING_KEY, false)
                .putInt(CRASH_COUNT_KEY, 0)
                .commit()
        }
    }

    /**
     * Device-protected storage, so tsnet can rejoin from its saved identity before the first unlock
     * after a reboot (Find My Device's locked path). Lived in credential-encrypted `filesDir` before
     * v0.52.0 - see [migrateStateDirIfNeeded].
     */
    private fun stateDir(context: Context) =
        context.createDeviceProtectedStorageContext().filesDir.resolve("tailscale")

    private fun legacyStateDir(context: Context) = context.filesDir.resolve("tailscale")

    /** Whether this device has already joined the tailnet (tsnet's persisted node identity exists). */
    fun hasSavedIdentity(context: Context): Boolean =
        stateDir(context).resolve(TSNET_STATE_FILE).length() > 0 ||
            (isUserUnlocked(context) && legacyStateDir(context).resolve(TSNET_STATE_FILE).length() > 0)

    /**
     * One-time move of tsnet's state from credential-encrypted to device-protected storage on the
     * first unlocked run after updating to v0.52.0. Copy-then-delete, not rename: the two storage
     * areas have different fscrypt policies, and the kernel refuses renames across them. Losing
     * this state would be unrecoverable without re-scanning the setup QR, since the auth key is
     * deleted after joining - so the old copy is only removed once the new one verifiably exists.
     */
    private fun migrateStateDirIfNeeded(context: Context) {
        if (!isUserUnlocked(context)) return
        val legacy = legacyStateDir(context)
        if (!legacy.exists()) return
        val target = stateDir(context)
        try {
            if (target.resolve(TSNET_STATE_FILE).length() == 0L) {
                legacy.copyRecursively(target, overwrite = true)
            }
            if (target.resolve(TSNET_STATE_FILE).length() > 0) {
                legacy.deleteRecursively()
                Log.i(LOG_TAG, "Moved tsnet state to device-protected storage")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to move tsnet state to device-protected storage", e)
        }
    }

    /**
     * The auth key is only needed to join the tailnet once - after that tsnet reconnects from its
     * saved identity ([hasSavedIdentity]) and ignores the key. Keeping a (typically reusable) key on
     * the phone afterwards only means anyone who extracts it can add *new* devices to the tailnet,
     * so it's deleted as soon as a connection has succeeded. Only called on a proven-working
     * connection, so a key that hasn't actually joined yet is never thrown away. If the identity is
     * ever lost (node removed in the Tailscale admin console, app data cleared), re-scanning the
     * setup QR supplies a fresh key.
     */
    private fun clearAuthKeyIfJoined(context: Context, authKey: String) {
        if (authKey.isBlank() || !launcherPreferencesReady || !hasSavedIdentity(context)) return
        LauncherPreferences.mdm().tailscaleAuthKey(null)
        Log.i(LOG_TAG, "Joined the tailnet - cleared the stored auth key")
    }

    /** A [Proxy] pointing at the running SOCKS5 proxy, or null if not connected yet - see
     * [MdmApi]'s `createMdmApi`, the only intended caller. Credentials are supplied separately via
     * the process-wide [Authenticator] installed in [connect], since OkHttp's [Proxy] type itself
     * carries no room for them (that's how the JDK's own SOCKS5 client is designed - proxy
     * authentication is always negotiated through [Authenticator], not the [Proxy] object). */
    fun proxy(): Proxy? {
        val addr = proxyAddress ?: return null
        val host = addr.substringBeforeLast(':')
        val port = addr.substringAfterLast(':').toIntOrNull() ?: return null
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
    }

    /**
     * [Authenticator] is a single process-wide singleton in the JDK, not something you can scope
     * to one [OkHttpClient] instance - this app has no other proxy/authenticator use anywhere
     * (confirmed: this is the only [Authenticator] reference in the codebase), so installing one
     * globally here is safe. Checks the requesting host/port match this proxy specifically before
     * handing out the credential, rather than answering unconditionally, in case that ever
     * changes.
     */
    private fun installAuthenticator(proxyAddr: String, cred: String) {
        val host = proxyAddr.substringBeforeLast(':')
        val port = proxyAddr.substringAfterLast(':').toIntOrNull()
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                if (requestingHost != host || requestingPort != port) return null
                return PasswordAuthentication(SOCKS_USERNAME, cred.toCharArray())
            }
        })
    }

    fun close() {
        try {
            client?.close()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to close tsnet client", e)
        } finally {
            client = null
            clientParams = null
            proxyAddress = null
            proxyCredential = null
        }
    }
}
