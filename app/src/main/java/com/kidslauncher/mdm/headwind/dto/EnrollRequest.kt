package com.kidslauncher.mdm.headwind.dto

import kotlinx.serialization.Serializable

/**
 * Body for `POST /rest/public/sync/configuration/{number}` - matches the server's
 * `DeviceCreateOptions`. All fields optional; only used the first time a device number enrolls.
 */
@Serializable
data class EnrollRequest(
    val customer: String? = null,
    val configuration: String? = null,
    val groups: List<String>? = null,
)
