package com.kidslauncher.mdm.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityLockBinding
import com.kidslauncher.mdm.headwind.LockReason
import com.kidslauncher.mdm.preferences.LauncherPreferences

/**
 * Full-screen block shown while [LockReason] (from [com.kidslauncher.mdm.headwind.MdmSyncWorker])
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
        updateMessageOrFinish()
    }

    override fun onStop() {
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
