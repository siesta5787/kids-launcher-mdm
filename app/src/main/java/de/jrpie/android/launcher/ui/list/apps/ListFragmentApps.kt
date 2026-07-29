package de.jrpie.android.launcher.ui.list.apps

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.apps.AppFilter
import de.jrpie.android.launcher.databinding.ListAppsBinding
import de.jrpie.android.launcher.preferences.LauncherPreferences
import de.jrpie.android.launcher.ui.UIObject
import de.jrpie.android.launcher.ui.closeSoftKeyboard
import de.jrpie.android.launcher.ui.list.AbstractListActivity
import de.jrpie.android.launcher.ui.openSoftKeyboard
import kotlin.math.absoluteValue


/**
 * The [ListFragmentApps] is used as a tab in ListActivity.
 *
 * It is a list of all installed applications that can be launched.
 */
class ListFragmentApps : Fragment(), UIObject {
    private lateinit var binding: ListAppsBinding
    private lateinit var appsRecyclerAdapter: AppsRecyclerAdapter


    private var sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            appsRecyclerAdapter.updateAppsList()
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ListAppsBinding.inflate(inflater)
        return binding.root
    }

    override fun onStart() {
        super<Fragment>.onStart()
        super<UIObject>.onStart()
        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
    }

    override fun onStop() {
        super.onStop()
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
    }


    override fun setOnClicks() {}

    override fun adjustLayout() {
        val listActivity = (activity as? AbstractListActivity) ?: return

        appsRecyclerAdapter =
            AppsRecyclerAdapter(
                listActivity, binding.root,
                appFilter = AppFilter(
                    requireContext(),
                    "",
                    hiddenVisibility = listActivity.hiddenVisibility,
                    pinnedVisibility = listActivity.pinnedVisibility
                )
            )


        // set up the list / recycler
        binding.listAppsRview.apply {
            // improve performance (since content changes don't change the layout size)
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(context)
            adapter = appsRecyclerAdapter
            if (LauncherPreferences.functionality().searchAutoCloseKeyboard()) {
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    var totalDy: Int = 0
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        totalDy += dy

                        if (totalDy.absoluteValue > 100) {
                            totalDy = 0
                            closeSoftKeyboard(requireActivity())
                        }
                    }
                })
            }
        }

        binding.listAppsSearchview.setOnQueryTextListener(object :
            androidx.appcompat.widget.SearchView.OnQueryTextListener {

            override fun onQueryTextSubmit(query: String): Boolean {
                appsRecyclerAdapter.setSearchString(query)
                appsRecyclerAdapter.selectItem(0)
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {

                if (newText == " " &&
                    !appsRecyclerAdapter.disableAutoLaunch &&
                    LauncherPreferences.functionality().searchAutoLaunch()
                ) {
                    appsRecyclerAdapter.disableAutoLaunch = true
                    binding.listAppsSearchview.apply {
                        queryHint = context.getString(R.string.list_apps_search_hint_no_auto_launch)
                        setQuery("", false)
                    }
                    return false
                }

                appsRecyclerAdapter.setSearchString(newText)
                return false
            }
        })

        if (LauncherPreferences.functionality().searchAutoOpenKeyboard()) {
            binding.listAppsSearchview.openSoftKeyboard(requireContext())
        }
    }

}
