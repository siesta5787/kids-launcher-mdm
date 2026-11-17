package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * One app group from `GET /api/devices/policy` (server v0.23.0+) - a parent-named set of apps
 * sharing one daily time limit ([dailyLimitMinutes], null = none), one allowed-hours window
 * ([allowedStartMinutes]-[allowedEndMinutes], minutes since midnight, null or equal = any time,
 * start > end wraps overnight), and one parent override ([overrideMode] `"pause"`/`"allow"` until
 * [overrideUntilMs] epoch millis on the device clock, null = until changed). See
 * [com.kidslauncher.mdm.server.AppGroups].
 */
@Serializable
data class AppGroupPolicy(
    val id: Long,
    val name: String,
    val packages: List<String> = emptyList(),
    val dailyLimitMinutes: Int? = null,
    val allowedStartMinutes: Int? = null,
    val allowedEndMinutes: Int? = null,
    val overrideMode: String? = null,
    val overrideUntilMs: Long? = null,
)
