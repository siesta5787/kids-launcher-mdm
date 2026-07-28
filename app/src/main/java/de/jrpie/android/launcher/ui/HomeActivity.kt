package de.jrpie.android.launcher.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import de.jrpie.android.launcher.databinding.ActivityHomeBinding
import de.jrpie.android.launcher.openAppsList
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.requestNotificationPermission
import de.jrpie.android.launcher.setDefaultHomeScreen
import de.jrpie.android.launcher.ui.minimalist.MinimalistHomeAdapter
import kotlin.math.abs

private const val SWIPE_UP_MIN_DISTANCE = 100
private const val SWIPE_UP_MIN_VELOCITY = 100

/**
 * [HomeActivity] is the actual application launcher.
 * It shows a fixed list of chosen apps; swiping up opens the full app drawer.
 */
class HomeActivity : UIObjectActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var minimalistAdapter: MinimalistHomeAdapter
    private lateinit var gestureDetector: GestureDetector

    private var sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, prefKey ->
            if (prefKey?.startsWith("display.") == true) {
                recreate()
            } else if (prefKey?.startsWith("minimalist.") == true) {
                minimalistAdapter.updateAppsList()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialise layout
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        minimalistAdapter = MinimalistHomeAdapter(this)
        binding.homeMinimalistList.layoutManager = LinearLayoutManager(this)
        binding.homeMinimalistList.adapter = minimalistAdapter

        // Back does nothing on the home screen, same as stock Android launchers.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (abs(diffY) > abs(diffX) &&
                    -diffY > SWIPE_UP_MIN_DISTANCE &&
                    abs(velocityY) > SWIPE_UP_MIN_VELOCITY
                ) {
                    openAppsList(this@HomeActivity, excludePinned = true)
                    return true
                }
                return false
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onStart() {
        super.onStart()

        // First launch: mark it done and try to set the default home screen
        if (!LauncherPreferences.internal().started()) {
            LauncherPreferences.internal().started(true)
            LauncherPreferences.internal().startedTime(System.currentTimeMillis() / 1000L)
            setDefaultHomeScreen(this, checkDefault = true)
            requestNotificationPermission(this)
        }

        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
    }

    override fun onResume() {
        super.onResume()
        minimalistAdapter.updateAppsList()
    }

    override fun onDestroy() {
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        minimalistAdapter.destroy()
        super.onDestroy()
    }

    override fun isHomeScreen(): Boolean {
        return true
    }
}
