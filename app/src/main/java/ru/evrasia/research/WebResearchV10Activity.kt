package ru.evrasia.research

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class WebResearchV10Activity : AppCompatActivity() {
    internal fun researchWebView(): WebView? = if (::web.isInitialized) web else null
    internal fun researchArchive(): ResearchArchive = archive
    internal fun researchUserAgent(): String = if (::userAgent.isInitialized) userAgent else ""
    internal fun captureResearchSnapshot() { if (::captureController.isInitialized) captureController.capturePageSnapshot() }

    private lateinit var web: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var address: EditText
    private lateinit var badge: TextView
    private lateinit var bookmarkSpinner: Spinner
    private lateinit var navigationController: WebNavigationController
    private lateinit var bookmarkController: WebBookmarkController
    private lateinit var statsController: WebCookieStatsController
    private lateinit var webViewController: WebResearchWebViewController
    private lateinit var exportController: WebResearchExportController
    private val archive = ResearchArchive()
    private lateinit var captureController: WebCaptureController
    private lateinit var userAgent: String
    private val badgeUpdatePending = AtomicBoolean(false)
    private val uiHandler = Handler(Looper.getMainLooper())

    private val bg = Color.rgb(6, 14, 12)
    private val panel = Color.rgb(14, 29, 24)
    private val panel2 = Color.rgb(20, 39, 33)
    private val accent = Color.rgb(151, 231, 92)
    private val textColor = Color.rgb(238, 245, 241)
    private val muted = Color.rgb(157, 177, 166)

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "web research"
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        hero.addView(TextView(this).apply {
            text = "WEB RESEARCH"
            setTextColor(textColor)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .08f
        })
        hero.addView(TextView(this).apply {
            text = "capture · inspect · understand"
            setTextColor(accent)
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        root.addView(hero)

        val navCard = card().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        address = EditText(this).apply {
            hint = "https://example.com/path"
            setHintTextColor(muted)
            setTextColor(textColor)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 14f
            background = rounded(panel2, 14f, Color.rgb(54, 76, 66))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setText("https://evrasia.rest/")
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { navigationController.navigate(text.toString()); true } else false
            }
        }
        val go = actionButton("→") { navigationController.navigate(address.text.toString()) }
        nav.addView(address, LinearLayout.LayoutParams(0, dp(46), 1f))
        nav.addView(go, LinearLayout.LayoutParams(dp(52), dp(46)).apply { marginStart = dp(8) })
        navCard.addView(nav)
        root.addView(navCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(8)) })

        val bookmarkCard = card().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
        val bookmarkTitle = TextView(this).apply { text = "ЗАКЛАДКИ"; setTextColor(muted); textSize = 11f; typeface = Typeface.DEFAULT_BOLD }
        bookmarkCard.addView(bookmarkTitle)
        val bookmarkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, 0) }
        bookmarkSpinner = Spinner(this)
        bookmarkController = WebBookmarkController(
            activity = this,
            normalizeUrl = { raw -> navigationController.normalizeUrl(raw) },
            onOpen = { url -> navigationController.navigate(url) }
        )
        bookmarkController.bind(bookmarkSpinner)
        val openBookmark = compactButton("Открыть") { bookmarkController.openSelected() }
        val saveBookmark = compactButton("★") { bookmarkController.save(address.text.toString()) }
        val deleteBookmark = compactButton("−") { bookmarkController.deleteSelected() }
        bookmarkRow.addView(bookmarkSpinner, LinearLayout.LayoutParams(0, dp(44), 1f))
        bookmarkRow.addView(openBookmark)
        bookmarkRow.addView(saveBookmark)
        bookmarkRow.addView(deleteBookmark)
        bookmarkCard.addView(bookmarkRow)
        root.addView(bookmarkCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(8)) })

        val statsHeader = Button(this).apply {
            text = "Куки  ▾"
            setTextColor(textColor)
            textSize = 14f
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            background = rounded(panel, 16f, Color.rgb(37, 62, 51))
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { if (::statsController.isInitialized) statsController.toggle() }
        }
        root.addView(statsHeader, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(12), 0, dp(12), dp(6)) })

        val statsPanel = card().apply {
            visibility = View.GONE
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val statsTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statsTop.addView(TextView(this).apply {
            text = "Сессионные куки"
            setTextColor(accent)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        statsTop.addView(compactButton("Копировать") { if (::statsController.isInitialized) statsController.copy() })
        statsPanel.addView(statsTop)
        val stats = TextView(this).apply {
            setTextColor(textColor)
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
        }
        statsPanel.addView(stats)
        root.addView(statsPanel, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(8)) })
        statsController = WebCookieStatsController(
            activity = this,
            header = statsHeader,
            panel = statsPanel,
            textView = stats,
            pageProvider = { if (::web.isInitialized) web.url ?: address.text?.toString().orEmpty() else address.text?.toString().orEmpty() }
        )

        web = WebView(this).apply { setBackgroundColor(Color.WHITE) }
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(accent)
            setProgressBackgroundColorSchemeColor(panel2)
            setOnChildScrollUpCallback { _, _ -> web.canScrollVertically(-1) }
            setOnRefreshListener {
                web.reload()
                uiHandler.postDelayed({ if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false }, 15000)
            }
            addView(web, android.view.ViewGroup.LayoutParams(-1, -1))
        }
        root.addView(swipeRefresh, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(panel) }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(6)) }
        badge = TextView(this).apply { text = "0 событий"; setTextColor(muted); textSize = 11f; setPadding(dp(4), 0, dp(12), 0) }
        controls.addView(badge)
        controls.addView(compactButton("Очистить") {
            archive.clear(); if (::captureController.isInitialized) captureController.clearPending(); updateBadge(); if (::statsController.isInitialized) statsController.update()
        })
        controls.addView(compactButton("Экспорт ZIP") { exportZip() })
        bottomScroll.addView(controls)
        root.addView(bottomScroll, LinearLayout.LayoutParams(-1, dp(52)))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.setSupportMultipleWindows(true)
        web.settings.javaScriptCanOpenWindowsAutomatically = true
        userAgent = web.settings.userAgentString + " WebResearch/10"
        web.settings.userAgentString = userAgent
        navigationController = WebNavigationController(this, web, address) { addRecord(it) }
        captureController = WebCaptureController(
            activity = this,
            web = web,
            archive = archive,
            userAgent = userAgent,
            record = { addRecord(it) },
            onChanged = { scheduleBadgeUpdate() },
            onSnapshot = { runOnUiThread { if (::statsController.isInitialized) statsController.update() } }
        )
        WebView.setWebContentsDebuggingEnabled(true)
        web.addJavascriptInterface(captureController.bridge, "EvrasiaResearch")
        exportController = WebResearchExportController(
            activity = this,
            archive = archive,
            web = web,
            captureSnapshot = { capturePageSnapshot() }
        )

        webViewController = WebResearchWebViewController(
            activity = this,
            web = web,
            swipeRefresh = swipeRefresh,
            address = address,
            captureController = captureController,
            navigationController = navigationController,
            handler = uiHandler,
            record = { addRecord(it) },
            updateStats = { if (::statsController.isInitialized) statsController.update() }
        )
        webViewController.install()
        navigationController.navigate("https://evrasia.rest/")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = dp(radius.toInt()).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(panel, 18f, Color.rgb(34, 57, 48)) }
    private fun actionButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label; setTextColor(Color.rgb(8, 18, 14)); textSize = 22f; isAllCaps = false; background = rounded(accent, 14f); setOnClickListener { click() }
    }
    private fun compactButton(label: String, click: () -> Unit) = Button(this).apply {
        text = label; setTextColor(textColor); textSize = 11f; isAllCaps = false; minWidth = 0; minimumWidth = 0; setPadding(dp(10), 0, dp(10), 0); background = rounded(panel2, 12f, Color.rgb(50, 76, 65)); setOnClickListener { click() }
    }

    fun requestResourceCopy(url: String, headersJson: JSONObject?): Boolean =
        if (::captureController.isInitialized) captureController.requestResourceCopy(url, headersJson) else false

    fun ensureInstrumentation() {
        if (::captureController.isInitialized) captureController.ensureInstrumentation()
    }

    private fun capturePageSnapshot() {
        if (::captureController.isInitialized) captureController.capturePageSnapshot()
    }

    private fun addRecord(record: JSONObject) { archive.addRecord(record); scheduleBadgeUpdate() }

    private fun scheduleBadgeUpdate() {
        if (!badgeUpdatePending.compareAndSet(false, true)) return
        uiHandler.postDelayed({
            badgeUpdatePending.set(false)
            if (::badge.isInitialized && !isFinishing) updateBadge()
        }, 250)
    }

    private fun updateBadge() { badge.text = "${archive.records.length()} событий · ${archive.scripts.size} JS · ${archive.resources.size} ресурсов" }

    private fun exportZip() {
        if (::exportController.isInitialized) exportController.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::exportController.isInitialized) exportController.handleResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        if (::statsController.isInitialized) statsController.onResume()
    }

    override fun onPause() {
        if (::statsController.isInitialized) statsController.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::statsController.isInitialized) statsController.destroy()
        if (::captureController.isInitialized) captureController.shutdown()
        super.onDestroy()
    }

    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
}
