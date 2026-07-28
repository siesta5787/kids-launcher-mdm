package de.jrpie.android.launcher.preferences.theme

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import de.jrpie.android.launcher.R

enum class Font(val id: Int, val getTypeface: (Context) -> Typeface?) {
    SYSTEM_DEFAULT(
        R.style.fontSystemDefault,
        { _ -> Typeface.DEFAULT }),
    ;

    fun applyToTheme(theme: Resources.Theme) {
        theme.applyStyle(id, true)
    }
}
