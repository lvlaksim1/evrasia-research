package ru.evrasia.research

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
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
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class WebResearchActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var badge: TextView
    private lateinit var stats: TextView
    private lateinit var bookmarkSpinner: Spinner
    private lateinit var bookmarkAdapter: ArrayAdapter<String>
    private val archive = ResearchArchive()
    private val downloadingScripts = ConcurrentHashMap.newKeySet<String>()
    private val downloadingResources = ConcurrentHashMap.newKeySet<String>()
    private val scriptChunks = ConcurrentHashMap<String, MutableMap<Int, String>>()
    private val artifactChunks = ConcurrentHashMap<String, MutableMap<Int, String>>()
    private val bookmarks = mutableListOf<String>()
    private lateinit var userAgent: String

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "web research"
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 19, 16))
        }

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 6, 8, 4)
        }
        address = EditText(this).apply {
            hint = "https://example.com/path"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setText("https://evrasia.rest/")
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { navigate(text.toString()); true } else false
            }
        }
        val go = Button(this).apply { text = "Перейти"; setOnClickListener { navigate(address.text.toString()) } }
        nav.addView(address, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(go)
        root.addView(nav, LinearLayout.LayoutParams(-1, -2))

        val bookmarkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 8, 4)
        }
        bookmarkSpinner = Spinner(this)
        bookmarkAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bookmarks)
        bookmarkSpinner.adapter = bookmarkAdapter
        val openBookmark = Button(this).apply {
            text = "Открыть"
            setOnClickListener { if (bookmarks.isNotEmpty()) navigate(bookmarks[bookmarkSpinner.selectedItemPosition]) }
        }
        val saveBookmark = Button(this).apply { text = "★"; setOnClickListener { saveBookmark(address.text.toString()) } }
        val deleteBookmark = Button(this).apply { text = "−"; setOnClickListener { deleteSelectedBookmark() } }
        bookmarkRow.addView(bookmarkSpinner, LinearLayout.LayoutParams(0, -2, 1f))
        bookmarkRow.addView(openBookmark)
        bookmarkRow.addView(saveBookmark)
        bookmarkRow.addView(deleteBookmark)
        root.addView(bookmarkRow, LinearLayout.LayoutParams(-1, -2))
        loadBookmarks()

        val statsHeader = Button(this).apply {
            text = "Статистика ▼"
            setOnClickListener {
                stats.visibility = if (stats.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                text = if (stats.visibility == View.VISIBLE) "Статистика ▲" else "Статистика ▼"
                updateStats()
            }
        }
        root.addView(statsHeader, LinearLayout.LayoutParams(-1, -2))
        stats = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setPadding(12, 6, 12, 8)
            setBackgroundColor(Color.rgb(18, 31, 26))
            textSize = 12f
        }
        root.addView(stats, LinearLayout.LayoutParams(-1, -2))

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 5, 8, 5)
            setBackgroundColor(Color.rgb(13, 26, 21))
        }
        badge = TextView(this).apply { text = "0 событий"; setTextColor(Color.WHITE); setPadding(8, 0, 8, 0) }
        val clear = Button(this).apply {
            text = "Очистить"
            setOnClickListener {
                archive.clear(); downloadingScripts.clear(); downloadingResources.clear(); scriptChunks.clear(); artifactChunks.clear(); updateBadge(); updateStats()
            }
        }
        val export = Button(this).apply { text = "Экспорт ZIP"; setOnClickListener { exportZip() } }
        controls.addView(badge, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(clear)
        controls.addView(export)
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
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
        userAgent = web.settings.userAgentString + " WebResearch/5"
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
                val temp = WebView(this@WebResearchActivity)
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
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                address.setText(url)
                addRecord(JSONObject().put("source", "navigation").put("time", System.currentTimeMillis()).put("url", url).put("page", url).put("method", "GET"))
                injectHooks(); capturePageSnapshot(); updateStats()
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                request?.let {
                    val url = it.url.toString(); val headers = HashMap(it.requestHeaders)
                    addRecord(JSONObject().put("source", "webview").put("time", System.currentTimeMillis()).put("method", it.method).put("url", url).put("headers", JSONObject(headers)))
                    if (it.method.equals("GET", true) && (url.startsWith("http://") || url.startsWith("https://"))) captureResource(url, headers)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
        navigate("https://evrasia.rest/")
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
            append("Куки текущей сессии: ${cookies.size}")
            if (cookies.isNotEmpty()) { append("\n"); append(cookies.joinToString("\n")) }
        }
    }

    private fun looksLikeJs(url: String): Boolean {
        val clean = url.substringBefore('#').substringBefore('?').lowercase(Locale.US)
        return clean.endsWith(".js") || clean.endsWith(".mjs")
    }

    private fun openConnection(url: String, headers: Map<String, String>): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true; c.connectTimeout = 15000; c.readTimeout = 45000; c.requestMethod = "GET"
        headers.forEach { (k, v) -> if (!k.equals("Host", true) && !k.equals("Content-Length", true)) try { c.setRequestProperty(k, v) } catch (_: Exception) {} }
        CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
        c.setRequestProperty("User-Agent", userAgent)
        return c
    }

    private fun captureResource(url: String, headers: Map<String, String>) {
        if (archive.resources.containsKey(url) || !downloadingResources.add(url)) return
        Thread {
            try {
                val c = openConnection(url, headers); val started = System.currentTimeMillis(); val status = c.responseCode
                val bytes = (if (status in 200..399) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                val responseHeaders = JSONObject(); c.headerFields.filterKeys { it != null }.forEach { (k, v) -> responseHeaders.put(k, v.joinToString(", ")) }
                val finalUrl = c.url.toString(); val contentType = c.contentType ?: ""
                archive.resources[url] = bytes
                archive.resourceMeta[url] = JSONObject().put("status", status).put("contentType", contentType).put("finalUrl", finalUrl).put("responseHeaders", responseHeaders)
                if (looksLikeJs(url) || contentType.contains("javascript", true)) archive.scripts[url] = bytes
                addRecord(JSONObject().put("source", "resource-copy").put("time", started).put("duration", System.currentTimeMillis() - started).put("method", "GET").put("url", url).put("status", status).put("responseHeaders", responseHeaders).put("mimeType", contentType).put("responseSize", bytes.size).put("redirectURL", if (finalUrl != url) finalUrl else ""))
                c.disconnect()
            } catch (e: Exception) {
                archive.resourceMeta[url] = JSONObject().put("error", e.toString())
                addRecord(JSONObject().put("source", "resource-copy").put("time", System.currentTimeMillis()).put("method", "GET").put("url", url).put("error", e.toString()))
            } finally { downloadingResources.remove(url); runOnUiThread { updateBadge() } }
        }.start()
    }

    private fun captureExternalScript(url: String, headers: Map<String, String>) {
        if (url.startsWith("blob:") || url.startsWith("data:") || archive.scripts.containsKey(url) || !downloadingScripts.add(url)) return
        Thread {
            try {
                val c = openConnection(url, headers); val status = c.responseCode
                val bytes = (if (status in 200..399) c.inputStream else c.errorStream)?.use { it.readBytes() }
                if (bytes != null) archive.scripts[url] = bytes else archive.scriptErrors[url] = "HTTP $status: empty body"
                c.disconnect()
            } catch (e: Exception) { archive.scriptErrors[url] = e.toString() }
            finally { downloadingScripts.remove(url); runOnUiThread { updateBadge() } }
        }.start()
    }

    private fun injectHooks() {
        val js = """
          (function(){
            if(window.__WR5)return; window.__WR5=true;
            const send=o=>{try{EvrasiaResearch.record(JSON.stringify(o))}catch(e){}};
            const chunk=(k,t,s)=>{t=String(t??'');let z=100000,n=Math.max(1,Math.ceil(t.length/z));for(let i=0;i<n;i++){try{s?EvrasiaResearch.scriptChunk(k,i,n,t.slice(i*z,(i+1)*z)):EvrasiaResearch.artifactChunk(k,i,n,t.slice(i*z,(i+1)*z))}catch(e){}}};
            const target=e=>{if(!e||e.nodeType!==1)return{};return{tag:(e.tagName||'').toLowerCase(),id:e.id||'',className:typeof e.className==='string'?e.className:'',name:e.name||'',type:e.type||'',role:e.getAttribute?.('role')||'',href:e.href||'',text:(e.innerText||e.textContent||'').trim().slice(0,300)}};
            ['click','change','submit'].forEach(type=>document.addEventListener(type,e=>send({source:'user-action',time:Date.now(),action:type,page:location.href,target:target(e.target)}),true));
            const HP=history.pushState.bind(history),HR=history.replaceState.bind(history);
            history.pushState=function(s,t,u){let r=HP(s,t,u);send({source:'history',time:Date.now(),action:'pushState',url:location.href,state:s});return r};
            history.replaceState=function(s,t,u){let r=HR(s,t,u);send({source:'history',time:Date.now(),action:'replaceState',url:location.href,state:s});return r};
            addEventListener('popstate',e=>send({source:'history',time:Date.now(),action:'popstate',url:location.href,state:e.state}));
            addEventListener('hashchange',e=>send({source:'history',time:Date.now(),action:'hashchange',url:location.href,oldURL:e.oldURL,newURL:e.newURL}));
            const F=window.fetch; window.fetch=async function(i,n){let u=typeof i==='string'?i:(i&&i.url)||'',m=(n&&n.method)||(i&&i.method)||'GET',b=n&&n.body,t=Date.now();try{let r=await F.apply(this,arguments),c=r.clone(),x='';try{x=await c.text()}catch(e){}let h={};r.headers.forEach((v,k)=>h[k]=v);send({source:'fetch',time:t,duration:Date.now()-t,method:m,url:String(u),requestBody:b==null?'':String(b),status:r.status,statusText:r.statusText,responseHeaders:h,responseBody:x,mimeType:r.headers.get('content-type')||''});return r}catch(e){send({source:'fetch',time:t,method:m,url:String(u),error:String(e)});throw e}};
            const O=XMLHttpRequest.prototype.open,S=XMLHttpRequest.prototype.send; XMLHttpRequest.prototype.open=function(m,u){this.__m=m;this.__u=u;return O.apply(this,arguments)}; XMLHttpRequest.prototype.send=function(b){let x=this,t=Date.now();x.addEventListener('loadend',()=>send({source:'xhr',time:t,duration:Date.now()-t,method:x.__m||'GET',url:String(x.__u||''),requestBody:b==null?'':String(b),status:x.status,statusText:x.statusText,responseHeadersRaw:x.getAllResponseHeaders(),responseBody:(x.responseType===''||x.responseType==='text')?x.responseText:'[non-text response]'}));return S.apply(this,arguments)};
            if(window.WebSocket){const W=window.WebSocket;window.WebSocket=class extends W{constructor(u,p){super(u,p);this.__u=String(u);send({source:'websocket-open',time:Date.now(),url:this.__u});this.addEventListener('message',e=>send({source:'websocket-receive',time:Date.now(),url:this.__u,data:typeof e.data==='string'?e.data:'[binary]'}))}send(d){send({source:'websocket-send',time:Date.now(),url:this.__u,data:typeof d==='string'?d:'[binary]'});return super.send(d)}}}
            if(window.EventSource){const E=window.EventSource;window.EventSource=class extends E{constructor(u,o){super(u,o);this.__u=String(u);send({source:'sse-open',time:Date.now(),url:this.__u});this.addEventListener('message',e=>send({source:'sse-message',time:Date.now(),url:this.__u,data:e.data,lastEventId:e.lastEventId||''}))}}}
            const scan=()=>Array.from(document.scripts).forEach((s,i)=>{if(s.src)EvrasiaResearch.externalScript(String(s.src));else if(s.textContent)chunk(location.href+'#inline-'+i,s.textContent,true)}); scan();
            new MutationObserver(ms=>{let data=ms.slice(0,100).map(m=>({type:m.type,target:target(m.target),added:m.addedNodes?.length||0,removed:m.removedNodes?.length||0,attribute:m.attributeName||''}));send({source:'dom-mutation',time:Date.now(),page:location.href,mutations:data});scan()}).observe(document.documentElement,{subtree:true,childList:true,attributes:true});
            addEventListener('error',e=>send({source:'js-error',time:Date.now(),message:e.message,url:e.filename||location.href,line:e.lineno||0,column:e.colno||0}));
            addEventListener('unhandledrejection',e=>send({source:'promise-rejection',time:Date.now(),message:String(e.reason)}));
            send({source:'hook',time:Date.now(),url:location.href,status:0});
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
            try{EvrasiaResearch.snapshot(JSON.stringify({time:Date.now(),url:location.href,title:document.title,cookie:document.cookie,nativeCookie:${JSONObject.quote(nativeCookies)},localStorage:store(localStorage),sessionStorage:store(sessionStorage),serviceWorkers:sw,cacheStorage:cacheNames,indexedDB:db,resources:resources,elements:elements,html:document.documentElement.outerHTML}))}catch(e){}
          })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun addRecord(record: JSONObject) { archive.addRecord(record); runOnUiThread { updateBadge() } }
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
                all.remove(key); runOnUiThread { updateBadge() }
            }
        } catch (e: Exception) { if (script) archive.scriptErrors[key] = e.toString() }
    }
}
