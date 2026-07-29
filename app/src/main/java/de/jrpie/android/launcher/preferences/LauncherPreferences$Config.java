package de.jrpie.android.launcher.preferences;

import java.util.HashMap;
import java.util.Set;

import de.jrpie.android.launcher.R;
import de.jrpie.android.launcher.preferences.serialization.MapAbstractAppInfoStringPreferenceSerializer;
import de.jrpie.android.launcher.preferences.serialization.SetAbstractAppInfoPreferenceSerializer;
import de.jrpie.android.launcher.preferences.theme.ColorTheme;
import eu.jonahbauer.android.preference.annotations.Preference;
import eu.jonahbauer.android.preference.annotations.PreferenceGroup;
import eu.jonahbauer.android.preference.annotations.Preferences;

@Preferences(
        name = "de.jrpie.android.launcher.preferences.LauncherPreferences",
        makeFile = true,
        r = R.class,
        value = {
                @PreferenceGroup(name = "internal", prefix = "settings_internal_", suffix = "_key", value = {
                        // set after first launch
                        @Preference(name = "started", type = boolean.class, defaultValue = "false"),
                        @Preference(name = "started_time", type = long.class),
                        // see PREFERENCE_VERSION in de.jrpie.android.launcher.preferences.Preferences.kt
                        @Preference(name = "version_code", type = int.class, defaultValue = "-1"),
                }),
                @PreferenceGroup(name = "apps", prefix = "settings_apps_", suffix = "_key", value = {
                        @Preference(name = "hidden", type = Set.class, serializer = SetAbstractAppInfoPreferenceSerializer.class),
                        @Preference(name = "custom_names", type = HashMap.class, serializer = MapAbstractAppInfoStringPreferenceSerializer.class),
                }),
                @PreferenceGroup(name = "general", prefix = "settings_general_", suffix = "_key", value = {
                        @Preference(name = "choose_home_screen", type = void.class),
                }),
                @PreferenceGroup(name = "theme", prefix = "settings_theme_", suffix = "_key", value = {
                        @Preference(name = "color_theme", type = ColorTheme.class, defaultValue = "DEFAULT"),
                        @Preference(name = "monochrome_icons", type = boolean.class, defaultValue = "false"),
                }),
                @PreferenceGroup(name = "display", prefix = "settings_display_", suffix = "_key", value = {
                        @Preference(name = "rotate_screen", type = boolean.class, defaultValue = "true"),
                }),
                @PreferenceGroup(name = "functionality", prefix = "settings_functionality_", suffix = "_key", value = {
                        @Preference(name = "search_auto_launch", type = boolean.class, defaultValue = "true"),
                        @Preference(name = "search_auto_open_keyboard", type = boolean.class, defaultValue = "true"),
                        @Preference(name = "search_auto_close_keyboard", type = boolean.class, defaultValue = "false"),
                }),
                @PreferenceGroup(name = "minimalist", prefix = "settings_minimalist_", suffix = "_key", value = {
                        @Preference(name = "apps", type = Set.class, serializer = SetAbstractAppInfoPreferenceSerializer.class),
                }),
        })
public final class LauncherPreferences$Config {
}
