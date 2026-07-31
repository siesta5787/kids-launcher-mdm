package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** One entry from `GET /api/devices/apps` - an app tracked from a GitHub repo's Releases (see
 * kid-phone-server's `handlers::tracked_apps`), generalizing the launcher's own self-update to
 * arbitrary third-party apps. [downloadUrl] is per-entry (unlike the launcher's fixed download
 * endpoint) since there can be many tracked apps, each with its own cached APK. */
@Serializable
data class TrackedAppUpdate(
    val packageName: String,
    val releaseTag: String,
    val downloadUrl: String,
)
