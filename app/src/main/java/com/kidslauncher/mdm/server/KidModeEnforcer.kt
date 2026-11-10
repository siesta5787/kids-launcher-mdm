package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.PolicyResponse
import java.util.Calendar

enum class LockReason {
    NONE, SCREEN_TIME, BEDTIME,

    /** A parent pressed "Lock now" on the admin site - see [PolicyResponse.lockOverride]. */
    PARENT,
}

/** [PolicyResponse.lockOverride] values - anything else (including null) means "follow the schedule". */
const val LOCK_OVERRIDE_LOCK = "lock"
const val LOCK_OVERRIDE_UNLOCK = "unlock"

/**
 * Pure decision logic for whether the device should currently be locked - no Android or network
 * dependencies, so it keeps working from the last-cached policy even when the server is
 * unreachable.
 */
object KidModeEnforcer {

    fun evaluate(policy: PolicyResponse?, now: Calendar): LockReason {
        if (policy == null) return LockReason.NONE

        // A parent's "Lock now"/"Unlock now" from the admin site beats the schedule until it
        // expires. Expiry is checked against the device's own clock (the same clock the schedule
        // below already trusts), so it lapses back to the schedule on time even while offline.
        when (activeLockOverride(policy, now.timeInMillis)) {
            LOCK_OVERRIDE_LOCK -> return LockReason.PARENT
            LOCK_OVERRIDE_UNLOCK -> return LockReason.NONE
        }

        val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        if (isRestricted(minuteOfDay, policy.bedtimeStartMinutes, policy.bedtimeEndMinutes)) {
            return LockReason.BEDTIME
        }

        val isWeekend = now.get(Calendar.DAY_OF_WEEK).let {
            it == Calendar.SATURDAY || it == Calendar.SUNDAY
        }
        val allowedStart = if (isWeekend) policy.weekendStartMinutes else policy.weekdayStartMinutes
        val allowedEnd = if (isWeekend) policy.weekendEndMinutes else policy.weekdayEndMinutes

        return if (isOutsideAllowedWindow(minuteOfDay, allowedStart, allowedEnd)) {
            LockReason.SCREEN_TIME
        } else {
            LockReason.NONE
        }
    }

    /** [PolicyResponse.lockOverride] if it's a known value that hasn't expired yet, else null.
     * A null [PolicyResponse.lockOverrideUntilMs] means it holds until the parent clears it. */
    private fun activeLockOverride(policy: PolicyResponse, nowMillis: Long): String? {
        val override = policy.lockOverride ?: return null
        if (override != LOCK_OVERRIDE_LOCK && override != LOCK_OVERRIDE_UNLOCK) return null
        val until = policy.lockOverrideUntilMs
        if (until != null && nowMillis >= until) return null
        return override
    }

    /** True while [minuteOfDay] falls inside a restricted [start]-[end] window (e.g. bedtime).
     * Handles overnight wraparound (start > end, e.g. 21:00-07:00). Null or start == end means
     * "no restriction". */
    private fun isRestricted(minuteOfDay: Int, start: Int?, end: Int?): Boolean {
        if (start == null || end == null || start == end) return false
        return inWindow(minuteOfDay, start, end)
    }

    /** True while [minuteOfDay] falls outside an allowed [start]-[end] window (e.g. weekday screen
     * time). Handles overnight wraparound. Null or start == end means "always allowed". */
    private fun isOutsideAllowedWindow(minuteOfDay: Int, start: Int?, end: Int?): Boolean {
        if (start == null || end == null || start == end) return false
        return !inWindow(minuteOfDay, start, end)
    }

    private fun inWindow(minuteOfDay: Int, start: Int, end: Int): Boolean {
        return if (start < end) {
            minuteOfDay in start until end
        } else {
            minuteOfDay >= start || minuteOfDay < end
        }
    }
}
