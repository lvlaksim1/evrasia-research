package ru.evrasia.research

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject

internal class WebResearchWebViewController(
    private val activity: AppCompatActivity,
    private val web: WebView,
    private val swipeRefresh: SwipeRefreshLayout,
    private val address: EditText,
    private val captureController: WebCaptureController,
    private val navigationController: WebNavigationController,
    private val handler: Handler,
    private val record: (JSONObject) -> Unit,
    private val updateStats: () -> Unit
) {
    private var mobileUserAgent = ""
    private var desktopUserAgent = ""
    private var desktopMode = false
    private var modeMenuButton: Button? = null

    fun install() {
        initializeBrowserMode()
        installHamburgerActions()
        WebDownloadController(activity, web, web.settings.userAgentString, record).install()

        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                record(JSONObject().put("source", "console").put("time", System.currentTimeMillis()).put("level", message.messageLevel().name).put("message", message.message()).put("sourceId", message.sourceId()).put("line", message.lineNumber()))
                return true
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (resultMsg == null) return false
                val temp = WebView(activity)
                temp.settings.javaScriptEnabled = true
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        navigationController.openInActiveWindow(request.url.toString())
                        temp.destroy()
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                        if (url != "about:blank") {
                            navigationController.openInActiveWindow(url)
                            temp.stopLoading()
                            temp.destroy()
                        }
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                handler.postDelayed({ captureController.ensureInstrumentation() }, 100)
                handler.postDelayed({ captureController.ensureInstrumentation() }, 350)
                if (desktopMode) {
                    handler.postDelayed({ applyDesktopViewport() }, 150)
                    handler.postDelayed({ applyDesktopViewport() }, 500)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                address.setText(url)
                record(JSONObject().put("source", "navigation").put("time", System.currentTimeMillis()).put("url", url).put("page", url).put("method", "GET"))
                if (desktopMode) {
                    applyDesktopViewport()
                    handler.postDelayed({ applyDesktopViewport() }, 250)
                    handler.postDelayed({ applyDesktopViewport() }, 900)
                }
                captureController.ensureInstrumentation()
                captureController.captureLightPageSnapshot()
                updateStats()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                request?.let {
                    val url = it.url.toString()
                    val headers = HashMap(it.requestHeaders)
                    record(JSONObject().put("source", "webview").put("time", System.currentTimeMillis()).put("method", it.method).put("url", url).put("headers", JSONObject(headers)))
                    if (it.method.equals("GET", true) && (url.startsWith("http://") || url.startsWith("https://")) && captureController.shouldAutoCopyResource(url, headers)) {
                        captureController.captureResource(url, headers, "auto-static")
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun installHamburgerActions() {
        val root = swipeRefresh.parent as? LinearLayout ?: return
        val bookmarkPanel = (0 until root.childCount)
            .map { root.getChildAt(it) }
            .filterIsInstance<LinearLayout>()
            .firstOrNull { containsSpinner(it) } ?: return
        if (bookmarkPanel.findViewWithTag<View>("web-browser-menu-actions") != null) return

        val row = LinearLayout(activity).apply {
            tag = "web-browser-menu-actions"
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val modeButton = menuActionButton(browserModeLabel()) {
            toggleBrowserMode()
        }
        modeMenuButton = modeButton
        val cookieButton = menuActionButton("Удалить куки домена") {
            clearCurrentDomainCookies()
        }
        row.addView(modeButton, LinearLayout.LayoutParams(0, dp(38), 1f))
        row.addView(cookieButton, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(6) })
        bookmarkPanel.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun containsSpinner(view: View): Boolean {
        if (view is Spinner) return true
        if (view !is ViewGroup) return false
        for (index in 0 until view.childCount) {
            if (containsSpinner(view.getChildAt(index))) return true
        }
        return false
    }

    private fun menuActionButton(label: String, click: () -> Unit) = Button(activity).apply {
        text = label
        setTextColor(Color.rgb(232, 244, 248))
        textSize = 9.5f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(8), 0, dp(8), 0)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.rgb(10, 25, 34))
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), Color.rgb(21, 57, 69))
        }
        setOnClickListener { click() }
    }

    internal fun browserModeLabel(): String = if (desktopMode) "Версия: ПК" else "Версия: мобильная"

    internal fun toggleBrowserMode() {
        applyBrowserMode(!desktopMode, true)
    }

    private fun initializeBrowserMode() {
        mobileUserAgent = web.settings.userAgentString
        desktopUserAgent = buildDesktopUserAgent(mobileUserAgent)
        val savedDesktopMode = activity.getSharedPreferences("web-research-browser", Context.MODE_PRIVATE).getBoolean("desktop-mode", false)
        applyBrowserMode(savedDesktopMode, false)
    }

    private fun applyBrowserMode(desktop: Boolean, reload: Boolean) {
        desktopMode = desktop
        val userAgent = if (desktop) desktopUserAgent else mobileUserAgent
        web.settings.userAgentString = userAgent
        web.settings.useWideViewPort = desktop
        web.settings.loadWithOverviewMode = desktop
        web.setInitialScale(0)
        captureController.updateUserAgent(userAgent)
        activity.getSharedPreferences("web-research-browser", Context.MODE_PRIVATE).edit().putBoolean("desktop-mode", desktop).apply()
        modeMenuButton?.text = browserModeLabel()
        record(JSONObject()
            .put("source", "browser-mode")
            .put("time", System.currentTimeMillis())
            .put("mode", if (desktop) "desktop" else "mobile")
            .put("userAgent", userAgent)
            .put("desktopViewportWidth", if (desktop) 1280 else JSONObject.NULL))
        if (reload && !web.url.isNullOrBlank()) web.reload()
    }

    private fun applyDesktopViewport() {
        if (!desktopMode) return
        val script = """
            (function() {
                try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.setAttribute('name', 'viewport');
                        (document.head || document.documentElement).appendChild(meta);
                    }
                    var desktopContent = 'width=1280, initial-scale=1.0, minimum-scale=0.1, maximum-scale=5.0, user-scalable=yes';
                    if (meta.getAttribute('content') !== desktopContent) meta.setAttribute('content', desktopContent);
                    document.documentElement.style.minWidth = '1280px';
                    if (document.body) document.body.style.minWidth = '1280px';
                    if (!window.__WR_DESKTOP_VIEWPORT_OBSERVER) {
                        window.__WR_DESKTOP_VIEWPORT_OBSERVER = new MutationObserver(function() {
                            var current = document.querySelector('meta[name="viewport"]');
                            if (current && current.getAttribute('content') !== desktopContent) current.setAttribute('content', desktopContent);
                            document.documentElement.style.minWidth = '1280px';
                            if (document.body) document.body.style.minWidth = '1280px';
                        });
                        window.__WR_DESKTOP_VIEWPORT_OBSERVER.observe(document.documentElement, {subtree:true, childList:true, attributes:true, attributeFilter:['content']});
                    }
                    window.dispatchEvent(new Event('resize'));
                    return JSON.stringify({width: window.innerWidth, screenWidth: screen.width, viewport: meta.getAttribute('content')});
                } catch (e) {
                    return JSON.stringify({error:String(e)});
                }
            })();
        """.trimIndent()
        web.evaluateJavascript(script) { result ->
            record(JSONObject()
                .put("source", "desktop-viewport")
                .put("time", System.currentTimeMillis())
                .put("url", web.url ?: "")
                .put("result", result ?: ""))
        }
    }

    private fun buildDesktopUserAgent(mobile: String): String {
        val chrome = Regex("Chrome/[^\\s]+", RegexOption.IGNORE_CASE).find(mobile)?.value ?: "Chrome/120.0.0.0"
        val suffix = if (mobile.contains("WebResearch/10")) " WebResearch/10" else ""
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36$suffix"
    }

    internal fun clearCurrentDomainCookies() {
        val page = web.url ?: address.text?.toString().orEmpty()
        val uri = try { Uri.parse(page) } catch (_: Exception) { null }
        val host = uri?.host.orEmpty()
        if (host.isBlank() || uri?.scheme !in setOf("http", "https")) {
            Toast.makeText(activity, "Текущий домен не определён", Toast.LENGTH_SHORT).show()
            return
        }

        val scheme = uri?.scheme ?: "https"
        val targets = linkedSetOf(page, "$scheme://$host/", "https://$host/", "http://$host/")
        val manager = CookieManager.getInstance()
        val names = linkedSetOf<String>()
        targets.forEach { target ->
            manager.getCookie(target).orEmpty().split(';').forEach { part ->
                val name = part.trim().substringBefore('=').trim()
                if (name.isNotBlank()) names.add(name)
            }
        }

        if (names.isEmpty()) {
            Toast.makeText(activity, "Для $host куки не найдены", Toast.LENGTH_SHORT).show()
            updateStats()
            return
        }

        val paths = cookiePaths(uri?.path.orEmpty())
        val domains = cookieDomains(host)
        val expires = "Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"
        names.forEach { name ->
            targets.forEach { target ->
                paths.forEach { path ->
                    manager.setCookie(target, "$name=; $expires; Path=$path")
                    domains.forEach { domain ->
                        manager.setCookie(target, "$name=; $expires; Path=$path; Domain=$domain")
                        manager.setCookie(target, "$name=; $expires; Path=$path; Domain=.$domain")
                    }
                }
            }
        }
        manager.flush()
        record(JSONObject()
            .put("source", "cookie-clear")
            .put("time", System.currentTimeMillis())
            .put("url", page)
            .put("host", host)
            .put("cookieNames", names.size))

        handler.postDelayed({
            updateStats()
            Toast.makeText(activity, "Куки домена $host удалены", Toast.LENGTH_SHORT).show()
            if (!web.url.isNullOrBlank()) web.reload()
        }, 250)
    }

    private fun cookiePaths(path: String): Set<String> {
        val out = linkedSetOf("/")
        val segments = path.split('/').filter { it.isNotBlank() }
        for (count in segments.size downTo 1) {
            out.add("/" + segments.take(count).joinToString("/"))
        }
        return out
    }

    private fun cookieDomains(host: String): Set<String> {
        if (host.contains(':') || host.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}"))) return setOf(host)
        val labels = host.split('.').filter { it.isNotBlank() }
        if (labels.size < 2) return setOf(host)
        val out = linkedSetOf<String>()
        for (index in 0 until labels.size - 1) out.add(labels.drop(index).joinToString("."))
        return out
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
