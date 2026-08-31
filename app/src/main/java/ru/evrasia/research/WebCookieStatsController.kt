package ru.evrasia.research

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

internal class WebCookieStatsController(
    private val activity: AppCompatActivity,
    private val header: Button,
    private val panel: LinearLayout,
    private val textView: TextView,
    private val pageProvider: () -> String
) {
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (panel.visibility == View.VISIBLE) {
                update()
                handler.postDelayed(this, 500)
            }
        }
    }

    fun toggle() {
        val show = panel.visibility != View.VISIBLE
        panel.visibility = if (show) View.VISIBLE else View.GONE
        header.text = if (show) "Куки  ▴" else "Куки  ▾"
        handler.removeCallbacks(ticker)
        if (show) { update(); handler.post(ticker) }
    }

    fun copy() {
        val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("web research statistics", textView.text ?: ""))
        Toast.makeText(activity, "Статистика скопирована", Toast.LENGTH_SHORT).show()
    }

    fun update() {
        val page = pageProvider()
        val raw = CookieManager.getInstance().getCookie(page).orEmpty()
        val cookies = raw.split(';').map { it.trim() }.filter { it.isNotBlank() }
        textView.text = buildString {
            append("Страница: ").append(page.ifBlank { "—" })
            append("\nКуки текущей сессии: ").append(cookies.size)
            if (cookies.isNotEmpty()) {
                append("\n\n")
                cookies.forEachIndexed { i, c -> append(i + 1).append(". ").append(c).append('\n') }
            }
        }.trimEnd()
    }

    fun onResume() {
        if (panel.visibility == View.VISIBLE) { handler.removeCallbacks(ticker); handler.post(ticker) }
    }

    fun onPause() { handler.removeCallbacks(ticker) }
    fun destroy() { handler.removeCallbacks(ticker) }
}
