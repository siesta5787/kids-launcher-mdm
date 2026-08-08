package com.kidslauncher.mdm.server

import android.content.Context
import android.os.Build
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import tsembed.Client
import tsembed.Tsembed

private const val LOG_TAG = "TsnetClient"

/** Fixed per tsnet.Server.Loopback's own contract - the SOCKS5 proxy it starts always expects
 * this exact username, with the per-connection proxyCred as the password. Not configurable. */
private const val SOCKS_USERNAME = "tsnet"

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
     * one shared entry point for every caller (eager kickoff from [com.kidslauncher.mdm.Application.onCreate],
     * plus every [MdmSyncWorker] cycle as a retry-until-connected backstop), so hostname/state-dir
     * computation lives in exactly one place. No-ops (returns null) if no auth key is configured yet,
     * or if already connected - safe to call as often as needed.
     */
    fun connectFromPreferences(context: Context): String? {
        val authKey = LauncherPreferences.mdm().tailscaleAuthKey()
        if (authKey.isNullOrBlank() || connected) return null
        val hostname = "kids-launcher-${Build.MODEL}".replace(Regex("[^A-Za-z0-9-]"), "-")
        val stateDir = context.filesDir.resolve("tailscale").absolutePath
        return connect(hostname, authKey, stateDir)
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
