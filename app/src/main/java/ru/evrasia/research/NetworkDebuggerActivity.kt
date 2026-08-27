package ru.evrasia.research

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NetworkDebuggerActivity : AppCompatActivity() {
    private val bg = Color.rgb(6, 14, 12)
    private val panel = Color.rgb(14, 29, 24)
    private val panel2 = Color.rgb(20, 39, 33)
    private val accent = Color.rgb(151, 231, 92)
    private val text = Color.rgb(238, 245, 241)
    private val muted = Color.rgb(157, 177, 166)
    private val bad = Color.rgb(255, 118, 118)

    private lateinit var list: ListView
    private lateinit var adapter: EventAdapter
    private lateinit var counter: TextView
    private lateinit var recordButton: Button
    private val items = mutableListOf<JSONObject>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastRevision = -1L

    private val refresh = object : Runnable {
        override fun run() {
            val rev = NetworkDebugStore.revision()
            if (rev != lastRevision) {
                lastRevision = rev
                reload()
            }
            handler.postDelayed(this, 350)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Network debugger"
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }
        header.addView(TextView(this).apply {
            setTextColor(text)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            text = "NETWORK DEBUGGER"
            letterSpacing = .06f
        })
        header.addView(TextView(this).apply {
            setTextColor(accent)
            textSize = 12f
            text = "requests · responses · cookies"
            setPadding(0, dp(2), 0, 0)
        })
        root.addView(header)

        val barScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(panel)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        recordButton = compactButton("") { toggleRecording() }
        updateRecordButton()
        bar.addView(recordButton)
        bar.addView(compactButton("Очистить") {
            NetworkDebugStore.clear()
            reload()
        })
        bar.addView(compactButton("Куки") { showAllCookies() })
        bar.addView(compactButton("Экспорт ZIP") { exportZip() })
        barScroll.addView(bar)
        root.addView(barScroll, LinearLayout.LayoutParams(-1, dp(54)))

        counter = TextView(this).apply {
            setTextColor(muted)
            textSize = 11f
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        root.addView(counter)

        list = ListView(this).apply {
            divider = null
            dividerHeight = dp(1)
            setBackgroundColor(bg)
        }
        adapter = EventAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> showDetails(items[position]) }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        reload()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun toggleRecording() {
        NetworkDebugStore.recording = !NetworkDebugStore.recording
        updateRecordButton()
        Toast.makeText(this, if (NetworkDebugStore.recording) "Запись включена" else "Запись остановлена", Toast.LENGTH_SHORT).show()
    }

    private fun updateRecordButton() {
        if (!::recordButton.isInitialized) return
        recordButton.text = if (NetworkDebugStore.recording) "● Запись ВКЛ" else "○ Запись ВЫКЛ"
        recordButton.setTextColor(if (NetworkDebugStore.recording) accent else muted)
    }

    private fun reload() {
        items.clear()
        items.addAll(NetworkDebugStore.snapshot().asReversed())
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        if (::counter.isInitialized) {
            val requests = items.count { it.optString("source") in setOf("fetch", "xhr", "webview", "resource-copy", "navigation", "new-window") }
            val errors = items.count { it.has("error") || it.optInt("status", 0) >= 400 }
            counter.text = "${items.size} событий · $requests запросов · $errors ошибок"
        }
    }

    private fun showDetails(event: JSONObject) {
        val url = event.optString("url", "")
        val cookies = if (url.startsWith("http://") || url.startsWith("https://")) CookieManager.getInstance().getCookie(url).orEmpty() else ""
        val body = buildString {
            section("ОБЩЕЕ")
            line("Источник", event.optString("source", "—"))
            line("Метод", event.optString("method", "—"))
            line("URL", url.ifBlank { "—" })
            line("Статус", if (event.has("status")) event.optInt("status").toString() + " " + event.optString("statusText", "") else "—")
            line("Время", formatTime(event.optLong("time", 0)))
            if (event.has("duration")) line("Длительность", "${event.optLong("duration")} ms")
            if (event.has("mimeType")) line("MIME", event.optString("mimeType"))

            section("REQUEST HEADERS")
            append(prettyObject(event.optJSONObject("requestHeaders") ?: event.optJSONObject("headers")))
            section("REQUEST BODY")
            append(event.optString("requestBody", "—").ifBlank { "—" })

            section("RESPONSE HEADERS")
            val responseHeaders = event.optJSONObject("responseHeaders")
            if (responseHeaders != null) append(prettyObject(responseHeaders)) else append(event.optString("responseHeadersRaw", "—").ifBlank { "—" })
            section("RESPONSE BODY")
            append(event.optString("responseBody", event.optString("data", "—")).ifBlank { "—" })

            section("КУКИ ДЛЯ URL")
            append(cookies.ifBlank { "—" })

            section("RAW EVENT")
            append(event.toString(2))
        }

        val scroll = ScrollView(this)
        val content = TextView(this).apply {
            setTextColor(text)
            setBackgroundColor(bg)
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(14), dp(12), dp(14), dp(16))
            this.text = body
        }
        scroll.addView(content)
        AlertDialog.Builder(this)
            .setTitle("Детали запроса")
            .setView(scroll)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun StringBuilder.section(title: String) {
        if (isNotEmpty()) append("\n\n")
        append("── ").append(title).append(" ──\n")
    }

    private fun StringBuilder.line(name: String, value: String) {
        append(name).append(": ").append(value).append('\n')
    }

    private fun prettyObject(obj: JSONObject?): String {
        if (obj == null || obj.length() == 0) return "—"
        return obj.toString(2)
    }

    private fun showAllCookies() {
        val urls = items.map { it.optString("url", "") }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
        val rows = linkedMapOf<String, String>()
        for (url in urls) {
            try {
                val host = java.net.URL(url).host
                val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
                if (cookie.isNotBlank()) rows[host] = cookie
            } catch (_: Exception) {}
        }
        val text = if (rows.isEmpty()) "Куки не обнаружены." else rows.entries.joinToString("\n\n") { "${it.key}\n${it.value}" }
        val view = TextView(this).apply {
            setTextColor(this@NetworkDebuggerActivity.text)
            setBackgroundColor(bg)
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(14), dp(12), dp(14), dp(16))
            this.text = text
        }
        val scroll = ScrollView(this).apply { addView(view) }
        AlertDialog.Builder(this).setTitle("Cookies").setView(scroll).setPositiveButton("Закрыть", null).show()
    }

    private fun exportZip() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "web-research-network-$stamp.zip")
        }
        startActivityForResult(intent, 701)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 701 || resultCode != RESULT_OK) return
        data?.data?.let { uri ->
            contentResolver.openOutputStream(uri)?.use { output ->
                ZipOutputStream(output).use { zip ->
                    val events = NetworkDebugStore.json()
                    addZip(zip, "network-events.json", JSONObject().put("recording", NetworkDebugStore.recording).put("exportedAt", System.currentTimeMillis()).put("events", events).toString(2))
                    addZip(zip, "cookies.json", buildCookiesJson().toString(2))
                }
            }
            Toast.makeText(this, "ZIP экспортирован", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildCookiesJson(): JSONArray {
        val out = JSONArray()
        val seen = mutableSetOf<String>()
        NetworkDebugStore.snapshot().forEach { event ->
            val url = event.optString("url", "")
            if (!(url.startsWith("http://") || url.startsWith("https://"))) return@forEach
            try {
                val host = java.net.URL(url).host
                if (seen.add(host)) {
                    out.put(JSONObject().put("host", host).put("url", url).put("cookie", CookieManager.getInstance().getCookie(url).orEmpty()))
                }
            } catch (_: Exception) {}
        }
        return out
    }

    private fun addZip(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "—"
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(ms))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun compactButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(this@NetworkDebuggerActivity.text)
        textSize = 11f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(11), 0, dp(11), 0)
        background = rounded(panel2, 12f, Color.rgb(50, 76, 65))
        setOnClickListener { click() }
    }

    inner class EventAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): JSONObject = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val event = getItem(position)
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@NetworkDebuggerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(panel, 0f, Color.rgb(26, 48, 39))
                addView(TextView(this@NetworkDebuggerActivity).apply { tag = "top"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD })
                addView(TextView(this@NetworkDebuggerActivity).apply { tag = "url"; textSize = 11f; maxLines = 2; setPadding(0, dp(3), 0, 0) })
            }
            val top = row.findViewWithTag<TextView>("top")
            val url = row.findViewWithTag<TextView>("url")
            val status = event.optInt("status", 0)
            val method = event.optString("method", event.optString("source", "EVENT")).uppercase(Locale.US)
            val source = event.optString("source", "")
            top.text = buildString {
                append(method)
                if (status > 0) append("   ").append(status)
                if (event.has("duration")) append("   ").append(event.optLong("duration")).append(" ms")
                append("   · ").append(source)
            }
            top.setTextColor(if (status >= 400 || event.has("error")) bad else if (status in 200..399) accent else text)
            url.text = event.optString("url", event.optString("message", "—"))
            url.setTextColor(muted)
            return row
        }
    }
}
