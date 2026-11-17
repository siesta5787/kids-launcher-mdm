package com.kidslauncher.mdm.server

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.AppGroupPolicy
import com.kidslauncher.mdm.server.dto.AppGroupUsageReport
import com.kidslauncher.mdm.server.dto.PolicyResponse
import java.util.Calendar
import java.util.Locale

private const val LOG_TAG = "AppGroups"

/** Why an app group's apps are blocked right now - reported to the server by name. */
enum class GroupBlockReason { PAUSED, OUTSIDE_HOURS, TIME_UP }

/** [AppGroupPolicy.overrideMode] values - anything else (including null) means "follow the rules". */
const val GROUP_OVERRIDE_PAUSE = "pause"
const val GROUP_OVERRIDE_ALLOW = "allow"

/** One foreground-relevant usage event, reduced to what [AppGroupRules.foregroundTime] needs. */
data class ForegroundEvent(val timeMs: Long, val packageName: String?, val kind: Kind) {
    enum class Kind { RESUMED, PAUSED, SCREEN_OFF }
}

/**
 * Pure decision logic for app groups - no Android dependencies, so it's unit-tested
 * (AppGroupRulesTest) and works from the cached policy while offline, same as [KidModeEnforcer].
 */
object AppGroupRules {

    /**
     * Why [group] is blocked at [now], or null when its apps are usable. A parent's override wins
     * (Pause blocks, Allow lets everything through), then allowed hours, then the daily limit.
     * [usedMinutes] null means time can't be counted (no usage access) - the limit is then not
     * enforced rather than blocking the group forever; hours and Pause still apply.
     */
    fun blockReason(group: AppGroupPolicy, now: Calendar, usedMinutes: Int?): GroupBlockReason? {
        when (activeOverride(group, now.timeInMillis)) {
            GROUP_OVERRIDE_PAUSE -> return GroupBlockReason.PAUSED
            GROUP_OVERRIDE_ALLOW -> return null
        }
        val start = group.allowedStartMinutes
        val end = group.allowedEndMinutes
        if (start != null && end != null && start != end) {
            val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            if (!KidModeEnforcer.inWindow(minuteOfDay, start, end)) return GroupBlockReason.OUTSIDE_HOURS
        }
        val limit = group.dailyLimitMinutes
        if (limit != null && usedMinutes != null && usedMinutes >= limit) return GroupBlockReason.TIME_UP
        return null
    }

    /** [AppGroupPolicy.overrideMode] if it's a known value that hasn't expired yet, else null. */
    fun activeOverride(group: AppGroupPolicy, nowMillis: Long): String? {
        val mode = group.overrideMode ?: return null
        if (mode != GROUP_OVERRIDE_PAUSE && mode != GROUP_OVERRIDE_ALLOW) return null
        val until = group.overrideUntilMs
        if (until != null && nowMillis >= until) return null
        return mode
    }

    /**
     * Foreground time per package between [fromMs] and [toMs], from time-ordered [events] (which
     * may start before [fromMs], so an app already open at midnight is counted from midnight).
     * Only one app is in the foreground at a time: a resume of another app ends the current one,
     * as does the screen going off. Also returns the package still in the foreground at [toMs].
     */
    fun foregroundTime(events: List<ForegroundEvent>, fromMs: Long, toMs: Long): Pair<Map<String, Long>, String?> {
        val totals = HashMap<String, Long>()
        var current: String? = null
        var since = 0L
        fun close(at: Long) {
            val pkg = current ?: return
            val start = maxOf(since, fromMs)
            val end = minOf(at, toMs)
            if (end > start) totals[pkg] = (totals[pkg] ?: 0L) + (end - start)
            current = null
        }
        for (event in events) {
            if (event.timeMs > toMs) break
            when (event.kind) {
                ForegroundEvent.Kind.RESUMED -> if (event.packageName != current) {
                    close(event.timeMs)
                    current = event.packageName
                    since = event.timeMs
                }
                ForegroundEvent.Kind.PAUSED -> if (event.packageName == current) close(event.timeMs)
                ForegroundEvent.Kind.SCREEN_OFF -> close(event.timeMs)
            }
        }
        val stillOpen = current
        close(toMs)
        return totals to stillOpen
    }
}

/**
 * App groups on the device (server v0.23.0+): a parent-named set of apps sharing one daily limit,
 * one allowed-hours window and one Pause/Allow override. A blocked group's apps are *suspended*
 * (greyed out, Android's "can't open" dialog) but never hidden or uninstalled - unlike apps off the
 * allowlist, which [AppEnforcer] also hides. Time is counted on the device from Android's own usage
 * events, so it keeps working offline; it needs usage access, which a Device Owner can't grant
 * itself (only system-only observer APIs exempt it - checked against AOSP's UsageStatsService), so
 * a parent allows it once from this app's Settings ("App time tracking").
 *
 * Enforcement runs from [AppEnforcer.apply] on every sync and from [tick], which
 * [CommandListenerService] calls every minute so a limit or a window edge lands on time rather
 * than up to five minutes late. Lifted entirely while the offline override or the pause kill-switch
 * is on, like every other restriction (see this repo's CLAUDE.md standing rule).
 */
object AppGroups {

    /** How far before midnight to read events, so an app already open at midnight is counted. */
    private const val LOOKBACK_MS = 3 * 60 * 60 * 1000L

    data class GroupState(val group: AppGroupPolicy, val usedMinutes: Int?, val blockReason: GroupBlockReason?)

    data class Evaluation(val states: List<GroupState>, val foregroundPackage: String?) {
        val blockedPackages: Set<String>
            get() = states.filter { it.blockReason != null }.flatMap { it.group.packages }.toSet()
    }

    private val NONE = Evaluation(emptyList(), null)

    /** The packages last suspended because of a blocked group - lets [tick] skip all work when
     * nothing changed. Null until the first [enforce] after process start. */
    @Volatile
    private var lastApplied: Set<String>? = null

    fun hasUsageAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        )
        if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't check usage access", e)
        false
    }

    /** Every group's state right now. [effectivePolicy] null (override active / no policy yet)
     * means nothing is blocked. Usage events are read whenever there are groups at all - for the
     * limits, and to know which app is on screen (see [enforce]). */
    fun evaluate(context: Context, effectivePolicy: PolicyResponse?, now: Calendar = Calendar.getInstance()): Evaluation {
        val groups = effectivePolicy?.appGroups.orEmpty().filter { it.packages.isNotEmpty() }
        if (groups.isEmpty()) return NONE
        val usage = foregroundToday(context, now)
        val states = groups.map { group ->
            val used = usage?.first?.let { totals ->
                (group.packages.sumOf { totals[it] ?: 0L } / 60_000L).toInt()
            }
            GroupState(group, used, AppGroupRules.blockReason(group, now, used))
        }
        return Evaluation(states, usage?.second)
    }

    /** For the status report: every group's minutes used today and block reason. */
    fun usageReport(context: Context, policy: PolicyResponse?): List<AppGroupUsageReport> {
        val mdm = LauncherPreferences.mdm()
        val overrideActive = OfflineOverride.isActive() || mdm.restrictionsPaused()
        val now = Calendar.getInstance()
        val day = String.format(
            Locale.ROOT, "%04d-%02d-%02d",
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
        )
        // Count time even while restrictions are lifted, so the parent still sees it - just
        // nothing is blocked then.
        val counted = evaluate(context, policy, now).states.associateBy { it.group.id }
        return policy?.appGroups.orEmpty().map { group ->
            val state = counted[group.id]
            AppGroupUsageReport(
                groupId = group.id,
                usedMinutes = state?.usedMinutes,
                day = day,
                blockedReason = if (overrideActive) null else state?.blockReason?.name,
            )
        }
    }

    /**
     * Brings every controllable package's suspended/hidden state in line with [effectivePolicy]
     * (via [AppEnforcer.applyPackageStates]) - the one path both [AppEnforcer.apply] and [tick] use.
     * When a group has just become blocked while one of its apps is on screen, also sends the kid
     * back to the home screen: suspending an app doesn't close it if it's already open (AOSP's
     * onPackagesSuspendedChanged only updates recents). That needs usage access to know what's on
     * screen; without it the app stays open until the kid leaves it, then can't be reopened.
     */
    @Synchronized
    fun enforce(context: Context, effectivePolicy: PolicyResponse?, evaluation: Evaluation = evaluate(context, effectivePolicy)) {
        // The default dialer is never group-blocked - see AppEnforcer.applyPackageStates.
        val blocked = evaluation.blockedPackages - setOfNotNull(AppEnforcer.defaultDialerPackage(context))
        val previous = lastApplied
        AppEnforcer.applyPackageStates(context, effectivePolicy, blocked)
        lastApplied = blocked

        val foreground = evaluation.foregroundPackage
        if (foreground != null && foreground in blocked && foreground !in previous.orEmpty()) {
            sendHome(context, evaluation.states.firstOrNull { it.blockReason != null && foreground in it.group.packages })
        }
    }

    /**
     * Every-minute check from [CommandListenerService] (unlocked mode only), so a limit or an
     * allowed-hours edge takes effect on time instead of at the next sync. Cheap when nothing
     * changed: one usage-events read (only when groups exist) and a set comparison.
     */
    fun tick(context: Context) {
        try {
            val mdm = LauncherPreferences.mdm()
            val effectivePolicy =
                if (OfflineOverride.isActive() || mdm.restrictionsPaused()) null else cachedPolicy()
            val evaluation = evaluate(context, effectivePolicy)
            val blocked = evaluation.blockedPackages - setOfNotNull(AppEnforcer.defaultDialerPackage(context))
            if (blocked == lastApplied) return
            enforce(context, effectivePolicy, evaluation)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "App group tick failed", e)
        }
    }

    private fun sendHome(context: Context, state: GroupState?) {
        try {
            // Device Owner is exempt from background-activity-start limits
            // (BackgroundActivityStartController's isDeviceOwner check), so this works from a
            // service.
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't return to the home screen", e)
        }
        val message = when (state?.blockReason) {
            GroupBlockReason.TIME_UP -> context.getString(R.string.app_group_time_up, state.group.name)
            GroupBlockReason.OUTSIDE_HOURS -> context.getString(R.string.app_group_outside_hours, state.group.name)
            else -> context.getString(R.string.app_group_paused, state?.group?.name.orEmpty())
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Foreground millis per package since local midnight, plus the app on screen right now -
     * or null without usage access. */
    private fun foregroundToday(context: Context, now: Calendar): Pair<Map<String, Long>, String?>? {
        if (!hasUsageAccess(context)) return null
        val nowMs = now.timeInMillis
        val midnight = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val events = try {
            context.getSystemService(UsageStatsManager::class.java)
                .queryEvents(midnight - LOOKBACK_MS, nowMs)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "queryEvents failed", e)
            return null
        } ?: return null

        val list = ArrayList<ForegroundEvent>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val kind = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> ForegroundEvent.Kind.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> ForegroundEvent.Kind.PAUSED
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.DEVICE_SHUTDOWN -> ForegroundEvent.Kind.SCREEN_OFF
                else -> null
            } ?: continue
            list.add(ForegroundEvent(event.timeStamp, event.packageName, kind))
        }
        return AppGroupRules.foregroundTime(list, midnight, nowMs)
    }
}
