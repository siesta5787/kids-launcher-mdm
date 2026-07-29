package com.kidslauncher.mdm.headwind.dto

import kotlinx.serialization.Serializable

/**
 * Response for `GET`/`POST /rest/public/sync/configuration/{number}` (Headwind's `SyncResponse`).
 * Deliberately minimal - only the field this client actually acts on. Unknown fields are ignored
 * by the [com.kidslauncher.mdm.headwind.HeadwindJson] configuration.
 */
@Serializable
data class ServerConfig(
    val newNumber: String? = null,
)
