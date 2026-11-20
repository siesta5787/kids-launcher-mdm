package com.kidslauncher.mdm.server

import android.util.Log
import com.kidslauncher.mdm.server.dto.DirectDownloadFile
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.net.Proxy
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "DirectDownloader"

/**
 * Downloads a tracked app's files straight from Google Play / GitHub (server v0.25.0+) instead of
 * from the server, whose Pi Zero Wi-Fi made a 1.2 GB game take about 15 minutes. The server still
 * downloads and checks every release itself; a direct download is only installed if it's
 * byte-for-byte the server's copy (same SHA-256 and size), and anything else - no links, an error,
 * a mismatch - falls back to downloading that file from the server, as before.
 *
 * Its own plain HTTP client on purpose: the public internet, not the tailnet (no tsnet proxy),
 * and never the device's bearer token - that only ever goes to our own server.
 */
object DirectDownloader {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            // GitHub's links redirect to its download servers - over https only.
            .followSslRedirects(false)
            .build()
    }

    /** Opens [file]'s link, or null (logged) if it isn't https or the source said no. */
    fun open(file: DirectDownloadFile): ResponseBody? {
        if (!file.url.startsWith("https://")) {
            Log.w(LOG_TAG, "Not an https link, skipping direct download")
            return null
        }
        val response = client.newCall(Request.Builder().url(file.url).build()).execute()
        if (!response.isSuccessful) {
            Log.w(LOG_TAG, "Direct download refused: HTTP ${response.code}")
            response.close()
            return null
        }
        return response.body
    }

    /** True when a finished download is exactly [file]. */
    fun matches(file: DirectDownloadFile, bytes: Long, sha256: String): Boolean =
        bytes == file.size && sha256.equals(file.sha256, ignoreCase = true)
}
