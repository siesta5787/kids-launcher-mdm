package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.ForegroundEvent.Kind.PAUSED
import com.kidslauncher.mdm.server.ForegroundEvent.Kind.RESUMED
import com.kidslauncher.mdm.server.ForegroundEvent.Kind.SCREEN_OFF
import com.kidslauncher.mdm.server.dto.AppGroupPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class AppGroupRulesTest {

    private fun at(hour: Int, minute: Int): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun group(
        limit: Int? = null,
        start: Int? = null,
        end: Int? = null,
        override: String? = null,
        overrideUntil: Long? = null,
    ) = AppGroupPolicy(
        id = 1,
        name = "Streaming",
        packages = listOf("tv.example"),
        dailyLimitMinutes = limit,
        allowedStartMinutes = start,
        allowedEndMinutes = end,
        overrideMode = override,
        overrideUntilMs = overrideUntil,
    )

    @Test
    fun `group with no rules is never blocked`() {
        assertNull(AppGroupRules.blockReason(group(), at(23, 0), usedMinutes = 500))
    }

    @Test
    fun `daily limit blocks once used up, and only when time can be counted`() {
        val g = group(limit = 60)
        assertNull(AppGroupRules.blockReason(g, at(12, 0), usedMinutes = 59))
        assertEquals(GroupBlockReason.TIME_UP, AppGroupRules.blockReason(g, at(12, 0), usedMinutes = 60))
        // No usage access: the limit can't be enforced, so it isn't (rather than blocking forever).
        assertNull(AppGroupRules.blockReason(g, at(12, 0), usedMinutes = null))
    }

    @Test
    fun `allowed hours block outside the window, including overnight windows`() {
        val afternoon = group(start = 15 * 60, end = 20 * 60)
        assertEquals(GroupBlockReason.OUTSIDE_HOURS, AppGroupRules.blockReason(afternoon, at(14, 59), 0))
        assertNull(AppGroupRules.blockReason(afternoon, at(15, 0), 0))
        assertEquals(GroupBlockReason.OUTSIDE_HOURS, AppGroupRules.blockReason(afternoon, at(20, 0), 0))

        val overnight = group(start = 22 * 60, end = 2 * 60)
        assertNull(AppGroupRules.blockReason(overnight, at(23, 30), 0))
        assertNull(AppGroupRules.blockReason(overnight, at(1, 0), 0))
        assertEquals(GroupBlockReason.OUTSIDE_HOURS, AppGroupRules.blockReason(overnight, at(12, 0), 0))

        // start == end means "any time", same as the phone's main schedule.
        assertNull(AppGroupRules.blockReason(group(start = 600, end = 600), at(3, 0), 0))
    }

    @Test
    fun `parent pause and allow beat the rules until they expire`() {
        val now = at(12, 0)
        val later = now.timeInMillis + 60_000

        val paused = group(override = GROUP_OVERRIDE_PAUSE, overrideUntil = later)
        assertEquals(GroupBlockReason.PAUSED, AppGroupRules.blockReason(paused, now, 0))

        val allowed = group(limit = 30, start = 15 * 60, end = 16 * 60, override = GROUP_OVERRIDE_ALLOW)
        assertNull(AppGroupRules.blockReason(allowed, now, usedMinutes = 999))

        val expired = group(limit = 30, override = GROUP_OVERRIDE_ALLOW, overrideUntil = now.timeInMillis)
        assertEquals(GroupBlockReason.TIME_UP, AppGroupRules.blockReason(expired, now, usedMinutes = 30))

        assertNull(AppGroupRules.blockReason(group(override = "bogus"), now, 0))
    }

    @Test
    fun `foreground time counts one app at a time and clips to the day`() {
        val midnight = 1_000_000L
        val min = 60_000L
        val events = listOf(
            // Already open before midnight - only counted from midnight.
            ForegroundEvent(midnight - 10 * min, "tv.example", RESUMED),
            ForegroundEvent(midnight + 5 * min, "tv.example", PAUSED),
            ForegroundEvent(midnight + 6 * min, "game.example", RESUMED),
            // Switching straight to another app (no pause seen) ends the first one.
            ForegroundEvent(midnight + 10 * min, "tv.example", RESUMED),
            ForegroundEvent(midnight + 12 * min, null, SCREEN_OFF),
            // A pause for an app that isn't in front doesn't affect anything.
            ForegroundEvent(midnight + 13 * min, "game.example", PAUSED),
            ForegroundEvent(midnight + 20 * min, "game.example", RESUMED),
        )
        val (totals, onScreen) = AppGroupRules.foregroundTime(events, midnight, midnight + 25 * min)
        assertEquals(7 * min, totals["tv.example"])
        assertEquals(9 * min, totals["game.example"])
        assertEquals("game.example", onScreen)
    }

    @Test
    fun `nothing on screen after the screen goes off`() {
        val events = listOf(
            ForegroundEvent(0, "tv.example", RESUMED),
            ForegroundEvent(1_000, null, SCREEN_OFF),
        )
        val (totals, onScreen) = AppGroupRules.foregroundTime(events, 0, 5_000)
        assertEquals(1_000L, totals["tv.example"])
        assertNull(onScreen)
    }
}
