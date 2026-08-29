package ru.evrasia.research

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

@Suppress("UNUSED_PARAMETER")
fun NetworkDebuggerActivity.rounded(fill: Int, radius: Float, ignoredShadowedLine: String): GradientDrawable {
    val density = resources.displayMetrics.density
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = (radius * density)
        setStroke((1f * density).toInt().coerceAtLeast(1), Color.rgb(50, 76, 65))
    }
}
