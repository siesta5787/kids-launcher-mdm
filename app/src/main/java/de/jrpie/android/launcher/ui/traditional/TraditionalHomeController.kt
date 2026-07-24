package de.jrpie.android.launcher.ui.traditional

import android.app.Activity
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    // The outer vertical LinearLayout (home_traditional_container) holding pager+indicator+dock.
    // Padding is applied here (not on the ViewPager2 itself) so it goes through completely
    // standard ViewGroup measurement - subtracted from available space before the pager's
    // layout_weight is resolved - rather than depending on how ViewPager2 internally handles
    // padding/clipToPadding for its full-page children, which is far less predictable.
    private val container = pager.parent as ViewGroup

    private var iconsPerPage = 0
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastInsetTop = -1
    private var lastInsetBottom = -1

    private val baseDockBottomPadding = dock.paddingBottom

    // Extra breathing room above the grid, beyond just clearing the status bar - about a
    // third of an app icon (icon size is 40sp, see list_apps_row_variant_grid.xml).
    private val extraTopPaddingPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 13f, activity.resources.displayMetrics
    ).toInt()

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

        // Some devices/Android versions don't reliably propagate the parent's fitsSystemWindows
        // padding down through ViewPager2's internally-created RecyclerView pages, letting the
        // grid's first row render under the status bar. Apply the system bar insets explicitly
        // to the container instead of relying on that propagation.
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (bars.top != lastInsetTop || bars.bottom != lastInsetBottom) {
                lastInsetTop = bars.top
                lastInsetBottom = bars.bottom
                view.setPadding(
                    view.paddingLeft, bars.top + extraTopPaddingPx, view.paddingRight, view.paddingBottom
                )
                dock.setPadding(
                    dock.paddingLeft, dock.paddingTop, dock.paddingRight,
                    baseDockBottomPadding + bars.bottom
                )
                iconsPerPage = computeIconsPerPage()
                updateApps()
            }
            insets
        }

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
        // Inflate against a throwaway RecyclerView (not `pager`) so the resulting LayoutParams
        // is a RecyclerView.LayoutParams / MarginLayoutParams - matching how GridLayoutManager
        // actually lays these rows out - and therefore captures the row's own
        // android:layout_margin (list_apps_row_variant_grid.xml), which a plain measuredHeight
        // read does not include, but GridLayoutManager still spaces rows out by.
        // RecyclerView.generateLayoutParams() delegates to its LayoutManager, which throws
        // if none is set - needs one even though it's never actually laid out.
        val probeParent = RecyclerView(activity).apply {
            layoutManager = GridLayoutManager(activity, columns)
        }
        val probe = LayoutInflater.from(activity)
            .inflate(R.layout.list_apps_row_variant_grid, probeParent, false)
        val widthSpec = MeasureSpec.makeMeasureSpec(pager.width / columns, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        probe.measure(widthSpec, heightSpec)
        val margins = probe.layoutParams as? ViewGroup.MarginLayoutParams
        val rowHeight = (probe.measuredHeight + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0))
            .coerceAtLeast(1)
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
