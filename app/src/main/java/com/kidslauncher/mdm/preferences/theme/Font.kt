package com.kidslauncher.mdm.preferences.theme

import android.content.Context
import android.content.res.Resources
import android.graphics.Typeface
import com.kidslauncher.mdm.R

enum class Font(val id: Int, val getTypeface: (Context) -> Typeface?) {
    SYSTEM_DEFAULT(
        R.style.fontSystemDefault,
        { _ -> Typeface.DEFAULT }),
    ;

    fun applyToTheme(theme: Resources.Theme) {
        theme.applyStyle(id, true)
    }
}
