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
import android.webkit.JavascriptInterface
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
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WebResearchV10Activity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var address: EditText
    private lateinit var badge: TextView
    private lateinit var stats: TextView
    private lateinit var statsPanel: LinearLayout
    private lateinit var statsHeader: Button
    private lateinit var bookmarkSpinner: Spinner
    private lateinit var bookmarkAdapter: ArrayAdapter<String>
    private val archive = ResearchArchive()
    private val downloadingScripts = ConcurrentHashMap.newKeySet<String>()
    private val downloadingResources = ConcurrentHashMap.newKeySet<String>()
    private val scriptChunks = ConcurrentHashMap<String, MutableMap<Int, String>>()
    private val artifactChunks = ConcurrentHashMap<String, MutableMap<Int, String>>()
    private val bookmarks = mutableListOf<String>()
    private lateinit var userAgent: String
    private val captureExecutor = Executors.newFixedThreadPool(2)
    private val badgeUpdatePending = AtomicBoolean(false)
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsTicker = object : Runnable {
        override fun run() {
            if (::statsPanel.isInitialized && statsPanel.visibility == View.VISIBLE) {
                updateStats()
                statsHandler.postDelayed(this, 500)
            }
        }
    }

    private val bg = Color.rgb(6, 14, 12)
    private val panel = Color.rgb(14, 29, 24)
    private val panel2 = Color.rgb(20, 39, 33)
    private val accent = Color.rgb(151, 231, 92)
    private val text = Color.rgb(238, 245, 241)
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
            setTextColor(text)
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
            setTextColor(text)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 14f
            background = rounded(panel2, 14f, Color.rgb(54, 76, 66))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setText("https://evrasia.rest/")
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { navigate(text.toString()); true } else false
            }
        }
        val go = actionButton("→") { navigate(address.text.toString()) }
        nav.addView(address, LinearLayout.LayoutParams(0, dp(46), 1f))
        nav.addView(go, LinearLayout.LayoutParams(dp(52), dp(46)).apply { marginStart = dp(8) })
        navCard.addView(nav)
        root.addView(navCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(8)) })

        val bookmarkCard = card().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
        val bookmarkTitle = TextView(this).apply { text = "ЗАКЛАДКИ"; setTextColor(muted); textSize = 11f; typeface = Typeface.DEFAULT_BOLD }
        bookmarkCard.addView(bookmarkTitle)
        val bookmarkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, 0) }
        bookmarkSpinner = Spinner(this)
        bookmarkAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bookmarks)
        bookmarkSpinner.adapter = bookmarkAdapter
        val openBookmark = compactButton("Открыть") { if (bookmarks.isNotEmpty()) navigate(bookmarks[bookmarkSpinner.selectedItemPosition]) }
        val saveBookmark = compactButton("★") { saveBookmark(address.text.toString()) }
        val deleteBookmark = compactButton("−") { deleteSelectedBookmark() }
        bookmarkRow.addView(bookmarkSpinner, LinearLayout.LayoutParams(0, dp(44), 1f))
        bookmarkRow.addView(openBookmark)
        bookmarkRow.addView(saveBookmark)
        bookmarkRow.addView(deleteBookmark)
        bookmarkCard.addView(bookmarkRow)
        root.addView(bookmarkCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(8)) })
        loadBookmarks()

        statsHeader = Button(this).apply {
            text = "Куки  ▾"
            setTextColor(text)
            textSize = 14f
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            background = rounded(panel, 16f, Color.rgb(37, 62, 51))
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { toggleStats() }
        }
        root.addView(statsHeader, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(12), 0, dp(12), dp(6)) })

        statsPanel = card().apply {
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
        statsTop.addView(compactButton("Копировать") { copyStats() })
        statsPanel.addView(statsTop)
        stats = TextView(this).apply {
            setTextColor(text)
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
        }
        statsPanel.addView(stats)
        root.addView(statsPanel, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(8)) })

        web = WebView(this).apply { setBackgroundColor(Color.WHITE) }
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(accent)
            setProgressBackgroundColorSchemeColor(panel2)
            setOnChildScrollUpCallback { _, _ -> web.canScrollVertically(-1) }
            setOnRefreshListener {
                web.reload()
                statsHandler.postDelayed({ if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false }, 15000)
            }
            addView(web, android.view.ViewGroup.LayoutParams(-1, -1))
        }
        root.addView(swipeRefresh, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(panel) }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(6)) }
        badge = TextView(this).apply { text = "0 событий"; setTextColor(muted); textSize = 11f; setPadding(dp(4), 0, dp(12), 0) }
        controls.addView(badge)
        controls.addView(compactButton("Очистить") {
            archive.clear(); downloadingScripts.clear(); downloadingResources.clear(); scriptChunks.clear(); artifactChunks.clear(); updateBadge(); updateStats()
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
        WebView.setWebContentsDebuggingEnabled(true)
        web.addJavascriptInterface(Bridge(this), "EvrasiaResearch")

        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                addRecord(JSONObject().put("source", "console").put("time", System.currentTimeMillis()).put("level", message.messageLevel().name).put("message", message.message()).put("sourceId", message.sourceId()).put("line", message.lineNumber()))
                return true
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (resultMsg == null) return false
                val temp = WebView(this@WebResearchV10Activity)
                temp.settings.javaScriptEnabled = true
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        openInActiveWindow(request.url.toString()); temp.destroy(); return true
                    }
                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                        if (url != "about:blank") { openInActiveWindow(url); temp.stopLoading(); temp.destroy() }
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
                statsHandler.postDelayed({ ensureInstrumentation() }, 100)
                statsHandler.postDelayed({ ensureInstrumentation() }, 350)
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false
                address.setText(url)
                addRecord(JSONObject().put("source", "navigation").put("time", System.currentTimeMillis()).put("url", url).put("page", url).put("method", "GET"))
                ensureInstrumentation(); captureLightPageSnapshot(); updateStats()
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                request?.let {
                    val url = it.url.toString(); val headers = HashMap(it.requestHeaders)
                    addRecord(JSONObject().put("source", "webview").put("time", System.currentTimeMillis()).put("method", it.method).put("url", url).put("headers", JSONObject(headers)))
                    if (it.method.equals("GET", true) && (url.startsWith("http://") || url.startsWith("https://")) && shouldAutoCopyResource(url, headers)) captureResource(url, headers, "auto-static")
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
        navigate("https://evrasia.rest/")
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
        text = label; setTextColor(text); textSize = 11f; isAllCaps = false; minWidth = 0; minimumWidth = 0; setPadding(dp(10), 0, dp(10), 0); background = rounded(panel2, 12f, Color.rgb(50, 76, 65)); setOnClickListener { click() }
    }

    private fun toggleStats() {
        val show = statsPanel.visibility != View.VISIBLE
        statsPanel.visibility = if (show) View.VISIBLE else View.GONE
        statsHeader.text = if (show) "Куки  ▴" else "Куки  ▾"
        statsHandler.removeCallbacks(statsTicker)
        if (show) { updateStats(); statsHandler.post(statsTicker) }
    }

    private fun copyStats() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("web research statistics", stats.text ?: ""))
        Toast.makeText(this, "Статистика скопирована", Toast.LENGTH_SHORT).show()
    }

    private fun normalizeUrl(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return "https://evrasia.rest/"
        return if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
    }

    private fun navigate(raw: String) {
        val url = normalizeUrl(raw)
        address.setText(url)
        web.loadUrl(url)
    }

    private fun openInActiveWindow(url: String) {
        runOnUiThread { navigate(url) }
        addRecord(JSONObject().put("source", "new-window").put("time", System.currentTimeMillis()).put("url", url).put("method", "GET"))
    }

    private fun loadBookmarks() {
        bookmarks.clear()
        val saved = getSharedPreferences("web-research", Context.MODE_PRIVATE).getStringSet("bookmarks", emptySet()) ?: emptySet()
        bookmarks.addAll(saved.sorted())
        bookmarkAdapter.notifyDataSetChanged()
    }

    private fun saveBookmark(raw: String) {
        val url = normalizeUrl(raw)
        if (!bookmarks.contains(url)) bookmarks.add(url)
        bookmarks.sort(); bookmarkAdapter.notifyDataSetChanged()
        getSharedPreferences("web-research", Context.MODE_PRIVATE).edit().putStringSet("bookmarks", bookmarks.toSet()).apply()
        bookmarkSpinner.setSelection(bookmarks.indexOf(url).coerceAtLeast(0))
        Toast.makeText(this, "Закладка сохранена", Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelectedBookmark() {
        if (bookmarks.isEmpty()) return
        bookmarks.removeAt(bookmarkSpinner.selectedItemPosition)
        bookmarkAdapter.notifyDataSetChanged()
        getSharedPreferences("web-research", Context.MODE_PRIVATE).edit().putStringSet("bookmarks", bookmarks.toSet()).apply()
    }

    private fun updateStats() {
        if (!::stats.isInitialized || !::web.isInitialized) return
        val page = web.url ?: address.text?.toString().orEmpty()
        val raw = CookieManager.getInstance().getCookie(page).orEmpty()
        val cookies = raw.split(';').map { it.trim() }.filter { it.isNotBlank() }
        stats.text = buildString {
            append("Страница: ").append(page.ifBlank { "—" })
            append("\nКуки текущей сессии: ").append(cookies.size)
            if (cookies.isNotEmpty()) {
                append("\n\n")
                cookies.forEachIndexed { i, c -> append(i + 1).append(". ").append(c).append('\n') }
            }
        }.trimEnd()
    }

    private fun looksLikeJs(url: String): Boolean {
        val clean = url.substringBefore('#').substringBefore('?').lowercase(Locale.US)
        return clean.endsWith(".js") || clean.endsWith(".mjs")
    }

    private fun headerValue(headers: Map<String, String>, name: String): String =
        headers.entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty()

    private fun shouldAutoCopyResource(url: String, headers: Map<String, String>): Boolean {
        val clean = url.substringBefore('#').substringBefore('?').lowercase(Locale.US)
        val staticExt = listOf(".js", ".mjs", ".css", ".map", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".otf")
        if (staticExt.any { clean.endsWith(it) }) return true
        val destination = headerValue(headers, "Sec-Fetch-Dest").lowercase(Locale.US)
        if (destination in setOf("script", "style", "image", "font")) return true
        val accept = headerValue(headers, "Accept").lowercase(Locale.US)
        return accept.contains("image/") || accept.contains("font/") || accept.contains("text/css") || accept.contains("javascript")
    }

    fun requestResourceCopy(url: String, headersJson: JSONObject?): Boolean {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false
        val headers = linkedMapOf<String, String>()
        if (headersJson != null) {
            val keys = headersJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                headers[key] = headersJson.optString(key, "")
            }
        }
        captureResource(url, headers, "manual-fallback")
        return true
    }

    private fun openConnection(url: String, headers: Map<String, String>): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true; c.connectTimeout = 15000; c.readTimeout = 45000; c.requestMethod = "GET"
        headers.forEach { (k, v) -> if (!k.equals("Host", true) && !k.equals("Content-Length", true)) try { c.setRequestProperty(k, v) } catch (_: Exception) {} }
        CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
        c.setRequestProperty("User-Agent", userAgent)
        return c
    }

    private fun captureResource(url: String, headers: Map<String, String>, copyMode: String) {
        if (archive.resources.containsKey(url) || !downloadingResources.add(url)) return
        captureExecutor.execute {
            try {
                val c = openConnection(url, headers); val started = System.currentTimeMillis(); val status = c.responseCode
                val bytes = (if (status in 200..399) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                val responseHeaders = JSONObject(); c.headerFields.filterKeys { it != null }.forEach { (k, v) -> responseHeaders.put(k, v.joinToString(", ")) }
                val finalUrl = c.url.toString(); val contentType = c.contentType ?: ""
                archive.resources[url] = bytes
                archive.resourceMeta[url] = JSONObject().put("status", status).put("contentType", contentType).put("finalUrl", finalUrl).put("responseHeaders", responseHeaders).put("copyMode", copyMode)
                if (looksLikeJs(url) || contentType.contains("javascript", true)) archive.scripts[url] = bytes
                addRecord(JSONObject().put("source", "resource-copy").put("copyMode", copyMode).put("time", started).put("duration", System.currentTimeMillis() - started).put("method", "GET").put("url", url).put("status", status).put("responseHeaders", responseHeaders).put("mimeType", contentType).put("responseSize", bytes.size).put("redirectURL", if (finalUrl != url) finalUrl else ""))
                c.disconnect()
            } catch (e: Exception) {
                archive.resourceMeta[url] = JSONObject().put("error", e.toString()).put("copyMode", copyMode)
                addRecord(JSONObject().put("source", "resource-copy").put("copyMode", copyMode).put("time", System.currentTimeMillis()).put("method", "GET").put("url", url).put("error", e.toString()))
            } finally { downloadingResources.remove(url); scheduleBadgeUpdate() }
        }
    }

    private fun captureExternalScript(url: String, headers: Map<String, String>) {
        if (url.startsWith("blob:") || url.startsWith("data:") || archive.scripts.containsKey(url) || !downloadingScripts.add(url)) return
        captureExecutor.execute {
            try {
                val c = openConnection(url, headers); val status = c.responseCode
                val bytes = (if (status in 200..399) c.inputStream else c.errorStream)?.use { it.readBytes() }
                if (bytes != null) archive.scripts[url] = bytes else archive.scriptErrors[url] = "HTTP $status: empty body"
                c.disconnect()
            } catch (e: Exception) { archive.scriptErrors[url] = e.toString() }
            finally { downloadingScripts.remove(url); scheduleBadgeUpdate() }
        }
    }

    fun ensureInstrumentation() {
        if (!::web.isInitialized) return
        val js = """
          (function(){
            if(window.__WR10)return; window.__WR10=true;
            window.__WR_REQ_HINTS=window.__WR_REQ_HINTS||[];
            const send=o=>{try{EvrasiaResearch.record(JSON.stringify(o))}catch(e){}};
            const absolute=u=>{try{return new URL(String(u||''),location.href).href}catch(e){return String(u||'')}};
            const remember=(u,m,t)=>{try{let a=window.__WR_REQ_HINTS;a.push({url:absolute(u),method:String(m||'GET').toUpperCase(),time:t});if(a.length>300)a.splice(0,a.length-300)}catch(e){}};
            const headersObject=h=>{let o={};try{new Headers(h||{}).forEach((v,k)=>o[k]=v)}catch(e){}return o};
            const bodyPreview=b=>{try{if(b==null)return'';if(typeof b==='string')return b;if(b instanceof URLSearchParams)return b.toString();if(typeof FormData!=='undefined'&&b instanceof FormData)return JSON.stringify(Array.from(b.entries()).map(([k,v])=>[k,typeof v==='string'?v:'[File '+(v?.name||'')+' '+(v?.size||0)+' bytes]']));if(typeof Blob!=='undefined'&&b instanceof Blob)return '[Blob '+(b.type||'')+' '+b.size+' bytes]';if(typeof ArrayBuffer!=='undefined'&&b instanceof ArrayBuffer)return '[ArrayBuffer '+b.byteLength+' bytes]';if(ArrayBuffer.isView?.(b))return '[TypedArray '+b.byteLength+' bytes]';return String(b)}catch(e){return'[unavailable]'}};
            const isTextual=ct=>!ct||/json|text|javascript|ecmascript|css|html|xml|x-www-form-urlencoded|graphql/.test(String(ct).toLowerCase());
            const chunk=(k,t,s)=>{t=String(t??'');let z=100000,n=Math.max(1,Math.ceil(t.length/z));for(let i=0;i<n;i++){try{s?EvrasiaResearch.scriptChunk(k,i,n,t.slice(i*z,(i+1)*z)):EvrasiaResearch.artifactChunk(k,i,n,t.slice(i*z,(i+1)*z))}catch(e){}}};
            const target=e=>{if(!e||e.nodeType!==1)return{};return{tag:(e.tagName||'').toLowerCase(),id:e.id||'',className:typeof e.className==='string'?e.className:'',name:e.name||'',type:e.type||'',role:e.getAttribute?.('role')||'',href:e.href||'',text:(e.innerText||e.textContent||'').trim().slice(0,300)}};
            ['click','change','submit'].forEach(type=>document.addEventListener(type,e=>send({source:'user-action',time:Date.now(),action:type,page:location.href,target:target(e.target)}),true));
            const HP=history.pushState.bind(history),HR=history.replaceState.bind(history);
            history.pushState=function(s,t,u){let r=HP(s,t,u);send({source:'history',time:Date.now(),action:'pushState',url:location.href,state:s});return r};
            history.replaceState=function(s,t,u){let r=HR(s,t,u);send({source:'history',time:Date.now(),action:'replaceState',url:location.href,state:s});return r};
            addEventListener('popstate',e=>send({source:'history',time:Date.now(),action:'popstate',url:location.href,state:e.state}));
            addEventListener('hashchange',e=>send({source:'history',time:Date.now(),action:'hashchange',url:location.href,oldURL:e.oldURL,newURL:e.newURL}));

            const F=window.fetch;
            if(F&&!F.__wrUnified){
              const wrapped=async function(i,n){
                const u=absolute(typeof i==='string'?i:(i&&i.url)||'');
                const m=String((n&&n.method)||(i&&i.method)||'GET').toUpperCase();
                const t=Date.now();
                let stack='';try{stack=(new Error()).stack||''}catch(e){}
                const rqHeaders=headersObject((n&&n.headers)||(i&&i.headers)||{});
                let body=bodyPreview(n&&n.body);
                if(!body&&typeof Request!=='undefined'&&i instanceof Request){try{body=await i.clone().text()}catch(e){}}
                remember(u,m,t);
                try{
                  const r=await F.apply(this,arguments);
                  const rh={};try{r.headers.forEach((v,k)=>rh[k]=v)}catch(e){}
                  const ct=(r.headers&&r.headers.get('content-type'))||'';
                  let responseBody='';
                  if(isTextual(ct)){try{responseBody=await r.clone().text()}catch(e){responseBody='[unavailable]'}}else responseBody='[binary]';
                  let responseSize=-1;try{responseSize=new TextEncoder().encode(responseBody).length}catch(e){responseSize=responseBody.length}
                  send({source:'fetch',time:t,duration:Date.now()-t,method:m,url:u,finalUrl:r.url||u,requestHeaders:rqHeaders,requestMimeType:rqHeaders['content-type']||'',requestBody:body,status:r.status,statusText:r.statusText,redirected:!!r.redirected,responseType:r.type||'',responseHeaders:rh,responseBody:responseBody,mimeType:ct,responseSize:responseSize,initiatorStack:stack});
                  return r
                }catch(e){send({source:'fetch',time:t,duration:Date.now()-t,method:m,url:u,requestHeaders:rqHeaders,requestMimeType:rqHeaders['content-type']||'',requestBody:body,initiatorStack:stack,error:String(e)});throw e}
              };
              wrapped.__wrUnified=true;window.fetch=wrapped;
            }

            try{
              const XP=XMLHttpRequest.prototype, O=XP.open, S=XP.send, H=XP.setRequestHeader;
              if(!XP.__wrUnified){
                XP.__wrUnified=true;
                XP.open=function(m,u){this.__wrMethod=String(m||'GET').toUpperCase();this.__wrUrl=absolute(u);this.__wrHeaders={};return O.apply(this,arguments)};
                XP.setRequestHeader=function(k,v){try{this.__wrHeaders[String(k).toLowerCase()]=String(v)}catch(e){};return H.apply(this,arguments)};
                XP.send=function(b){
                  const x=this,t=Date.now(),m=x.__wrMethod||'GET',u=x.__wrUrl||'';let stack='';try{stack=(new Error()).stack||''}catch(e){};const body=bodyPreview(b);remember(u,m,t);
                  x.addEventListener('loadend',()=>{let responseBody='[binary]';try{if(x.responseType===''||x.responseType==='text')responseBody=x.responseText}catch(e){};let ct='';try{ct=x.getResponseHeader('content-type')||''}catch(e){};send({source:'xhr',time:t,duration:Date.now()-t,method:m,url:u,finalUrl:x.responseURL||u,requestHeaders:x.__wrHeaders||{},requestMimeType:(x.__wrHeaders||{})['content-type']||'',requestBody:body,status:x.status,statusText:x.statusText,responseType:x.responseType||'',responseHeadersRaw:x.getAllResponseHeaders(),responseBody:responseBody,mimeType:ct,initiatorStack:stack})},{once:true});
                  return S.apply(this,arguments)
                };
              }
            }catch(e){}

            if(window.WebSocket){const W=window.WebSocket;if(!W.__wrUnified){const Wrapped=class extends W{constructor(u,p){super(u,p);this.__u=String(u);send({source:'websocket-open',time:Date.now(),url:this.__u});this.addEventListener('message',e=>send({source:'websocket-receive',time:Date.now(),url:this.__u,data:typeof e.data==='string'?e.data:'[binary]'}))}send(d){send({source:'websocket-send',time:Date.now(),url:this.__u,data:typeof d==='string'?d:'[binary]'});return super.send(d)}};Wrapped.__wrUnified=true;window.WebSocket=Wrapped}}
            if(window.EventSource){const E=window.EventSource;if(!E.__wrUnified){const Wrapped=class extends E{constructor(u,o){super(u,o);this.__u=String(u);send({source:'sse-open',time:Date.now(),url:this.__u});this.addEventListener('message',e=>send({source:'sse-message',time:Date.now(),url:this.__u,data:e.data,lastEventId:e.lastEventId||''}))}};Wrapped.__wrUnified=true;window.EventSource=Wrapped}}
            const seenScripts=new WeakSet();let dynamicInline=0;
            const archiveScript=(s,key)=>{try{if(!s||seenScripts.has(s))return;seenScripts.add(s);if(s.src)EvrasiaResearch.externalScript(String(s.src));else if(s.textContent)chunk(key||location.href+'#inline-dynamic-'+(++dynamicInline),s.textContent,true)}catch(e){}};
            Array.from(document.scripts).forEach((s,i)=>archiveScript(s,location.href+'#inline-'+i));
            let mutationAdded=0,mutationRemoved=0,mutationAttributes=0,mutationTimer=0;
            const flushMutations=()=>{mutationTimer=0;if(!(mutationAdded||mutationRemoved||mutationAttributes))return;send({source:'dom-mutation',time:Date.now(),page:location.href,mutations:[{type:'batch',added:mutationAdded,removed:mutationRemoved,attributes:mutationAttributes}]});mutationAdded=0;mutationRemoved=0;mutationAttributes=0};
            new MutationObserver(ms=>{for(const m of ms){if(m.type==='attributes'){mutationAttributes++;continue}mutationAdded+=m.addedNodes?.length||0;mutationRemoved+=m.removedNodes?.length||0;for(const n of Array.from(m.addedNodes||[])){if(!n||n.nodeType!==1)continue;if(String(n.tagName||'').toLowerCase()==='script')archiveScript(n,location.href+'#inline-dynamic-'+(++dynamicInline));try{if(n.querySelectorAll)n.querySelectorAll('script').forEach(s=>archiveScript(s,location.href+'#inline-dynamic-'+(++dynamicInline)))}catch(e){}}}if(!mutationTimer)mutationTimer=setTimeout(flushMutations,1000)}).observe(document.documentElement,{subtree:true,childList:true,attributes:true});
            addEventListener('error',e=>send({source:'js-error',time:Date.now(),message:e.message,url:e.filename||location.href,line:e.lineno||0,column:e.colno||0}));
            addEventListener('unhandledrejection',e=>send({source:'promise-rejection',time:Date.now(),message:String(e.reason)}));
            send({source:'hook',time:Date.now(),url:location.href,status:0});
          })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun captureLightPageSnapshot() {
        if (!::web.isInitialized) return
        val nativeCookies = CookieManager.getInstance().getCookie(web.url ?: "") ?: ""
        val js = """
          (function(){
            function store(s){let o={};try{for(let i=0;i<s.length;i++){let k=s.key(i);o[k]=s.getItem(k)}}catch(e){o.__error=String(e)}return o}
            const attrs=e=>{let o={};try{for(const a of e.attributes||[])o[a.name]=a.value}catch(x){}return o};
            const elements=Array.from(document.querySelectorAll('a,button,input,select,textarea,form,[role],[onclick]')).slice(0,2500).map(e=>({tag:e.tagName.toLowerCase(),attrs:attrs(e),text:(e.innerText||e.textContent||'').trim().slice(0,300)}));
            const resources=performance.getEntriesByType('resource').map(r=>({name:r.name,initiatorType:r.initiatorType,startTime:r.startTime,duration:r.duration,transferSize:r.transferSize,encodedBodySize:r.encodedBodySize,decodedBodySize:r.decodedBodySize}));
            try{EvrasiaResearch.snapshot(JSON.stringify({time:Date.now(),url:location.href,title:document.title,cookie:document.cookie,nativeCookie:${JSONObject.quote(nativeCookies)},localStorage:store(localStorage),sessionStorage:store(sessionStorage),resources:resources,elements:elements,lightweight:true}))}catch(e){}
          })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun capturePageSnapshot() {
        val nativeCookies = CookieManager.getInstance().getCookie(web.url ?: "") ?: ""
        val js = """
          (async function(){
            const chunk=(k,t)=>{t=String(t??'');let z=100000,n=Math.max(1,Math.ceil(t.length/z));for(let i=0;i<n;i++)try{EvrasiaResearch.artifactChunk(k,i,n,t.slice(i*z,(i+1)*z))}catch(e){}};
            function store(s){let o={};try{for(let i=0;i<s.length;i++){let k=s.key(i);o[k]=s.getItem(k)}}catch(e){o.__error=String(e)}return o}
            let sw=[],cacheNames=[],db=[];
            try{if(navigator.serviceWorker){let rs=await navigator.serviceWorker.getRegistrations();sw=rs.map(r=>({scope:r.scope,active:r.active?.scriptURL||'',waiting:r.waiting?.scriptURL||'',installing:r.installing?.scriptURL||''}));sw.forEach(r=>[r.active,r.waiting,r.installing].filter(Boolean).forEach(u=>EvrasiaResearch.externalScript(String(u))))}}catch(e){sw=[{error:String(e)}]}
            try{if(window.caches){cacheNames=await window.caches.keys();for(const name of cacheNames){let c=await window.caches.open(name),keys=await c.keys();let entries=[];for(const req of keys.slice(0,250)){try{let res=await c.match(req),ct=res?.headers?.get('content-type')||'',txt='';if(res&&(/json|text|javascript|css|html|xml/.test(ct)))txt=(await res.clone().text()).slice(0,200000);entries.push({url:req.url,status:res?.status||0,contentType:ct,body:txt})}catch(e){entries.push({url:req.url,error:String(e)})}}chunk('cache-'+name+'.json',JSON.stringify({name,entries}))}}}catch(e){cacheNames=['ERROR:'+String(e)]}
            try{if(indexedDB.databases){let list=await indexedDB.databases();for(const info of list){if(!info.name)continue;let dump={name:info.name,version:info.version,stores:{}};try{let d=await new Promise((ok,bad)=>{let q=indexedDB.open(info.name);q.onsuccess=()=>ok(q.result);q.onerror=()=>bad(q.error)});for(const sn of Array.from(d.objectStoreNames)){try{let tx=d.transaction(sn,'readonly'),st=tx.objectStore(sn),vals=await new Promise((ok,bad)=>{let q=st.getAll();q.onsuccess=()=>ok(q.result);q.onerror=()=>bad(q.error)});dump.stores[sn]=vals.slice(0,1000)}catch(e){dump.stores[sn]={error:String(e)}}}d.close()}catch(e){dump.error=String(e)}chunk('indexeddb-'+info.name+'.json',JSON.stringify(dump));db.push({name:info.name,version:info.version})}}}catch(e){db=[{error:String(e)}]}
            const attrs=e=>{let o={};try{for(const a of e.attributes||[])o[a.name]=a.value}catch(x){}return o};
            const elements=Array.from(document.querySelectorAll('a,button,input,select,textarea,form,[role],[onclick]')).slice(0,10000).map(e=>({tag:e.tagName.toLowerCase(),attrs:attrs(e),text:(e.innerText||e.textContent||'').trim().slice(0,500)}));
            const resources=performance.getEntriesByType('resource').map(r=>({name:r.name,initiatorType:r.initiatorType,startTime:r.startTime,duration:r.duration,transferSize:r.transferSize,encodedBodySize:r.encodedBodySize,decodedBodySize:r.decodedBodySize}));
            try{EvrasiaResearch.snapshot(JSON.stringify({time:Date.now(),url:location.href,title:document.title,cookie:document.cookie,nativeCookie:${JSONObject.quote(nativeCookies)},localStorage:store(localStorage),sessionStorage:store(sessionStorage),serviceWorkers:sw,cacheStorage:cacheNames,indexedDB:db,resources:resources,elements:elements,html:document.documentElement.outerHTML,fullSnapshot:true}))}catch(e){}
          })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun addRecord(record: JSONObject) { archive.addRecord(record); scheduleBadgeUpdate() }

    private fun scheduleBadgeUpdate() {
        if (!badgeUpdatePending.compareAndSet(false, true)) return
        statsHandler.postDelayed({
            badgeUpdatePending.set(false)
            if (::badge.isInitialized && !isFinishing) updateBadge()
        }, 250)
    }

    private fun updateBadge() { badge.text = "${archive.records.length()} событий · ${archive.scripts.size} JS · ${archive.resources.size} ресурсов" }

    private fun exportZip() {
        capturePageSnapshot()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"; putExtra(Intent.EXTRA_TITLE, "web-research-$stamp.zip") }
        startActivityForResult(intent, 501)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 501 && resultCode == RESULT_OK) data?.data?.let { uri -> contentResolver.openOutputStream(uri)?.use { archive.writeZip(it, web.url ?: "") } }
    }

    override fun onResume() {
        super.onResume()
        if (::statsPanel.isInitialized && statsPanel.visibility == View.VISIBLE) { statsHandler.removeCallbacks(statsTicker); statsHandler.post(statsTicker) }
    }

    override fun onPause() {
        statsHandler.removeCallbacks(statsTicker)
        super.onPause()
    }

    override fun onDestroy() {
        statsHandler.removeCallbacks(statsTicker)
        captureExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    inner class Bridge(private val context: Context) {
        @JavascriptInterface fun record(json: String) { try { addRecord(JSONObject(json)) } catch (_: Exception) {} }
        @JavascriptInterface fun snapshot(json: String) { try { archive.snapshot = JSONObject(json); runOnUiThread { updateStats() } } catch (_: Exception) {} }
        @JavascriptInterface fun externalScript(url: String) { if (url.isNotBlank()) captureExternalScript(url, emptyMap()) }
        @JavascriptInterface fun requestSnapshot() { runOnUiThread { capturePageSnapshot() } }
        @JavascriptInterface fun scriptChunk(url: String, index: Int, total: Int, chunk: String) { collectChunk(url, index, total, chunk, true) }
        @JavascriptInterface fun artifactChunk(key: String, index: Int, total: Int, chunk: String) { collectChunk(key, index, total, chunk, false) }
    }

    private fun collectChunk(key: String, index: Int, total: Int, chunk: String, script: Boolean) {
        try {
            val all = if (script) scriptChunks else artifactChunks
            val map = all.getOrPut(key) { ConcurrentHashMap() }; map[index] = chunk
            if (map.size == total) {
                val out = StringBuilder(); for (i in 0 until total) out.append(map[i] ?: "")
                if (script) archive.scripts[key] = out.toString().toByteArray(Charsets.UTF_8) else archive.extraArtifacts[key] = out.toString().toByteArray(Charsets.UTF_8)
                all.remove(key); scheduleBadgeUpdate()
            }
        } catch (e: Exception) { if (script) archive.scriptErrors[key] = e.toString() }
    }
}
