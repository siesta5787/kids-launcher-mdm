package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * Response from `GET /api/devices/policy`. [allowlist] null/empty means "no restriction". Minute
 * fields are minutes-since-midnight, same representation [com.kidslauncher.mdm.server.KidModeEnforcer]
 * already expects - null or a matching start/end means "no restriction" for that window.
 * [kioskDesired] is the server-authoritative kiosk switch: the admin site sets it, the device
 * applies it automatically on its next sync - there is no on-device way to change it.
 * [lockTaskFeatures] is the raw bitmask for `DevicePolicyManager.setLockTaskFeatures` (0 = every
 * system-chrome feature disabled while pinned, matching this app's previous hardcoded behavior).
 * [wifiMode]/[bluetoothMode] are one of "open" | "restricted" | "disabled".
 * [overridePinHash]/[overridePinSalt] back the offline override PIN (see [com.kidslauncher.mdm.ui.LockActivity])
 * - both null means no PIN is configured for this device.
 * [requireTailscale]/[tailscaleExitNodeId] are pushed to the Tailscale app (`com.tailscale.ipn`)
 * as Android managed-app-restrictions (`ForceEnabled`/`ExitNodeID`) - see
 * [com.kidslauncher.mdm.server.AppEnforcer.applyVpnRestrictions]. A blank/null exit node ID means
 * no exit node is enforced.
 * [quickControlsMask] is the raw bitmask for which switches show up on the launcher's
 * swipe-left-from-home "Quick Controls" screen (1 = WiFi, 2 = Bluetooth, 4 = brightness) - see
 * [com.kidslauncher.mdm.ui.quickcontrols.QuickControlsActivity].
 * [pendingCommand] is Find My Device's remote-command queue (ring/lock/wipe) - see
 * [com.kidslauncher.mdm.server.LocateCommands] and [MdmSyncWorker]'s dispatch of it.
 */
@Serializable
data class PolicyResponse(
    val allowlist: List<String>? = null,
    val weekdayStartMinutes: Int? = null,
    val weekdayEndMinutes: Int? = null,
    val weekendStartMinutes: Int? = null,
    val weekendEndMinutes: Int? = null,
    val bedtimeStartMinutes: Int? = null,
    val bedtimeEndMinutes: Int? = null,
    val kioskDesired: Boolean = false,
    val lockTaskFeatures: Long = 0,
    val wifiMode: String = "open",
    val bluetoothMode: String = "open",
    val overridePinHash: String? = null,
    val overridePinSalt: String? = null,
    val requireTailscale: Boolean = false,
    val tailscaleExitNodeId: String? = null,
    val quickControlsMask: Long = 0,
    val pendingCommand: PendingCommand? = null,
)
