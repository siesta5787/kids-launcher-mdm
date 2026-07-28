package de.jrpie.android.launcher.preferences.legacy

import android.content.Context
import de.jrpie.android.launcher.preferences.LauncherPreferences

fun migratePreferencesFromVersion4(context: Context) {
    assert(LauncherPreferences.internal().versionCode() < 100)

    LauncherPreferences.widgets().widgets(emptySet())
    LauncherPreferences.internal().versionCode(100)
    migratePreferencesFromVersion100(context)
}