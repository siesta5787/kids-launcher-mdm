package com.kidslauncher.mdm.headwind.dto

import kotlinx.serialization.Serializable

/**
 * Body for `POST /rest/plugins/kidmode/status/device/{number}` - matches the server's
 * `KidModeStatusRecord` (id/customerId/deviceId/deviceNumber/reportTime are set server-side and
 * ignored here). Best-effort reporting only - a failed send must never affect the local lock
 * decision, only the parent-facing dashboard's visibility into it.
 */
@Serializable
data class KidModeStatusReport(
    val locked: Boolean,
    val lockReason: String,
)
