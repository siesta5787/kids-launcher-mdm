package com.kidslauncher.mdm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.UserHandle
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.apps.AbstractAppInfo
import com.kidslauncher.mdm.apps.AbstractDetailedAppInfo
import com.kidslauncher.mdm.server.CommandListenerService
import com.kidslauncher.mdm.server.KidVpnService
import com.kidslauncher.mdm.server.TsnetClient
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.preferences.migratePreferencesToNewVersion
import com.kidslauncher.mdm.preferences.resetPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.system.exitProcess


class Application : android.app.Application() {
    val apps = MutableLiveData<List<AbstractDetailedAppInfo>>()

    private val profileAvailabilityBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // TODO: only update specific apps
            // use Intent.EXTRA_USER
            loadApps()
        }
    }

    // TODO: only update specific apps
    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p0: String?, p1: UserHandle?) {
            loadApps()
        }

        override fun onPackageAdded(p0: String?, p1: UserHandle?) {
            loadApps()
        }

        override fun onPackageChanged(p0: String?, p1: UserHandle?) {
            loadApps()
        }

        override fun onPackagesAvailable(p0: Array<out String>?, p1: UserHandle?, p2: Boolean) {
            // TODO
        }

        override fun onPackagesSuspended(packageNames: Array<out String>?, user: UserHandle?) {
            loadApps()
        }

        override fun onPackagesUnsuspended(packageNames: Array<out String>?, user: UserHandle?) {
            loadApps()
        }

        override fun onPackagesUnavailable(p0: Array<out String>?, p1: UserHandle?, p2: Boolean) {
            // TODO
        }

        override fun onPackageLoadingProgressChanged(
            packageName: String,
            user: UserHandle,
            progress: Float
        ) {
            // TODO
        }

        override fun onShortcutsChanged(
            packageName: String,
            shortcuts: MutableList<ShortcutInfo>,
            user: UserHandle
        ) {
            // TODO
        }
    }

    private var customAppNames: HashMap<AbstractAppInfo, String>? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, pref ->
        if (pref == getString(R.string.settings_apps_custom_names_key)) {
            customAppNames = LauncherPreferences.apps().customNames()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // TODO  Error: Invalid resource ID 0x00000000.
        // DynamicColors.applyToActivitiesIfAvailable(this)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            sendCrashNotification(this@Application, throwable)
            exitProcess(1)
        }


        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        LauncherPreferences.init(preferences, this.resources)

        // Try to restore old preferences
        migratePreferencesToNewVersion(this)

        // First time opening the app: set defaults
        // (the rest of first-launch setup happens in HomeActivity#onStart)
        if (!LauncherPreferences.internal().started()) {
            resetPreferences(this)
        }


        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(listener)


        val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
        launcherApps.registerCallback(launcherAppsCallback)

        if (Build.VERSION.SDK_INT >= VERSION_CODES.N) {
            val filter = IntentFilter().also {
                if (Build.VERSION.SDK_INT >= VERSION_CODES.VANILLA_ICE_CREAM) {
                    it.addAction(Intent.ACTION_PROFILE_AVAILABLE)
                    it.addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                } else {
                    it.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                    it.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                }
            }
            ContextCompat.registerReceiver(
                this, profileAvailabilityBroadcastReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }

        loadApps()

        createNotificationChannels(this)

        // CommandListenerService both holds the SSE connection and drives the periodic backstop
        // sync directly off its own timer - see that class's doc comment for why this replaced a
        // separate WorkManager-based schedule() call here.
        CommandListenerService.start(this)

        // The on-device DNS filter and the embedded tailnet connection are both the device's
        // baseline network path now, not admin-configurable features - see CLAUDE.md's
        // on-device-filtering/embedded-tsnet migration writeup. Both fail soft if not ready yet
        // (no VPN consent granted, no auth key configured) and get retried on the next sync cycle
        // (KidVpnService via Android's own always-on-VPN management once AppEnforcer.apply grants
        // consent; TsnetClient via MdmSyncWorker's connectFromPreferences call) - see each class's
        // own doc comment.
        KidVpnService.start(this)
        CoroutineScope(Dispatchers.IO).launch { TsnetClient.connectFromPreferences(this@Application) }
    }

    fun getCustomAppNames(): HashMap<AbstractAppInfo, String> {
        return (customAppNames ?: LauncherPreferences.apps().customNames() ?: HashMap())
            .also { customAppNames = it }
    }

    private fun loadApps() {
        CoroutineScope(Dispatchers.Default).launch {
            apps.postValue(getApps(packageManager, applicationContext))
        }
    }
}
