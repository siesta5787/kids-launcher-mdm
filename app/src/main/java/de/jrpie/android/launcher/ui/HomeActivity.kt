package de.jrpie.android.launcher.ui

import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import de.jrpie.android.launcher.Application
import de.jrpie.android.launcher.actions.Action
import de.jrpie.android.launcher.actions.Gesture
import de.jrpie.android.launcher.actions.LauncherAction
import de.jrpie.android.launcher.databinding.ActivityHomeBinding
import de.jrpie.android.launcher.openTutorial
import de.jrpie.android.launcher.preferences.HomeMode
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.ui.minimalist.MinimalistHomeAdapter
import de.jrpie.android.launcher.ui.traditional.TraditionalHomeController
import de.jrpie.android.launcher.ui.util.LauncherGestureActivity


/**
 * [HomeActivity] is the actual application launcher.
 * It displays widgets (usually just the clock)
 * and listens for gestures.
 */
class HomeActivity : UIObject, LauncherGestureActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var minimalistAdapter: MinimalistHomeAdapter
    private lateinit var traditionalController: TraditionalHomeController

    private var sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, prefKey ->
            if ( prefKey?.startsWith("display.") == true ) {
                recreate()
            } else if (prefKey?.startsWith("action.") == true) {
                updateSettingsFallbackButtonVisibility()
            } else if (prefKey == LauncherPreferences.widgets().keys().widgets()) {
                binding.homeWidgetContainer.updateWidgets(
                    this@HomeActivity,
                    LauncherPreferences.widgets().widgets()
                )
            } else if (prefKey?.startsWith("minimalist.") == true ||
                prefKey?.startsWith("traditional.") == true ||
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

        traditionalController = TraditionalHomeController(
            this,
            binding.homeTraditionalPager,
            binding.homeTraditionalPageIndicator,
            binding.homeTraditionalDock
        )

        binding.buttonFallbackSettings.setOnClickListener {
            LauncherAction.SETTINGS.invoke(this)
        }
    }

    private fun updateHomeMode() {
        val mode = LauncherPreferences.general().homeMode()
        binding.homeWidgetContainer.visibility = if (mode == HomeMode.GESTURES) View.VISIBLE else View.GONE
        binding.homeMinimalistContainer.visibility = if (mode == HomeMode.MINIMAL) View.VISIBLE else View.GONE
        binding.homeTraditionalContainer.visibility = if (mode == HomeMode.TRADITIONAL) View.VISIBLE else View.GONE
        when (mode) {
            HomeMode.MINIMAL -> minimalistAdapter.updateAppsList()
            HomeMode.TRADITIONAL -> traditionalController.updateApps()
            HomeMode.GESTURES -> {}
        }
    }

    override fun onStart() {
        super<LauncherGestureActivity>.onStart()
        super<UIObject>.onStart()

        // If the tutorial was not finished, start it
        if (!LauncherPreferences.internal().started()) {
            openTutorial(this)
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

    override fun onPause() {
        try {
            (application as Application).appWidgetHost.stopListening()
        } catch (e: Exception) {
            // Throws a NullPointerException on Android 12 an earlier, see #172
            e.printStackTrace()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        updateSettingsFallbackButtonVisibility()
        updateHomeMode()

        binding.homeWidgetContainer.updateWidgets(
            this@HomeActivity,
            LauncherPreferences.widgets().widgets()
        )

        (application as Application).appWidgetHost.startListening()
    }


    override fun onDestroy() {
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        minimalistAdapter.destroy()
        traditionalController.destroy()
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
