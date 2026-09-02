package ru.evrasia.research

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
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
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NetworkResearchActivity : AppCompatActivity() {
    private lateinit var palette: WebUiTheme.Palette
    private lateinit var list: ListView
    private lateinit var adapter: RequestAdapter
    private lateinit var search: EditText
    private lateinit var counter: TextView
    private val dataSource = NetworkDebuggerDataSource()
    private val allItems = mutableListOf<JSONObject>()
    private val items = mutableListOf<JSONObject>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastRevision = -1L
    private var selectedCategory = "ALL"
    private var pendingExport = false
    private val filters = linkedMapOf(
        "ALL" to "Все",
        "FETCH" to "Fetch/XHR",
        "JS" to "JS",
        "DOCUMENT" to "Document",
        "MEDIA" to "Media",
        "OTHER" to "Other"
    )
    private val filterButtons = linkedMapOf<String, Button>()

    private val refresh = object : Runnable {
        override fun run() {
            refreshData()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WebUiTheme.applySaved(this)
        super.onCreate(savedInstanceState)
        palette = WebUiTheme.palette(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureSystemBars()

        val root = LinearLayout(this).apply {
            tag = "network-root"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }

        val header = LinearLayout(this).apply {
            tag = "network-header"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(6))
            setBackgroundColor(palette.background)
        }
        header.addView(iconButton(TechIconDrawable.Kind.BACK) { finish() }, LinearLayout.LayoutParams(dp(42), dp(44)))
        header.addView(TextView(this).apply {
            text = "Сетевые запросы"
            setTextColor(palette.text)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(textButton("⌕") { toggleSearch() }, LinearLayout.LayoutParams(dp(42), dp(44)))
        header.addView(textButton("⋮") { showNetworkMenu() }, LinearLayout.LayoutParams(dp(42), dp(44)).apply { marginStart = dp(3) })
        root.addView(header, LinearLayout.LayoutParams(-1, dp(56)))

        search = EditText(this).apply {
            tag = "network-search"
            visibility = View.GONE
            hint = "Поиск по URL, headers, body..."
            setHintTextColor(palette.secondary)
            setTextColor(palette.text)
            textSize = 13f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = rounded(palette.address, 16f, palette.divider)
            setPadding(dp(13), 0, dp(13), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilters()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(42)).apply { setMargins(dp(10), 0, dp(10), dp(6)) })

        val filterScroll = HorizontalScrollView(this).apply {
            tag = "network-filter-scroll"
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(palette.background)
        }
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(6))
        }
        filters.forEach { (key, label) ->
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 11.5f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    selectedCategory = key
                    renderFilterButtons()
                    applyFilters()
                }
            }
            filterButtons[key] = button
            filterRow.addView(button, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(5) })
        }
        renderFilterButtons()
        filterScroll.addView(filterRow)
        root.addView(filterScroll, LinearLayout.LayoutParams(-1, dp(44)))

        counter = TextView(this).apply {
            tag = "network-counter"
            setTextColor(palette.secondary)
            textSize = 11f
            setPadding(dp(12), dp(2), dp(12), dp(5))
        }
        root.addView(counter, LinearLayout.LayoutParams(-1, dp(28)))

        list = ListView(this).apply {
            tag = "network-list"
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(4)
            setBackgroundColor(palette.background)
            setPadding(dp(7), 0, dp(7), dp(8))
            clipToPadding = false
        }
        adapter = RequestAdapter()
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
        refreshData(force = true)
    }

    private fun configureSystemBars() {
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.dark
            isAppearanceLightNavigationBars = !palette.dark
        }
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

    private fun refreshData(force: Boolean = false) {
        val result = dataSource.refresh(force, lastRevision)
        if (!result.changed) return
        lastRevision = result.revision
        allItems.clear()
        allItems.addAll(result.events)
        applyFilters()
    }

    private fun applyFilters() {
        if (!::search.isInitialized || !::adapter.isInitialized) return
        val query = search.text?.toString().orEmpty().trim()
        items.clear()
        items.addAll(allItems.filter { event ->
            val categoryOk = selectedCategory == "ALL" || categoryOf(event) == selectedCategory
            val queryOk = query.isBlank() || event.toString().contains(query, true)
            categoryOk && queryOk
        })
        adapter.notifyDataSetChanged()
        counter.text = "${items.size} из ${allItems.size} · ${allItems.count { NetworkEventClassifier.isRequestEvent(it) }} запросов"
    }

    private fun categoryOf(event: JSONObject): String {
        val sources = NetworkEventClassifier.eventSources(event)
        if (sources.any { it in setOf("fetch", "fetch-meta", "xhr", "xhr-meta") }) return "FETCH"
        if (NetworkEventClassifier.isJsEvent(event)) return "JS"
        val kind = NetworkEventClassifier.responseKind(event)
        val initiator = event.optString("initiatorType", "").lowercase(Locale.US)
        if (kind == "HTML" || sources.any { it in setOf("navigation", "navigation-timing") } || initiator == "document") return "DOCUMENT"
        val mime = event.optString("mimeType", "").lowercase(Locale.US)
        if (kind in setOf("IMG", "PDF") || mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/") || initiator in setOf("img", "image", "media", "video", "audio")) return "MEDIA"
        return "OTHER"
    }

    private fun renderFilterButtons() {
        filterButtons.forEach { (key, button) ->
            val selected = key == selectedCategory
            button.setTextColor(if (selected) Color.WHITE else palette.text)
            button.background = rounded(if (selected) palette.accent else palette.address, 16f, if (selected) palette.accent else palette.divider)
        }
    }

    private fun toggleSearch() {
        search.visibility = if (search.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (search.visibility == View.VISIBLE) {
            search.requestFocus()
        } else if (search.text.isNotEmpty()) {
            search.setText("")
        }
    }

    private fun showNetworkMenu() {
        val options = arrayOf(
            if (NetworkDebugStore.recording) "Остановить запись" else "Начать запись",
            "Очистить сетевой журнал",
            "Экспорт ZIP",
            "Расширенный журнал"
        )
        AlertDialog.Builder(this)
            .setTitle("Network / Research")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> NetworkDebugStore.recording = !NetworkDebugStore.recording
                    1 -> {
                        NetworkDebugStore.clear()
                        refreshData(force = true)
                    }
                    2 -> beginExport()
                    3 -> startActivity(Intent(this, NetworkDebuggerActivity::class.java))
                }
            }
            .show()
    }

    private fun showDetails(event: JSONObject) {
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.card, 22f, palette.divider)
            setPadding(dp(10), dp(10), dp(10), dp(12))
        }
        root.addView(View(this).apply { background = rounded(palette.divider, 2f) }, LinearLayout.LayoutParams(dp(38), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })

        val method = NetworkEventClassifier.methodOf(event)
        val url = NetworkEventClassifier.eventLocation(event)
        val status = event.optInt("status", 0)
        val duration = if (event.has("duration")) formatDuration(event.optDouble("duration", 0.0)) else ""
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), dp(8)) }
        header.addView(TextView(this).apply {
            text = method
            setTextColor(palette.accent)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        })
        header.addView(TextView(this).apply {
            text = url.ifBlank { "—" }
            setTextColor(palette.text)
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, dp(4))
        })
        header.addView(TextView(this).apply {
            text = buildString {
                append(if (status > 0) "$status ${statusText(status)}" else "pending")
                if (duration.isNotBlank()) append(" · ").append(duration)
            }
            setTextColor(statusColor(status, event.has("error")))
            textSize = 12f
        })
        root.addView(header)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(8), dp(2), dp(8)) }
        val tabNames = listOf("Общее", "Request", "Response", "Cookies")
        val tabButtons = mutableListOf<Button>()
        var selectedTab = 0

        fun renderContent(index: Int) {
            selectedTab = index
            content.removeAllViews()
            val text = when (index) {
                0 -> generalText(event)
                1 -> requestText(event)
                2 -> responseText(event)
                else -> CookieManager.getInstance().getCookie(url).orEmpty().ifBlank { "—" }
            }
            content.addView(TextView(this).apply {
                this.text = text
                setTextColor(palette.text)
                textSize = 11.5f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(palette.address, 12f, palette.divider)
            }, LinearLayout.LayoutParams(-1, -2))
            tabButtons.forEachIndexed { buttonIndex, button ->
                val selected = buttonIndex == selectedTab
                button.setTextColor(if (selected) palette.accent else palette.secondary)
                button.background = if (selected) rounded(palette.address, 12f, palette.divider) else ColorDrawable(Color.TRANSPARENT)
            }
        }

        tabNames.forEachIndexed { index, label ->
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(6), 0, dp(6), 0)
                setOnClickListener { renderContent(index) }
            }
            tabButtons.add(button)
            tabs.addView(button, LinearLayout.LayoutParams(0, dp(38), 1f))
        }
        root.addView(tabs)

        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        actions.addView(primaryButton("POSTMAN JSON") {
            val cookies = if (url.startsWith("http")) CookieManager.getInstance().getCookie(url).orEmpty() else ""
            copy("POSTMAN JSON", PostmanRequestExporter.build(event, method, cookies))
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        actions.addView(secondaryButton("Копировать URL") { copy("URL", url) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(7) })
        root.addView(actions)

        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.86).toInt())
                setGravity(Gravity.BOTTOM)
            }
        }
        dialog.show()
        renderContent(0)
    }

    private fun generalText(event: JSONObject): String = buildString {
        val url = NetworkEventClassifier.eventLocation(event)
        append("URL: ").append(url.ifBlank { "—" }).append('\n')
        append("Method: ").append(NetworkEventClassifier.methodOf(event)).append('\n')
        append("Status: ").append(event.optInt("status", 0).takeIf { it > 0 } ?: "pending").append('\n')
        if (event.has("duration")) append("Duration: ").append(formatDuration(event.optDouble("duration", 0.0))).append('\n')
        val size = responseSize(event)
        if (size >= 0) append("Size: ").append(formatBytes(size)).append('\n')
        append("Type: ").append(NetworkEventClassifier.responseKind(event)).append('\n')
        append("Source: ").append(NetworkEventClassifier.eventSources(event).joinToString(" + ").ifBlank { "—" }).append('\n')
        event.optJSONObject("timing")?.let { append("\nTiming\n").append(it.toString(2)) }
    }.trimEnd()

    private fun requestText(event: JSONObject): String = buildString {
        append(NetworkEventClassifier.methodOf(event)).append(' ').append(NetworkEventClassifier.eventLocation(event)).append('\n')
        append("\nHEADERS\n").append(headersText(event.optJSONObject("requestHeaders") ?: event.optJSONObject("headers")))
        val body = event.optString("requestBody", "")
        append("\n\nBODY\n").append(body.ifBlank { "—" })
    }

    private fun responseText(event: JSONObject): String = buildString {
        val status = event.optInt("status", 0)
        append(if (status > 0) "$status ${statusText(status)}" else "pending")
        append("\n\nHEADERS\n").append(headersText(event.optJSONObject("responseHeaders")))
        append("\n\nBODY\n").append(NetworkEventClassifier.responseBodyText(event).ifBlank { "—" })
    }

    private fun headersText(headers: JSONObject?): String {
        if (headers == null || headers.length() == 0) return "—"
        val keys = headers.keys().asSequence().toList().sorted()
        return keys.joinToString("\n") { key -> "$key: ${headers.optString(key, "")}" }
    }

    private fun beginExport() {
        NetworkRequestActions.prepareFullExport(this)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        pendingExport = true
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "web-research-$stamp.zip")
        }, 801)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 801 || resultCode != RESULT_OK || !pendingExport) return
        pendingExport = false
        data?.data?.let { uri ->
            contentResolver.openOutputStream(uri)?.use { output -> NetworkRequestActions.writeFullExport(this, output) }
        }
        Toast.makeText(this, "ZIP экспортирован", Toast.LENGTH_SHORT).show()
    }

    private fun copy(label: String, value: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun pathOf(url: String): String = try {
        val parsed = Uri.parse(url)
        val path = parsed.encodedPath.orEmpty().ifBlank { "/" }
        if (parsed.encodedQuery.isNullOrBlank()) path else "$path?${parsed.encodedQuery}"
    } catch (_: Exception) {
        url
    }

    private fun hostOf(url: String): String = try { URL(url).host } catch (_: Exception) { "" }

    private fun responseSize(event: JSONObject): Long = when {
        event.has("responseSize") -> event.optLong("responseSize", -1)
        event.has("decodedBodySize") -> event.optLong("decodedBodySize", -1)
        event.has("transferSize") -> event.optLong("transferSize", -1)
        else -> -1
    }

    private fun statusColor(status: Int, error: Boolean): Int = when {
        error || status >= 500 -> palette.red
        status >= 400 -> palette.orange
        status >= 300 -> palette.blue
        status >= 200 -> palette.green
        else -> palette.pending
    }

    private fun statusText(status: Int): String = when (status) {
        in 200..299 -> "OK"
        in 300..399 -> "Redirect"
        in 400..499 -> "Client error"
        in 500..599 -> "Server error"
        else -> ""
    }

    private fun formatDuration(ms: Double): String = if (ms < 1000) "${ms.toInt()} ms" else String.format(Locale.US, "%.2f s", ms / 1000.0)
    private fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }

    private fun iconButton(kind: TechIconDrawable.Kind, click: () -> Unit) = Button(this).apply {
        text = ""
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(9), dp(9), dp(9), dp(9))
        background = ColorDrawable(Color.TRANSPARENT)
        foreground = TechIconDrawable(kind, palette.accent)
        setOnClickListener { click() }
    }

    private fun textButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        setTextColor(palette.text)
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        background = ColorDrawable(Color.TRANSPARENT)
        setOnClickListener { click() }
    }

    private fun primaryButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = rounded(palette.accent, 14f)
        setOnClickListener { click() }
    }

    private fun secondaryButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setTextColor(palette.text)
        background = rounded(palette.address, 14f, palette.divider)
        setOnClickListener { click() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    inner class RequestAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): JSONObject = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val event = getItem(position)
            val row = (convertView as? LinearLayout)?.takeIf { it.tag == "network-row" } ?: LinearLayout(this@NetworkResearchActivity).apply {
                tag = "network-row"
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = rounded(palette.card, 14f, palette.divider)
                addView(TextView(this@NetworkResearchActivity).apply {
                    tag = "primary"
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                })
                addView(TextView(this@NetworkResearchActivity).apply {
                    tag = "secondary"
                    textSize = 10.5f
                    setTextColor(palette.secondary)
                    maxLines = 1
                    setPadding(0, dp(4), 0, 0)
                })
            }
            val primary = row.findViewWithTag<TextView>("primary")
            val secondary = row.findViewWithTag<TextView>("secondary")
            val method = NetworkEventClassifier.methodOf(event)
            val status = event.optInt("status", 0)
            val url = NetworkEventClassifier.eventLocation(event)
            val path = pathOf(url)
            primary.text = buildString {
                append(method).append("   ")
                append(if (status > 0) status else "…").append("   ")
                append(path.ifBlank { url.ifBlank { event.optString("source", "event") } })
            }
            primary.setTextColor(statusColor(status, event.has("error")))
            val host = hostOf(url).ifBlank { event.optString("source", "") }
            secondary.text = buildString {
                append(host.ifBlank { "—" })
                if (event.has("duration")) append(" · ").append(formatDuration(event.optDouble("duration", 0.0)))
                val size = responseSize(event)
                if (size >= 0) append(" · ").append(formatBytes(size))
            }
            return row
        }
    }
}
