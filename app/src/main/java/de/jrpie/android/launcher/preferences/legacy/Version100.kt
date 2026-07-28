package de.jrpie.android.launcher.preferences.legacy

import android.content.Context
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.preferences.PREFERENCE_VERSION

fun migratePreferencesFromVersion100(context: Context) {
    assert(PREFERENCE_VERSION == 101)
    assert(LauncherPreferences.internal().versionCode() == 100)

    LauncherPreferences.internal().versionCode(101)
}