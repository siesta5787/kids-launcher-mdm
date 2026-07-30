package com.kidslauncher.mdm.preferences;

import java.util.HashMap;
import java.util.Set;

import com.kidslauncher.mdm.R;
import com.kidslauncher.mdm.server.LockReason;
import com.kidslauncher.mdm.preferences.serialization.MapAbstractAppInfoStringPreferenceSerializer;
import com.kidslauncher.mdm.preferences.serialization.SetAbstractAppInfoPreferenceSerializer;
import com.kidslauncher.mdm.preferences.theme.ColorTheme;
import eu.jonahbauer.android.preference.annotations.Preference;
import eu.jonahbauer.android.preference.annotations.PreferenceGroup;
import eu.jonahbauer.android.preference.annotations.Preferences;

@Preferences(
        name = "com.kidslauncher.mdm.preferences.LauncherPreferences",
        makeFile = true,
        r = R.class,
        value = {
                @PreferenceGroup(name = "internal", prefix = "settings_internal_", suffix = "_key", value = {
                        // set after first launch
                        @Preference(name = "started", type = boolean.class, defaultValue = "false"),
                        @Preference(name = "started_time", type = long.class),
                        // see PREFERENCE_VERSION in com.kidslauncher.mdm.preferences.Preferences.kt
                        @Preference(name = "version_code", type = int.class, defaultValue = "-1"),
                }),
                @PreferenceGroup(name = "apps", prefix = "settings_apps_", suffix = "_key", value = {
                        @Preference(name = "hidden", type = Set.class, serializer = SetAbstractAppInfoPreferenceSerializer.class),
                        @Preference(name = "custom_names", type = HashMap.class, serializer = MapAbstractAppInfoStringPreferenceSerializer.class),
                }),
                @PreferenceGroup(name = "theme", prefix = "settings_theme_", suffix = "_key", value = {
                        @Preference(name = "color_theme", type = ColorTheme.class, defaultValue = "DEFAULT"),
                }),
                @PreferenceGroup(name = "display", prefix = "settings_display_", suffix = "_key", value = {
                        @Preference(name = "rotate_screen", type = boolean.class, defaultValue = "true"),
                }),
                @PreferenceGroup(name = "minimalist", prefix = "settings_minimalist_", suffix = "_key", value = {
                        @Preference(name = "apps", type = Set.class, serializer = SetAbstractAppInfoPreferenceSerializer.class),
                }),
                @PreferenceGroup(name = "mdm", prefix = "settings_mdm_", suffix = "_key", value = {
                        @Preference(name = "server_url", type = String.class),
                        // Bearer credential returned by POST /api/devices/enroll - the one-shot
                        // enrollment code used to get it is never itself persisted.
                        @Preference(name = "device_token", type = String.class),
                        @Preference(name = "enrolled", type = boolean.class, defaultValue = "false"),
                        @Preference(name = "kid_mode_policy", type = String.class),
                        // Current lock decision, persisted so LockActivity/HomeActivity can react
                        // via the usual SharedPreferences-listener pattern instead of a broadcast.
                        @Preference(name = "lock_reason", type = LockReason.class, defaultValue = "NONE"),
                        // Computed handoff flag: true once AppEnforcer has actually configured DPM
                        // lock-task state (server-authoritative via PolicyResponse.kioskDesired -
                        // there is no local toggle). HomeActivity reads this to decide whether to
                        // call startLockTask()/stopLockTask().
                        @Preference(name = "kiosk_enabled", type = boolean.class, defaultValue = "false"),
                }),
        })
public final class LauncherPreferences$Config {
}
