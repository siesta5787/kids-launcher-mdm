package de.jrpie.android.launcher.ui

import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import de.jrpie.android.launcher.actions.Action
import de.jrpie.android.launcher.actions.Gesture
import de.jrpie.android.launcher.actions.LauncherAction
import de.jrpie.android.launcher.databinding.ActivityHomeBinding
import de.jrpie.android.launcher.preferences.HomeMode
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.requestNotificationPermission
import de.jrpie.android.launcher.setDefaultHomeScreen
import de.jrpie.android.launcher.ui.minimalist.MinimalistHomeAdapter
import de.jrpie.android.launcher.ui.util.LauncherGestureActivity


/**
 * [HomeActivity] is the actual application launcher.
 * It listens for gestures.
 */
class HomeActivity : UIObject, LauncherGestureActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var minimalistAdapter: MinimalistHomeAdapter

    private var sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, prefKey ->
            if ( prefKey?.startsWith("display.") == true ) {
                recreate()
            } else if (prefKey?.startsWith("action.") == true) {
                updateSettingsFallbackButtonVisibility()
            } else if (prefKey?.startsWith("minimalist.") == true ||
                prefKey == LauncherPreferences.general().keys().homeMode()
            ) {
                updateHomeMode()
            }

        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super<LauncherGestureActivity>.onCreate(savedInstanceState)
        super<UIObject>.onCreate()

        // Initialise layout
        binding = ActivityHomeBinding.inflate(layoutInflater)

        setContentView(binding.root)

        minimalistAdapter = MinimalistHomeAdapter(this)
        binding.homeMinimalistList.layoutManager = LinearLayoutManager(this)
        binding.homeMinimalistList.adapter = minimalistAdapter

        binding.buttonFallbackSettings.setOnClickListener {
            LauncherAction.SETTINGS.invoke(this)
        }
    }

    private fun updateHomeMode() {
        val mode = LauncherPreferences.general().homeMode()
        binding.homeWidgetContainer.visibility = if (mode == HomeMode.GESTURES) View.VISIBLE else View.GONE
        binding.homeMinimalistContainer.visibility = if (mode == HomeMode.MINIMAL) View.VISIBLE else View.GONE
        when (mode) {
            HomeMode.MINIMAL -> minimalistAdapter.updateAppsList()
            HomeMode.GESTURES -> {}
        }
    }

    override fun onStart() {
        super<LauncherGestureActivity>.onStart()
        super<UIObject>.onStart()

        // First launch: no tutorial, just mark it done and try to set the default home screen
        if (!LauncherPreferences.internal().started()) {
            LauncherPreferences.internal().started(true)
            LauncherPreferences.internal().startedTime(System.currentTimeMillis() / 1000L)
            setDefaultHomeScreen(this, checkDefault = true)
            requestNotificationPermission(this)
        }

        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)

    }

    private fun updateSettingsFallbackButtonVisibility() {
        // If µLauncher settings can not be reached from any action bound to an enabled gesture,
        // show the fallback button.
        binding.buttonFallbackSettings.visibility = if (
            !Gesture.entries.any { g ->
                g.isEnabled() && Action.forGesture(g)?.canReachSettings() == true
            }
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    override fun getTheme(): Resources.Theme {
        return modifyTheme(super.getTheme())
    }

    override fun onResume() {
        super.onResume()
        updateSettingsFallbackButtonVisibility()
        updateHomeMode()
    }


    override fun onDestroy() {
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        minimalistAdapter.destroy()
        super.onDestroy()
    }

    override fun handleBack() {
        Gesture.BACK(this)
    }

    override fun getRootView(): View {
        return binding.root
    }

    override fun isHomeScreen(): Boolean {
        return true
    }
}
