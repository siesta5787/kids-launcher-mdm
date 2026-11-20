package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** `GET /api/devices/apps/{id}/direct` (server v0.25.0+) - fresh links to a tracked app's files
 * straight from Google Play or GitHub, so the download skips the server's slow Wi-Fi. Each file
 * must match [DirectDownloadFile.sha256]/[DirectDownloadFile.size] of the server's own checked
 * copy - see [com.kidslauncher.mdm.server.DirectDownloader]. */
@Serializable
data class DirectDownloads(
    /** `google_play` or `github`. */
    val source: String,
    /** Same as [TrackedAppUpdate.releaseTag] when the links are for the release being installed. */
    val releaseTag: String,
    /** Base APK first, then the splits in [TrackedAppUpdate.splits] order. */
    val files: List<DirectDownloadFile>,
)

@Serializable
data class DirectDownloadFile(
    val url: String,
    /** Lowercase hex SHA-256. */
    val sha256: String,
    val size: Long,
)
