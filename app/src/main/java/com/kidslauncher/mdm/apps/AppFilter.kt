package com.kidslauncher.mdm.apps

import android.content.Context
import android.content.pm.PackageManager
import com.kidslauncher.mdm.preferences.LauncherPreferences
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
                    && !isMdmSuspended(info)
        }

        return apps
    }

    /**
     * Belt-and-suspenders check alongside [android.app.admin.DevicePolicyManager.setApplicationHidden]
     * (which should already make MDM-blocked apps disappear from the underlying app enumeration):
     * excludes anything currently OS-suspended, so a live-updating drawer/home list never shows an
     * app the parent has blocked even if hiding doesn't fully take effect on a given Android version.
     */
    private fun isMdmSuspended(info: AbstractDetailedAppInfo): Boolean {
        val packageName = (info.getRawInfo() as? AppInfo)?.packageName ?: return false
        return try {
            context.packageManager.isPackageSuspended(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
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