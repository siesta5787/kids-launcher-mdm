package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** One app group's state in a status report - [usedMinutes] today (null when usage access isn't
 * granted, so time can't be counted), the device-local [day] (YYYY-MM-DD) it's for, and why the
 * group is blocked right now ([blockedReason] is a [com.kidslauncher.mdm.server.GroupBlockReason]
 * name, null when its apps are usable). */
@Serializable
data class AppGroupUsageReport(
    val groupId: Long,
    val usedMinutes: Int?,
    val day: String,
    val blockedReason: String?,
)
