package com.kidslauncher.mdm.ui

import android.app.ActivityManager
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import com.kidslauncher.mdm.databinding.ActivityHomeBinding
import com.kidslauncher.mdm.headwind.LockReason
import com.kidslauncher.mdm.openAppsList
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.requestNotificationPermission
import com.kidslauncher.mdm.setDefaultHomeScreen
import com.kidslauncher.mdm.ui.minimalist.MinimalistHomeAdapter
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
            } else if (prefKey == LauncherPreferences.mdm().keys().lockReason()) {
                redirectToLockScreenIfLocked()
            } else if (prefKey == LauncherPreferences.mdm().keys().kioskEnabled()) {
                reconcileKioskMode()
            } else {
                // covers minimalist. (added/removed), apps.hidden (hidden while shown here)
                // and apps.custom_names (renamed) - all of which can change via the
                // home screen's own long-press menu.
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
        // Must run before the lock-screen check below: while the bedtime/screen-time block is
        // showing is exactly when kiosk pinning should also be engaged, so the kid can't use
        // recents/home/notification-shade to route around LockActivity.
        reconcileKioskMode()
        // Checked here (not just via the preference listener) so pressing Home while the lock
        // screen is showing can't be used to bounce back into the drawer/home list underneath it.
        if (redirectToLockScreenIfLocked()) return
        minimalistAdapter.updateAppsList()
    }

    /** @return true if currently locked (and [LockActivity] was launched). */
    private fun redirectToLockScreenIfLocked(): Boolean {
        if (LauncherPreferences.mdm().lockReason() != LockReason.NONE) {
            LockActivity.start(this)
            return true
        }
        return false
    }

    /**
     * Entering lock-task mode is never automatic on the OS side (only removing the pinned
     * package from DevicePolicyManager.setLockTaskPackages() auto-exits it) - AppEnforcer only
     * configures the DPM-side state from a background Worker, so an Activity has to actually
     * call startLockTask()/stopLockTask() to enter/exit. Runs on every onResume() since neither
     * the pinned state nor this check survives reboot/process death on their own.
     */
    private fun reconcileKioskMode() {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val currentlyLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            activityManager.isInLockTaskMode
        }
        val shouldBeLocked = LauncherPreferences.mdm().kioskEnabled()

        if (shouldBeLocked && !currentlyLocked) {
            startLockTask()
        } else if (!shouldBeLocked && currentlyLocked) {
            stopLockTask()
        }
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
