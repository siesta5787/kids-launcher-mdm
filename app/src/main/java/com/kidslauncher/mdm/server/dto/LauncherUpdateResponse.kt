package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Response from `GET /api/devices/launcher-update` - describes the currently-uploaded launcher
 * build. The device does its own comparison against `BuildConfig.VERSION_CODE`; the server
 * doesn't need to know the caller's current version for this endpoint. */
@Serializable
data class LauncherUpdateResponse(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
)
