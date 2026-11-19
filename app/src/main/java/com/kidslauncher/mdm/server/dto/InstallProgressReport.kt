package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** Body for `POST /api/devices/apps/progress` - purely transient, drives the admin site's
 * unified Apps list "Installing NN%"/"Install failed" status label. Best-effort from this side; a
 * dropped report just means one stale-looking status until the next one lands or the row goes
 * stale. [percent] is meaningless when [failed] is true - send 0. */
@Serializable
data class InstallProgressReport(
    val trackedAppId: Long,
    val percent: Int,
    val failed: Boolean = false,
    /** Why it failed - Android's install status message, or the download error. Shown on the
     * parent site's device page (server v0.24.2+; older servers ignore it). */
    val message: String? = null,
)
