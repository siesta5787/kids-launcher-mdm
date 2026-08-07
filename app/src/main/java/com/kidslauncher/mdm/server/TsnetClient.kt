package com.kidslauncher.mdm.server

import android.util.Log
import tsembed.Client
import tsembed.Tsembed

private const val LOG_TAG = "TsnetClient"

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
            val c = client ?: Tsembed.new_(hostname, authKey, stateDir).also { client = it }
            val ip = c.up(timeoutSeconds)
            proxyAddress = c.startProxy()
            proxyCredential = c.proxyCredential()
            ip
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to connect to tailnet", e)
            null
        }
    }

    fun close() {
        try {
            client?.close()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to close tsnet client", e)
        } finally {
            client = null
            proxyAddress = null
            proxyCredential = null
        }
    }
}
