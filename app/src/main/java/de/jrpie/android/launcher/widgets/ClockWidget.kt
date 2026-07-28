package de.jrpie.android.launcher.widgets

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Retained only so preferences persisted before the clock widget was removed still
 * deserialize without crashing - removeStrayWidgets() in Preferences.kt strips any
 * instance of this on the next app start, and it can no longer be added.
 */
@Serializable
@SerialName("widget:clock")
class ClockWidget(
    override var id: Int,
    override var position: WidgetPosition,
    override val panelId: Int,
    override var allowInteraction: Boolean = true
) : Widget() {

    override fun createView(activity: Activity): View? = null

    override fun findView(views: Sequence<View>): View? = null

    override fun getPreview(context: Context): Drawable? = null

    override fun getIcon(context: Context): Drawable? = null

    override fun isConfigurable(context: Context): Boolean = false

    override fun isReconfigurable(context: Context): Boolean = false

    override fun configure(activity: Activity, requestCode: Int) {}
}
