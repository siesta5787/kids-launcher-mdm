package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** One entry in a status report's self-reported app list - lets the admin site's unified Apps
 * list show real installed apps instead of asking a parent to type package names.
 * [preinstalled] mirrors `ApplicationInfo.FLAG_SYSTEM` (already checked in
 * [com.kidslauncher.mdm.server.controllablePackages], just not previously reported anywhere) -
 * it's what lets the admin UI tell "can be suspended but never actually uninstalled" apps apart
 * from ones it pushed itself. */
@Serializable
data class InstalledApp(
    val packageName: String,
    val label: String,
    val preinstalled: Boolean,
    /** False for a preinstalled app that's on the phone but not set up for this user (e.g. skipped
     * in the phone's setup) - Android shows it nowhere, and [AppEnforcer] restores it if the parent
     * allows it. Server v0.27.0+ labels these. */
    val installed: Boolean = true,
)
