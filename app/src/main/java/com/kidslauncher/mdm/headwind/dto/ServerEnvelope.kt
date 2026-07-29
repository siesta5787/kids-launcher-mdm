package com.kidslauncher.mdm.headwind.dto

import kotlinx.serialization.Serializable

/**
 * Every Headwind REST response is wrapped in this shape.
 */
@Serializable
data class ServerEnvelope<T>(
    val status: String,
    val message: String? = null,
    val data: T? = null,
) {
    val ok: Boolean get() = status == "OK"
}
