package ru.evrasia.research

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class WebResearchV10Activity : AppCompatActivity() {
    internal fun researchWebView(): WebView? = if (::web.isInitialized) web else null
    internal fun researchArchive(): ResearchArchive = archive
    internal fun researchUserAgent(): String = if (::userAgent.isInitialized) userAgent else ""
    internal fun captureResearchSnapshot() { if (::captureController.isInitialized) captureController.capturePageSnapshot() }

    private lateinit var palette: WebUiTheme.Palette
    private lateinit var web: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var address: EditText
    private lateinit var pageAction: Button
    private lateinit var networkBadge: TextView
    private lateinit var progress: ProgressBar
    private lateinit var navigationController: WebNavigationController
    private lateinit var bookmarkController: WebBookmarkController
    private lateinit var webViewController: WebResearchWebViewController
    private lateinit var exportController: WebResearchExportController
    private lateinit var captureController: WebCaptureController
    private val archive = ResearchArchive()
    private lateinit var userAgent: String
    private val badgeUpdatePending = AtomicBoolean(false)
    private val uiHandler = Handler(Looper.getMainLooper())
    private var loading = false
    private var editingAddress = false

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        WebUiTheme.applySaved(this)
        super.onCreate(savedInstanceState)
        title = "web research"
        palette = WebUiTheme.palette(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureSystemBars()

        val root = LinearLayout(this).apply {
            tag = "web-research-root"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }

        val toolbar = LinearLayout(this).apply {
            tag = "browser-toolbar"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(6))
            setBackgroundColor(palette.background)
        }

        val menu = iconButton(TechIconDrawable.Kind.MENU, false) { showBrowserMenu() }
        toolbar.addView(menu, LinearLayout.LayoutParams(dp(42), dp(46)))

        address = EditText(this).apply {
            tag = "browser-address"
            hint = "Адрес сайта"
            setHintTextColor(palette.secondary)
            setTextColor(palette.text)
            setSingleLine(true)
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_GO
            background = rounded(palette.address, 22f)
            setPadding(dp(14), 0, dp(14), 0)
            setText("https://evrasia.rest/")
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    navigateFromAddress()
                    true
                } else false
            }
            onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                editingAddress = hasFocus
                updatePageAction()
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (hasFocus()) editingAddress = true
                    updatePageAction()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        toolbar.addView(address, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(4) })

        pageAction = Button(this).apply {
            tag = "browser-page-action"
            text = "→"
            textSize = 21f
            setTextColor(palette.accent)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = rounded(palette.card, 16f, palette.divider)
            setOnClickListener { handlePageAction() }
        }
        toolbar.addView(pageAction, LinearLayout.LayoutParams(dp(42), dp(46)).apply { marginStart = dp(5) })

        val networkContainer = FrameLayout(this).apply { tag = "browser-network" }
        val network = iconButton(TechIconDrawable.Kind.NETWORK, true) {
            ensureInstrumentation()
            startActivity(Intent(this, NetworkResearchActivity::class.java))
        }
        networkContainer.addView(network, FrameLayout.LayoutParams(dp(42), dp(46)))
        networkBadge = TextView(this).apply {
            tag = "network-badge"
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(18)
            setPadding(dp(4), 0, dp(4), 0)
            background = rounded(palette.accent, 9f)
        }
        networkContainer.addView(networkBadge, FrameLayout.LayoutParams(-2, dp(18), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(1)
            marginEnd = dp(0)
        })
        toolbar.addView(networkContainer, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(4) })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(58)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = "browser-progress"
            max = 100
            progressTintList = ColorStateList.valueOf(palette.accent)
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            visibility = View.INVISIBLE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))

        web = WebView(this).apply {
            tag = "browser-webview"
            setBackgroundColor(Color.WHITE)
        }
        swipeRefresh = SwipeRefreshLayout(this).apply {
            tag = "browser-webview-container"
            setColorSchemeColors(palette.accent)
            setProgressBackgroundColorSchemeColor(palette.card)
            setOnChildScrollUpCallback { _, _ -> web.canScrollVertically(-1) }
            setOnRefreshListener {
                web.reload()
                uiHandler.postDelayed({ if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false }, 15000)
            }
            addView(web, ViewGroup.LayoutParams(-1, -1))
        }
        root.addView(swipeRefresh, LinearLayout.LayoutParams(-1, 0, 1f))
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
        bookmarkController = WebBookmarkController(
            activity = this,
            normalizeUrl = { raw -> navigationController.normalizeUrl(raw) },
            onOpen = { url -> navigationController.navigate(url) }
        )
        captureController = WebCaptureController(
            activity = this,
            web = web,
            archive = archive,
            userAgent = userAgent,
            record = { addRecord(it) },
            onChanged = { scheduleBadgeUpdate() },
            onSnapshot = { scheduleBadgeUpdate() }
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
            onLoadingChanged = { isLoading ->
                loading = isLoading
                if (!isLoading) editingAddress = false
                updatePageAction()
                progress.visibility = if (isLoading) View.VISIBLE else View.INVISIBLE
            },
            onProgressChanged = { value ->
                progress.progress = value
                if (value in 1..99) progress.visibility = View.VISIBLE
                if (value >= 100 && !loading) progress.visibility = View.INVISIBLE
            },
            onPageUrlChanged = { url ->
                if (!address.hasFocus()) address.setText(url)
            }
        )
        webViewController.install()
        navigationController.navigate("https://evrasia.rest/")
    }

    private fun configureSystemBars() {
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.dark
            isAppearanceLightNavigationBars = !palette.dark
        }
    }

    private fun handlePageAction() {
        when {
            loading -> web.stopLoading()
            editingAddress || address.hasFocus() -> navigateFromAddress()
            else -> web.reload()
        }
    }

    private fun navigateFromAddress() {
        navigationController.navigate(address.text.toString())
        editingAddress = false
        address.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        updatePageAction()
    }

    private fun updatePageAction() {
        if (!::pageAction.isInitialized) return
        pageAction.text = when {
            loading -> "✕"
            editingAddress || address.hasFocus() -> "→"
            else -> "↻"
        }
    }

    private fun showBrowserMenu() {
        showBottomSheet("Меню") { dialog ->
            addSection("СТРАНИЦА")
            addMenuRow("★", "Добавить в закладки", currentHost()) {
                bookmarkController.save(currentPage())
                dialog.dismiss()
            }
            addMenuRow("▤", "Закладки", "${bookmarkController.all().size} сохранено") {
                dialog.dismiss()
                showBookmarksSheet()
            }
            addSiteVersionRow()

            addSection("ДАННЫЕ САЙТА")
            val cookieCount = cookieCount()
            addMenuRow("◉", "Cookies", "${currentHost()} · $cookieCount cookies") {
                dialog.dismiss()
                showCookiesSheet()
            }
            addMenuRow("⌫", "Удалить cookies домена", if (cookieCount > 0) "$cookieCount cookies" else "Нет cookies") {
                dialog.dismiss()
                confirmClearCookies(cookieCount)
            }

            addSection("ИССЛЕДОВАНИЕ")
            addMenuRow("○", "Очистить текущую сессию", "События, JS, ресурсы и сетевой журнал") {
                archive.clear()
                NetworkDebugStore.clear()
                if (::captureController.isInitialized) captureController.clearPending()
                updateBadge()
                Toast.makeText(this@WebResearchV10Activity, "Текущая сессия очищена", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            addMenuRow("⇩", "Экспорт ZIP", "Полный архив исследования") {
                dialog.dismiss()
                showExportSheet()
            }

            addSection("ИНТЕРФЕЙС")
            addMenuRow("◐", "Тема", WebUiTheme.savedMode(this@WebResearchV10Activity).label) {
                dialog.dismiss()
                showThemePicker()
            }

            addSection("ПРИЛОЖЕНИЕ")
            addMenuRow("i", "О приложении", "web research") {
                dialog.dismiss()
                showAbout()
            }
        }
    }

    private fun LinearLayout.addSiteVersionRow() {
        val container = LinearLayout(this@WebResearchV10Activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(10))
        }
        container.addView(TextView(this@WebResearchV10Activity).apply {
            text = "Версия сайта"
            setTextColor(palette.text)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        })
        val selector = LinearLayout(this@WebResearchV10Activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, 0)
        }
        fun modeButton(label: String, desktop: Boolean): Button = Button(this@WebResearchV10Activity).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            fun render() {
                val selected = webViewController.isDesktopMode() == desktop
                setTextColor(if (selected) Color.WHITE else palette.text)
                background = rounded(if (selected) palette.accent else palette.address, 12f, if (selected) palette.accent else palette.divider)
            }
            render()
            setOnClickListener {
                webViewController.setDesktopMode(desktop)
                for (index in 0 until selector.childCount) {
                    (selector.getChildAt(index) as? Button)?.let { button ->
                        val selected = (button.text.toString() == "ПК") == webViewController.isDesktopMode()
                        button.setTextColor(if (selected) Color.WHITE else palette.text)
                        button.background = rounded(if (selected) palette.accent else palette.address, 12f, if (selected) palette.accent else palette.divider)
                    }
                }
            }
        }
        selector.addView(modeButton("Мобильная", false), LinearLayout.LayoutParams(0, dp(38), 1f))
        selector.addView(modeButton("ПК", true), LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(6) })
        container.addView(selector)
        addView(container, LinearLayout.LayoutParams(-1, -2))
        addDivider()
    }

    private fun showBookmarksSheet() {
        showBottomSheet("Закладки") { dialog ->
            addMenuRow("★", "Добавить текущую страницу", currentPage()) {
                bookmarkController.save(currentPage())
                dialog.dismiss()
                showBookmarksSheet()
            }
            val saved = bookmarkController.all()
            if (saved.isEmpty()) {
                addView(TextView(this@WebResearchV10Activity).apply {
                    text = "Закладок пока нет"
                    setTextColor(palette.secondary)
                    textSize = 14f
                    setPadding(dp(14), dp(20), dp(14), dp(24))
                })
            } else {
                saved.forEach { url ->
                    val row = LinearLayout(this@WebResearchV10Activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(7), dp(8), dp(7))
                        setOnClickListener {
                            bookmarkController.open(url)
                            dialog.dismiss()
                        }
                    }
                    val labels = LinearLayout(this@WebResearchV10Activity).apply { orientation = LinearLayout.VERTICAL }
                    labels.addView(TextView(this@WebResearchV10Activity).apply {
                        text = hostOf(url).ifBlank { url }
                        setTextColor(palette.text)
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    labels.addView(TextView(this@WebResearchV10Activity).apply {
                        text = url
                        setTextColor(palette.secondary)
                        textSize = 11f
                        maxLines = 1
                    })
                    row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(Button(this@WebResearchV10Activity).apply {
                        text = "×"
                        textSize = 20f
                        setTextColor(palette.secondary)
                        isAllCaps = false
                        minWidth = 0
                        minimumWidth = 0
                        background = ColorDrawable(Color.TRANSPARENT)
                        setOnClickListener {
                            bookmarkController.delete(url)
                            dialog.dismiss()
                            showBookmarksSheet()
                        }
                    }, LinearLayout.LayoutParams(dp(42), dp(42)))
                    addView(row)
                    addDivider()
                }
            }
        }
    }

    private fun showCookiesSheet() {
        val page = currentPage()
        val raw = CookieManager.getInstance().getCookie(page).orEmpty()
        val cookies = raw.split(';').map { it.trim() }.filter { it.isNotBlank() }
        showBottomSheet("Cookies") { dialog ->
            addView(TextView(this@WebResearchV10Activity).apply {
                text = "${currentHost()} · ${cookies.size} cookies"
                setTextColor(palette.secondary)
                textSize = 12f
                setPadding(dp(14), dp(2), dp(14), dp(10))
            })
            if (cookies.isEmpty()) {
                addView(TextView(this@WebResearchV10Activity).apply {
                    text = "Для текущего домена cookies не найдены"
                    setTextColor(palette.text)
                    textSize = 14f
                    setPadding(dp(14), dp(12), dp(14), dp(18))
                })
            } else {
                cookies.forEach { cookie ->
                    addView(TextView(this@WebResearchV10Activity).apply {
                        text = cookie
                        setTextColor(palette.text)
                        textSize = 12f
                        setTextIsSelectable(true)
                        setPadding(dp(14), dp(9), dp(14), dp(9))
                    })
                    addDivider()
                }
                addPrimaryButton("Копировать cookies") {
                    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    manager.setPrimaryClip(ClipData.newPlainText("cookies", raw))
                    Toast.makeText(this@WebResearchV10Activity, "Cookies скопированы", Toast.LENGTH_SHORT).show()
                }
                addDangerButton("Удалить cookies домена") {
                    dialog.dismiss()
                    confirmClearCookies(cookies.size)
                }
            }
        }
    }

    private fun confirmClearCookies(count: Int) {
        if (count <= 0) {
            webViewController.clearCurrentDomainCookies()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Удалить cookies?")
            .setMessage("Удалить $count cookies для ${currentHost()}?")
            .setPositiveButton("Удалить") { _, _ -> webViewController.clearCurrentDomainCookies() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showExportSheet() {
        showBottomSheet("Экспорт исследования") { dialog ->
            addView(TextView(this@WebResearchV10Activity).apply {
                text = "В архив войдут HAR, события, JavaScript, ресурсы, snapshots, cookies и metadata."
                setTextColor(palette.secondary)
                textSize = 13f
                setPadding(dp(14), dp(4), dp(14), dp(14))
            })
            listOf("HAR", "События", "JavaScript", "Ресурсы", "Snapshots", "Cookies", "Metadata").forEach { item ->
                addView(TextView(this@WebResearchV10Activity).apply {
                    text = "✓  $item"
                    setTextColor(palette.text)
                    textSize = 14f
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                })
            }
            addPrimaryButton("Создать ZIP") {
                dialog.dismiss()
                exportZip()
            }
        }
    }

    private fun showThemePicker() {
        val modes = WebUiTheme.Mode.entries.toTypedArray()
        val labels = modes.map { it.label }.toTypedArray()
        val selected = modes.indexOf(WebUiTheme.savedMode(this))
        AlertDialog.Builder(this)
            .setTitle("Тема")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                WebUiTheme.save(this, modes[which])
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showAbout() {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "dev" } catch (_: Exception) { "dev" }
        AlertDialog.Builder(this)
            .setTitle("web research")
            .setMessage("Версия приложения: $version\nРелиз: $version\n\nМобильный браузер для исследования сетевого взаимодействия сайтов.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showBottomSheet(title: String, build: LinearLayout.(Dialog) -> Unit) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(16))
            background = rounded(palette.card, 22f, palette.divider)
        }
        panel.addView(View(this).apply { background = rounded(palette.divider, 2f) }, LinearLayout.LayoutParams(dp(38), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(9) })
        panel.addView(TextView(this).apply {
            text = title
            setTextColor(palette.text)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(2), dp(12), dp(10))
        })
        panel.build(dialog)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
            addView(panel, ViewGroup.LayoutParams(-1, -2))
        }
        dialog.setContentView(scroll)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.BOTTOM
                dimAmount = 0.35f
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            dialog.window?.setGravity(Gravity.BOTTOM)
        }
        dialog.show()
    }

    private fun LinearLayout.addSection(label: String) {
        addView(TextView(this@WebResearchV10Activity).apply {
            text = label
            setTextColor(palette.secondary)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .08f
            setPadding(dp(14), dp(14), dp(14), dp(5))
        })
    }

    private fun LinearLayout.addMenuRow(icon: String, title: String, subtitle: String = "", click: () -> Unit) {
        val row = LinearLayout(this@WebResearchV10Activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
        row.addView(TextView(this@WebResearchV10Activity).apply {
            text = icon
            setTextColor(palette.accent)
            textSize = 18f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(34), dp(42)))
        val labels = LinearLayout(this@WebResearchV10Activity).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this@WebResearchV10Activity).apply {
            text = title
            setTextColor(palette.text)
            textSize = 14.5f
        })
        if (subtitle.isNotBlank()) labels.addView(TextView(this@WebResearchV10Activity).apply {
            text = subtitle
            setTextColor(palette.secondary)
            textSize = 11f
            maxLines = 1
        })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this@WebResearchV10Activity).apply {
            text = "›"
            setTextColor(palette.secondary)
            textSize = 22f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(28), dp(42)))
        addView(row)
        addDivider()
    }

    private fun LinearLayout.addDivider() {
        addView(View(this@WebResearchV10Activity).apply { setBackgroundColor(palette.divider) }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(48); marginEnd = dp(10) })
    }

    private fun LinearLayout.addPrimaryButton(label: String, click: () -> Unit) {
        addView(Button(this@WebResearchV10Activity).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(palette.accent, 15f)
            setOnClickListener { click() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(12), dp(12), dp(12), dp(2)) })
    }

    private fun LinearLayout.addDangerButton(label: String, click: () -> Unit) {
        addView(Button(this@WebResearchV10Activity).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTextColor(palette.red)
            background = rounded(palette.address, 15f, palette.divider)
            setOnClickListener { click() }
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(12), dp(8), dp(12), dp(2)) })
    }

    private fun iconButton(kind: TechIconDrawable.Kind, strong: Boolean, click: () -> Unit) = Button(this).apply {
        text = ""
        contentDescription = when (kind) {
            TechIconDrawable.Kind.MENU -> "Меню"
            TechIconDrawable.Kind.NETWORK -> "Network / Research"
            TechIconDrawable.Kind.NAVIGATE -> "Перейти"
            TechIconDrawable.Kind.BACK -> "Назад"
        }
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(9), dp(9), dp(9), dp(9))
        background = rounded(if (strong) palette.card else Color.TRANSPARENT, 16f, if (strong) palette.divider else Color.TRANSPARENT)
        foreground = TechIconDrawable(kind, palette.accent)
        setOnClickListener { click() }
    }

    private fun currentPage(): String = web.url ?: address.text?.toString().orEmpty()
    private fun currentHost(): String = hostOf(currentPage()).ifBlank { "Текущий сайт" }
    private fun hostOf(url: String): String = try { Uri.parse(url).host.orEmpty() } catch (_: Exception) { "" }
    private fun cookieCount(): Int = CookieManager.getInstance().getCookie(currentPage()).orEmpty().split(';').count { it.trim().isNotBlank() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    fun requestResourceCopy(url: String, headersJson: JSONObject?): Boolean =
        if (::captureController.isInitialized) captureController.requestResourceCopy(url, headersJson) else false

    fun ensureInstrumentation() {
        if (::captureController.isInitialized) captureController.ensureInstrumentation()
    }

    private fun capturePageSnapshot() {
        if (::captureController.isInitialized) captureController.capturePageSnapshot()
    }

    private fun addRecord(record: JSONObject) {
        archive.addRecord(record)
        scheduleBadgeUpdate()
    }

    private fun scheduleBadgeUpdate() {
        if (!badgeUpdatePending.compareAndSet(false, true)) return
        uiHandler.postDelayed({
            badgeUpdatePending.set(false)
            if (::networkBadge.isInitialized && !isFinishing) updateBadge()
        }, 250)
    }

    private fun updateBadge() {
        val count = archive.records.length()
        networkBadge.text = if (count > 999) "999+" else count.toString()
        networkBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private fun exportZip() {
        if (::exportController.isInitialized) exportController.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::exportController.isInitialized) exportController.handleResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        if (::captureController.isInitialized) captureController.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
