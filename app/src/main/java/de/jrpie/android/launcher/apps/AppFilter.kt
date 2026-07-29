package de.jrpie.android.launcher.apps

import android.content.Context
import de.jrpie.android.launcher.preferences.LauncherPreferences
import java.util.Locale

class AppFilter(
    var context: Context,
    var hiddenVisibility: AppSetVisibility = AppSetVisibility.HIDDEN,
    var pinnedVisibility: AppSetVisibility = AppSetVisibility.VISIBLE
) {

    operator fun invoke(apps: List<AbstractDetailedAppInfo>): List<AbstractDetailedAppInfo> {
        var apps =
            apps.sortedBy { app -> app.getCustomLabel(context).lowercase(Locale.ROOT) }

        val hidden = LauncherPreferences.apps().hidden() ?: setOf()
        val pinned = LauncherPreferences.minimalist().apps() ?: setOf()

        apps = apps.filter { info ->
            hiddenVisibility.predicate(hidden, info)
                    && pinnedVisibility.predicate(pinned, info)
        }

        return apps
    }

    companion object {
        enum class AppSetVisibility(
            val predicate: (set: Set<AbstractAppInfo>, AbstractDetailedAppInfo) -> Boolean
        ) {
            VISIBLE({ _, _ -> true }),
            HIDDEN({ set, appInfo -> !set.contains(appInfo.getRawInfo()) }),
            EXCLUSIVE({ set, appInfo -> set.contains(appInfo.getRawInfo()) }),
            ;
        }
    }
}