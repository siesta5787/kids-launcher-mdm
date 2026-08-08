package com.kidslauncher.mdm.server

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.preferences.LauncherPreferences
import kotlinx.serialization.Serializable

private const val LOG_TAG = "TrackedAppUpdateState"

@Serializable
data class TrackedAppState(
    val lastInstalledTag: String? = null,
    val lastFailedTag: String? = null,
)

/**
 * Per-app install/failure tracking for apps tracked from GitHub Releases (see [AppInstaller],
 * [MdmSyncWorker]'s tracked-app sync) - cached as one JSON blob in a single preference, the same
 * "small JSON blob in a String preference" pattern already used for `kid_mode_policy`, rather than
 * a new custom preference-annotation serializer for a `Map` type. Without this, a release that
 * fails to install (or one that's already installed) would be re-downloaded and re-attempted every
 * 2-minute sync forever. Keyed by [TrackedAppUpdate.id] (as a string - stringified once at the
 * call site, not here, to keep this a plain `Map<String, _>` like the preference blob it mirrors),
 * not the app's Android package name - that's optional server-side now and can't be trusted to be
 * present or unique across tracked apps.
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

    // Writes synchronously (commit(), not the generated preference setter's apply()) because a
    // successful self-update record is written from AppInstallReceiver right as Android is about
    // to SIGKILL this process to replace it with the new APK - an async apply() write frequently
    // never reached disk before that kill, so the launcher kept re-downloading and reinstalling
    // the exact same release forever (confirmed live: same asset_id installed twice ~25s apart,
    // killing CommandListenerService's SSE connection each time). recordFailed shares this path
    // for consistency, though it isn't itself racing a process kill.
    private fun save(context: Context, state: Map<String, TrackedAppState>) {
        val key = LauncherPreferences.mdm().keys().trackedAppUpdateState()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(key, ServerJson.encodeToString(state))
            .commit()
    }

    fun recordInstalled(context: Context, appKey: String, releaseTag: String) {
        val state = load().toMutableMap()
        state[appKey] = TrackedAppState(lastInstalledTag = releaseTag)
        save(context, state)
    }

    fun recordFailed(context: Context, appKey: String, releaseTag: String) {
        val state = load().toMutableMap()
        val lastInstalledTag = state[appKey]?.lastInstalledTag
        state[appKey] = TrackedAppState(lastInstalledTag, lastFailedTag = releaseTag)
        save(context, state)
    }
}
