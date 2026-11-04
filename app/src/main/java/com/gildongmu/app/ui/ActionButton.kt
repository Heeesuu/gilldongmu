package com.gildongmu.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.gildongmu.app.R

/**
 * 아이콘과 글자를 함께 쓰는 큰 버튼 (Pleos 디자인 가이드: 아이콘에는 글자를 붙인다).
 * 누르면 진동으로 알려 준다 (가이드: 터치에 진동이나 소리로 반응).
 */
class ActionButton(context: Context, label: String, icon: Int, look: Look, textSp: Float = 28f) : LinearLayout(context) {

    enum class Look { GO, SEED, REC, OUTLINE, SELECTED }

    private val density = resources.displayMetrics.density
    private val iconView = ImageView(context)
    private val labelView = TextView(context).apply {
        text = label
        textSize = textSp
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        maxLines = 1
    }
    private var iconRes = icon

    var look: Look = look
        set(value) {
            if (field == value) return
            field = value
            applyLook()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding(dp(24f), 0, dp(24f), 0)
        val iconSize = dp(textSp * 1.3f)
        addView(iconView, LayoutParams(iconSize, iconSize))
        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { if (icon != NO_ICON) marginStart = dp(12f) })
        contentDescription = label
        if (icon == NO_ICON) iconView.visibility = GONE else iconView.setImageResource(icon)
        applyLook()
    }

    fun setLabel(label: String) {
        if (labelView.text.toString() == label) return
        labelView.text = label
        contentDescription = label
    }

    fun setIcon(icon: Int) {
        if (iconRes == icon) return
        iconRes = icon
        iconView.setImageResource(icon)
    }

    fun onClick(action: () -> Unit) = setOnClickListener {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        action()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    private fun applyLook() {
        val (fill, fg) = when (look) {
            Look.GO -> R.color.gd_go to R.color.gd_on_go
            Look.SEED -> R.color.gd_seed to R.color.gd_on_seed
            Look.REC -> R.color.gd_rec to R.color.gd_on_rec
            Look.SELECTED -> R.color.gd_text to R.color.gd_bg
            Look.OUTLINE -> null to R.color.gd_text
        }
        val shape = GradientDrawable().apply {
            cornerRadius = dp(20f).toFloat()
            if (fill != null) {
                setColor(context.getColor(fill))
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2f), context.getColor(R.color.gd_outline))
            }
        }
        background = RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.gd_press)), shape, null)
        labelView.setTextColor(context.getColor(fg))
        iconView.imageTintList = ColorStateList.valueOf(context.getColor(fg))
    }

    private fun dp(value: Float) = (value * density).toInt()

    companion object {
        /** 글자만 있는 버튼. */
        const val NO_ICON = 0
    }
}
