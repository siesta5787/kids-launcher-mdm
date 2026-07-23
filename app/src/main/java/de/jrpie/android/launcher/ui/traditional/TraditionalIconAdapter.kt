package de.jrpie.android.launcher.ui.traditional

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import de.jrpie.android.launcher.R
import de.jrpie.android.launcher.apps.AbstractDetailedAppInfo

/**
 * A plain icon(+label) [RecyclerView.Adapter], used for both the traditional-mode grid
 * pages and the dock. Tapping an icon launches the app directly, bypassing the gesture
 * system entirely - same pattern as [de.jrpie.android.launcher.ui.minimalist.MinimalistHomeAdapter].
 */
class TraditionalIconAdapter(
    private val activity: Activity,
    @androidx.annotation.LayoutRes private val rowLayoutRes: Int,
    private val apps: List<AbstractDetailedAppInfo>
) : RecyclerView.Adapter<TraditionalIconAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView),
        View.OnClickListener {
        var img: ImageView = itemView.findViewById(R.id.list_apps_row_icon)
        var textView: TextView? = itemView.findViewById(R.id.list_apps_row_name)

        override fun onClick(v: View) {
            val rect = Rect()
            img.getGlobalVisibleRect(rect)
            apps.getOrNull(bindingAdapterPosition)?.getAction()?.invoke(activity, rect)
        }

        init {
            itemView.setOnClickListener(this)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(rowLayoutRes, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        val icon = app.getIcon(activity)
        holder.img.setImageDrawable(icon.constantState?.newDrawable() ?: icon)
        holder.textView?.text = app.getCustomLabel(activity)
    }

    override fun getItemCount(): Int = apps.size
}
