package com.kidslauncher.mdm.headwind.dto

import kotlinx.serialization.Serializable

/**
 * Mirrors the KidMode plugin's device-facing policy response
 * (`GET /rest/plugins/kidmode/policy/device/{number}`). Minute fields are minutes-since-midnight.
 */
@Serializable
data class KidModePolicy(
    val id: Int? = null,
    val customerId: Int? = null,
    val deviceId: Int? = null,
    val enabled: Boolean = false,
    val weekdayStartMinutes: Int? = null,
    val weekdayEndMinutes: Int? = null,
    val weekendStartMinutes: Int? = null,
    val weekendEndMinutes: Int? = null,
    val bedtimeStartMinutes: Int? = null,
    val bedtimeEndMinutes: Int? = null,
    val allowlistJson: String? = null,
    val updateTs: Long? = null,
    val common: Boolean = false,
)
