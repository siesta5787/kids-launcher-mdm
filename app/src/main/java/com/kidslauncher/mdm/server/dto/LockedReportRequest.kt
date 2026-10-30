package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * Find My Device's heartbeat before the first unlock after a reboot - see
 * [com.kidslauncher.mdm.server.performLockedCheck]. A separate, deliberately tiny report rather
 * than a [StatusReportRequest]: before unlock the app can't read its installed-apps list, kiosk
 * state or lock reason, and a status report without them would overwrite the server's last real
 * snapshot (which the admin site's allowlist checkboxes are built from).
 */
@Serializable
data class LockedReportRequest(
    val location: LocationReport? = null,
)
