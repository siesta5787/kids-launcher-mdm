package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Body for `POST /api/devices/apps/progress` - purely transient, drives the admin site's
 * unified Apps list "Installing NN%" status label. Best-effort from this side; a dropped report
 * just means one stale-looking percentage until the next one lands or the row goes stale. */
@Serializable
data class InstallProgressReport(
    val trackedAppId: Long,
    val percent: Int,
)
