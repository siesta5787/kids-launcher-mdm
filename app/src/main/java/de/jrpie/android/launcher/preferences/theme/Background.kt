package de.jrpie.android.launcher.preferences.theme

import android.content.res.Resources
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.preferences.LauncherPreferences

enum class Background(val id: Int) {
    TRANSPARENT(R.style.backgroundWallpaper),
    SOLID(R.style.backgroundSolid),
    ;

    fun applyToTheme(theme: Resources.Theme) {
        var background = this

        // force a solid background when using the light theme
        if (LauncherPreferences.theme().colorTheme() == ColorTheme.LIGHT) {
            background = SOLID
        }
        theme.applyStyle(background.id, true)
    }
}
