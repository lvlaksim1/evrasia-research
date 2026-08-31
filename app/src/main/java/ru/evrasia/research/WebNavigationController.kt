package ru.evrasia.research

import android.widget.EditText
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

internal class WebNavigationController(
    private val activity: AppCompatActivity,
    private val web: WebView,
    private val address: EditText,
    private val record: (JSONObject) -> Unit
) {
    fun normalizeUrl(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return "https://evrasia.rest/"
        return if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
    }

    fun navigate(raw: String) {
        val url = normalizeUrl(raw)
        address.setText(url)
        web.loadUrl(url)
    }

    fun openInActiveWindow(url: String) {
        activity.runOnUiThread { navigate(url) }
        record(JSONObject().put("source", "new-window").put("time", System.currentTimeMillis()).put("url", url).put("method", "GET"))
    }
}
