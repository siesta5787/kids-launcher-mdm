package de.jrpie.android.launcher.ui.traditional

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import de.jrpie.android.launcher.Application
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.apps.AbstractDetailedAppInfo
import de.jrpie.android.launcher.apps.AppFilter
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.preferences.list.getNumColumns
import java.util.Locale

/**
 * Drives the "Traditional" home screen mode: a paged grid of every app (via the same
 * [AppFilter] the app drawer already uses - so `apps.hidden` etc. are respected for free)
 * plus a fixed dock row sourced from `traditional.dock_apps`. No manual placement/drag yet;
 * icons auto-fill pages in sorted order (see the plan for how drag-and-drop would extend
 * this later without a rewrite).
 */
class TraditionalHomeController(
    private val activity: Activity,
    private val pager: ViewPager2,
    private val pageIndicator: LinearLayout,
    private val dock: RecyclerView
) {
    private val apps = (activity.applicationContext as Application).apps

    private var iconsPerPage = 0
    private var lastWidth = 0
    private var lastHeight = 0

    // HomeActivity is a plain Activity (not a LifecycleOwner) - see MinimalistHomeAdapter
    // for why this needs observeForever()/destroy() instead of the usual observe().
    private val appsObserver = Observer<List<AbstractDetailedAppInfo>> { updateApps() }

    init {
        dock.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position)
            }
        })

        pager.viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (pager.width > 0 && pager.height > 0 &&
                    (pager.width != lastWidth || pager.height != lastHeight)
                ) {
                    lastWidth = pager.width
                    lastHeight = pager.height
                    iconsPerPage = computeIconsPerPage()
                    updateApps()
                }
            }
        })

        updateApps()
        apps.observeForever(appsObserver)
    }

    fun destroy() {
        apps.removeObserver(appsObserver)
    }

    private fun computeIconsPerPage(): Int {
        val columns = getNumColumns(activity).coerceAtLeast(1)
        val probe = LayoutInflater.from(activity)
            .inflate(R.layout.list_apps_row_variant_grid, pager, false)
        val widthSpec = MeasureSpec.makeMeasureSpec(pager.width / columns, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        probe.measure(widthSpec, heightSpec)
        val rowHeight = probe.measuredHeight.coerceAtLeast(1)
        val rows = (pager.height / rowHeight).coerceAtLeast(1)
        return columns * rows
    }

    fun updateApps() {
        val all = apps.value ?: emptyList()

        val gridApps = AppFilter(activity, "")(all)

        val dockSet = LauncherPreferences.traditional().dockApps() ?: emptySet()
        val dockApps = all.filter { dockSet.contains(it.getRawInfo()) }
            .sortedBy { it.getCustomLabel(activity).lowercase(Locale.ROOT) }
        dock.adapter = TraditionalIconAdapter(
            activity, R.layout.list_apps_row_variant_grid_only_icons, dockApps
        )

        val perPage = if (iconsPerPage > 0) iconsPerPage else getNumColumns(activity).coerceAtLeast(1)
        val pages = if (gridApps.isEmpty()) listOf(emptyList()) else gridApps.chunked(perPage)

        val previousPage = pager.currentItem
        pager.adapter = TraditionalPagerAdapter(activity, pages)
        pager.setCurrentItem(previousPage.coerceIn(0, pages.size - 1), false)

        buildPageIndicator(pages.size)
        updatePageIndicator(pager.currentItem)
    }

    private fun buildPageIndicator(count: Int) {
        pageIndicator.removeAllViews()
        if (count <= 1) {
            pageIndicator.visibility = View.GONE
            return
        }
        pageIndicator.visibility = View.VISIBLE
        for (i in 0 until count) {
            val dot = TextView(activity)
            dot.text = "•"
            dot.textSize = 18f
            dot.setPadding(8, 0, 8, 0)
            pageIndicator.addView(dot)
        }
    }

    private fun updatePageIndicator(position: Int) {
        for (i in 0 until pageIndicator.childCount) {
            (pageIndicator.getChildAt(i) as? TextView)?.alpha = if (i == position) 1f else 0.4f
        }
    }

    private class TraditionalPagerAdapter(
        private val activity: Activity,
        private val pages: List<List<AbstractDetailedAppInfo>>
    ) : RecyclerView.Adapter<TraditionalPagerAdapter.PageViewHolder>() {

        class PageViewHolder(val recyclerView: RecyclerView) : RecyclerView.ViewHolder(recyclerView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val recyclerView = RecyclerView(activity)
            recyclerView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            recyclerView.layoutManager = GridLayoutManager(activity, getNumColumns(activity).coerceAtLeast(1))
            return PageViewHolder(recyclerView)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.recyclerView.adapter =
                TraditionalIconAdapter(activity, R.layout.list_apps_row_variant_grid, pages[position])
        }

        override fun getItemCount(): Int = pages.size
    }
}
