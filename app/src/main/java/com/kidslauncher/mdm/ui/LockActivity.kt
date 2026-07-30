package com.kidslauncher.mdm.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityLockBinding
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.reevaluateLockReasonFromCache
import com.kidslauncher.mdm.preferences.LauncherPreferences

private const val LOCK_REASON_REFRESH_INTERVAL_MS = 60_000L

/**
 * Full-screen block shown while [LockReason] (from [com.kidslauncher.mdm.server.MdmSyncWorker])
 * is anything other than [LockReason.NONE]. No way to dismiss it besides the lock actually
 * clearing - back is a no-op, there's no close button.
 */
class LockActivity : UIObjectActivity() {
    private lateinit var binding: ActivityLockBinding

    private val sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, prefKey ->
            if (prefKey == LauncherPreferences.mdm().keys().lockReason()) {
                finishIfUnlocked()
            }
        }

    private val refreshHandler = Handler(Looper.getMainLooper())

    // Re-checks the schedule against the device's own clock every minute while this screen is
    // showing - otherwise the lock would only ever clear whenever the next ~15-minute background
    // sync happens to land, which could leave someone stuck well after their allowed time began.
    private val refreshRunnable = object : Runnable {
        override fun run() {
            reevaluateLockReasonFromCache()
            refreshHandler.postDelayed(this, LOCK_REASON_REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
    }

    override fun onStart() {
        super.onStart()
        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
        refreshHandler.post(refreshRunnable)
        updateMessageOrFinish()
    }

    override fun onStop() {
        refreshHandler.removeCallbacks(refreshRunnable)
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        super.onStop()
    }

    private fun finishIfUnlocked() {
        if (LauncherPreferences.mdm().lockReason() == LockReason.NONE) {
            finish()
        } else {
            updateMessageOrFinish()
        }
    }

    private fun updateMessageOrFinish() {
        when (LauncherPreferences.mdm().lockReason()) {
            LockReason.BEDTIME -> binding.lockMessage.setText(R.string.lock_reason_bedtime)
            LockReason.SCREEN_TIME -> binding.lockMessage.setText(R.string.lock_reason_screen_time)
            LockReason.NONE -> finish()
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, LockActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
