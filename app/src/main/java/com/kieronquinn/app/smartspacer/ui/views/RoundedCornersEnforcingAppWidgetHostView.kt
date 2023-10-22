package com.kieronquinn.app.smartspacer.ui.views

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.AppWidgetHostViewHidden
import androidx.core.view.setPadding
import com.kieronquinn.app.smartspacer.utils.appwidget.RoundedCornerEnforcement
import com.kieronquinn.app.smartspacer.utils.extensions.setRecursiveLongClickListener

open class RoundedCornersEnforcingAppWidgetHostView(context: Context): AppWidgetHostViewHidden(context) {

    private val mEnforcedRectangle: Rect = Rect()
    private var mEnforcedCornerRadius = RoundedCornerEnforcement.computeEnforcedRadius(getContext())

    private val mCornerRadiusEnforcementOutline: ViewOutlineProvider =
        object : ViewOutlineProvider() {
            override fun getOutline(view: View?, outline: Outline) {
                if (mEnforcedRectangle.isEmpty || mEnforcedCornerRadius <= 0) {
                    outline.setEmpty()
                } else {
                    outline.setRoundRect(mEnforcedRectangle, mEnforcedCornerRadius)
                }
            }
        }


    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        enforceRoundedCorners()
        setRecursiveLongClickListener {
            (parent as? View)?.performLongClick() ?: false
        }
    }

    private fun resetRoundedCorners() {
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = false
    }

    private fun enforceRoundedCorners() {
        if (mEnforcedCornerRadius <= 0) {
            resetRoundedCorners()
            return
        }
        val background: View? = RoundedCornerEnforcement.findBackground(this)
        if (background == null || RoundedCornerEnforcement.hasAppWidgetOptedOut(this, background)) {
            resetRoundedCorners()
            return
        }
        RoundedCornerEnforcement.computeRoundedRectangle(
            this,
            background,
            mEnforcedRectangle
        )
        outlineProvider = mCornerRadiusEnforcementOutline
        clipToOutline = true
    }

    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
        super.setAppWidget(appWidgetId, info)
        setPadding(0)
    }

}