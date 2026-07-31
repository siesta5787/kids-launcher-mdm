package com.kidslauncher.mdm.server

import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import kotlinx.serialization.Serializable

private const val LOG_TAG = "TrackedAppUpdateState"

@Serializable
data class TrackedAppState(
    val lastInstalledTag: String? = null,
    val lastFailedTag: String? = null,
)

/**
 * Per-package install/failure tracking for apps tracked from GitHub Releases (see [AppInstaller],
 * [MdmSyncWorker]'s tracked-app sync) - cached as one JSON blob in a single preference, the same
 * "small JSON blob in a String preference" pattern already used for `kid_mode_policy`, rather than
 * a new custom preference-annotation serializer for a `Map` type. Without this, a release that
 * fails to install (or one that's already installed) would be re-downloaded and re-attempted every
 * 2-minute sync forever.
 */
object TrackedAppUpdateState {

    fun load(): Map<String, TrackedAppState> {
        val raw = LauncherPreferences.mdm().trackedAppUpdateState() ?: return emptyMap()
        return try {
            ServerJson.decodeFromString(raw)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to decode tracked-app update state", e)
            emptyMap()
        }
    }

    private fun save(state: Map<String, TrackedAppState>) {
        LauncherPreferences.mdm().trackedAppUpdateState(ServerJson.encodeToString(state))
    }

    fun recordInstalled(packageName: String, releaseTag: String) {
        val state = load().toMutableMap()
        state[packageName] = TrackedAppState(lastInstalledTag = releaseTag)
        save(state)
    }

    fun recordFailed(packageName: String, releaseTag: String) {
        val state = load().toMutableMap()
        val lastInstalledTag = state[packageName]?.lastInstalledTag
        state[packageName] = TrackedAppState(lastInstalledTag, lastFailedTag = releaseTag)
        save(state)
    }
}
