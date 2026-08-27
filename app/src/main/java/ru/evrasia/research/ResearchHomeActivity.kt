package ru.evrasia.research

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class ResearchHomeActivity : AppCompatActivity() {
    private val bg = Color.rgb(6, 14, 12)
    private val panel = Color.rgb(14, 29, 24)
    private val accent = Color.rgb(151, 231, 92)
    private val text = Color.rgb(238, 245, 241)
    private val muted = Color.rgb(157, 177, 166)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(bg)
        }

        root.addView(TextView(this).apply {
            this.text = "WEB RESEARCH"
            setTextColor(text)
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .08f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            this.text = "capture · inspect · understand"
            setTextColor(accent)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(32))
        })

        root.addView(screenButton("Браузер", "Открыть исследуемый сайт") {
            startActivity(Intent(this, WebResearchV10Activity::class.java))
        })
        root.addView(screenButton("Network debugger", "Сетевые события · запросы · ответы · cookies") {
            startActivity(Intent(this, NetworkDebuggerActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(92)).apply { topMargin = dp(14) })

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left + dp(22), bars.top + dp(24), bars.right + dp(22), bars.bottom + dp(24))
            insets
        }
    }

    private fun screenButton(title: String, subtitle: String, click: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(panel, 18f, Color.rgb(48, 76, 64))
            isClickable = true
            isFocusable = true
            addView(TextView(this@ResearchHomeActivity).apply {
                text = title
                setTextColor(this@ResearchHomeActivity.text)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@ResearchHomeActivity).apply {
                text = subtitle
                setTextColor(muted)
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
            })
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(92))
        }
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
