package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** One entry in a status report's self-reported app list - lets the admin site's allowlist
 * checkboxes show real installed apps instead of asking a parent to type package names. */
@Serializable
data class InstalledApp(
    val packageName: String,
    val label: String,
)
