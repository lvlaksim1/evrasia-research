package ru.evrasia.research

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var badge: TextView
    private val archive = ResearchArchive()
    private val downloadingScripts = ConcurrentHashMap.newKeySet<String>()
    private val scriptChunks = ConcurrentHashMap<String, MutableMap<Int, String>>()

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 19, 16))
        }

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 8, 10, 8)
            setBackgroundColor(Color.argb(235, 13, 26, 21))
        }
        badge = TextView(this).apply {
            text = "0 событий"
            setTextColor(Color.WHITE)
            setPadding(12, 0, 12, 0)
        }
        val export = Button(this).apply {
            text = "Экспорт ZIP"
            setOnClickListener { exportZip() }
        }
        val clear = Button(this).apply {
            text = "Очистить"
            setOnClickListener {
                archive.clear()
                downloadingScripts.clear()
                scriptChunks.clear()
                updateBadge()
            }
        }
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
        web.settings.userAgentString = web.settings.userAgentString + " EvrasiaResearch/2"
        WebView.setWebContentsDebuggingEnabled(true)
        web.addJavascriptInterface(Bridge(this), "EvrasiaResearch")

        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                addRecord(JSONObject()
                    .put("source", "console")
                    .put("time", System.currentTimeMillis())
                    .put("level", message.messageLevel().name)
                    .put("message", message.message())
                    .put("sourceId", message.sourceId())
                    .put("line", message.lineNumber()))
                return super.onConsoleMessage(message)
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectHooks()
                capturePageSnapshot()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                request?.let {
                    val url = it.url.toString()
                    addRecord(JSONObject()
                        .put("source", "webview")
                        .put("time", System.currentTimeMillis())
                        .put("method", it.method)
                        .put("url", url)
                        .put("headers", JSONObject(it.requestHeaders)))
                    if (looksLikeJs(url)) captureExternalScript(url, it.requestHeaders)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        web.loadUrl("https://evrasia.rest/")
    }

    private fun looksLikeJs(url: String): Boolean {
        val clean = url.substringBefore('#').substringBefore('?').lowercase(Locale.US)
        return clean.endsWith(".js") || clean.endsWith(".mjs")
    }

    private fun captureExternalScript(url: String, headers: Map<String, String>) {
        if (archive.scripts.containsKey(url) || !downloadingScripts.add(url)) return
        Thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.requestMethod = "GET"
                headers.forEach { (k, v) ->
                    if (!k.equals("Host", true) && !k.equals("Content-Length", true)) {
                        try { c.setRequestProperty(k, v) } catch (_: Exception) {}
                    }
                }
                CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
                c.setRequestProperty("User-Agent", web.settings.userAgentString)
                val status = c.responseCode
                val input = if (status in 200..399) c.inputStream else c.errorStream
                if (input != null) {
                    val bytes = input.use { it.readBytes() }
                    archive.scripts[url] = bytes
                    addRecord(JSONObject()
                        .put("source", "script-archive")
                        .put("time", System.currentTimeMillis())
                        .put("method", "GET")
                        .put("url", url)
                        .put("status", status)
                        .put("bytes", bytes.size))
                } else {
                    archive.scriptErrors[url] = "HTTP $status: empty body"
                }
                c.disconnect()
            } catch (e: Exception) {
                archive.scriptErrors[url] = e.toString()
            } finally {
                downloadingScripts.remove(url)
                runOnUiThread { updateBadge() }
            }
        }.start()
    }

    private fun injectHooks() {
        val js = """
            (function(){
              if(window.__ER)return;window.__ER=true;
              const send=o=>{try{EvrasiaResearch.record(JSON.stringify(o))}catch(e){}};
              const hobj=h=>{let o={};try{new Headers(h||{}).forEach((v,k)=>o[k]=v)}catch(e){}return o};
              const f=window.fetch;
              window.fetch=async function(i,n){let u=typeof i==='string'?i:(i&&i.url)||'';let m=(n&&n.method)||(i&&i.method)||'GET';let b=n&&n.body;let t=Date.now();try{let r=await f.apply(this,arguments);let c=r.clone(),x='';try{x=await c.text()}catch(e){}let rh={};try{r.headers.forEach((v,k)=>rh[k]=v)}catch(e){}send({source:'fetch',time:t,duration:Date.now()-t,method:m,url:String(u),requestHeaders:hobj((n&&n.headers)||(i&&i.headers)),requestBody:b==null?'':String(b),status:r.status,statusText:r.statusText,responseHeaders:rh,responseBody:x});return r}catch(e){send({source:'fetch',time:t,duration:Date.now()-t,method:m,url:String(u),requestBody:b==null?'':String(b),error:String(e)});throw e}};
              const O=XMLHttpRequest.prototype.open,S=XMLHttpRequest.prototype.send,H=XMLHttpRequest.prototype.setRequestHeader;
              XMLHttpRequest.prototype.open=function(m,u){this.__erm=m;this.__eru=u;this.__erh={};return O.apply(this,arguments)};
              XMLHttpRequest.prototype.setRequestHeader=function(k,v){try{this.__erh[k]=v}catch(e){}return H.apply(this,arguments)};
              XMLHttpRequest.prototype.send=function(b){let x=this,t=Date.now();x.addEventListener('loadend',function(){let raw='';try{raw=x.getAllResponseHeaders()}catch(e){}send({source:'xhr',time:t,duration:Date.now()-t,method:x.__erm||'GET',url:String(x.__eru||''),requestHeaders:x.__erh||{},requestBody:b==null?'':String(b),status:x.status,statusText:x.statusText,responseHeadersRaw:raw,responseBody:x.responseType===''||x.responseType==='text'?x.responseText:'[non-text response]'})});return S.apply(this,arguments)};
              if(navigator.sendBeacon){const B=navigator.sendBeacon.bind(navigator);navigator.sendBeacon=function(u,d){send({source:'beacon',time:Date.now(),method:'POST',url:String(u),requestBody:d==null?'':String(d)});return B(u,d)}}
              addEventListener('error',e=>send({source:'js-error',time:Date.now(),message:e.message,url:e.filename||location.href,line:e.lineno||0,column:e.colno||0}));
              addEventListener('unhandledrejection',e=>send({source:'promise-rejection',time:Date.now(),message:String(e.reason)}));
              send({source:'hook',time:Date.now(),method:'',url:location.href,status:0,responseBody:'hook installed'});
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun capturePageSnapshot() {
        val js = """
            (async function(){
              function store(s){let o={};try{for(let i=0;i<s.length;i++){let k=s.key(i);o[k]=s.getItem(k)}}catch(e){o.__error=String(e)}return o}
              let sw=[];try{if(navigator.serviceWorker){let rs=await navigator.serviceWorker.getRegistrations();sw=rs.map(r=>({scope:r.scope,active:r.active&&r.active.scriptURL,waiting:r.waiting&&r.waiting.scriptURL,installing:r.installing&&r.installing.scriptURL}))}}catch(e){sw=[{error:String(e)}]}
              let caches=[];try{if(window.caches)caches=await window.caches.keys()}catch(e){caches=['ERROR: '+String(e)]}
              let db=[];try{if(indexedDB.databases)db=await indexedDB.databases()}catch(e){db=[{error:String(e)}]}
              let resources=[];try{resources=performance.getEntriesByType('resource').map(r=>({name:r.name,initiatorType:r.initiatorType,startTime:r.startTime,duration:r.duration,transferSize:r.transferSize,encodedBodySize:r.encodedBodySize,decodedBodySize:r.decodedBodySize}))}catch(e){}
              try{EvrasiaResearch.snapshot(JSON.stringify({time:Date.now(),url:location.href,title:document.title,cookie:document.cookie,localStorage:store(localStorage),sessionStorage:store(sessionStorage),serviceWorkers:sw,cacheStorage:caches,indexedDB:db,resources:resources,html:document.documentElement.outerHTML}))}catch(e){}
              Array.from(document.scripts).forEach((s,i)=>{if(s.src){try{EvrasiaResearch.externalScript(String(s.src))}catch(e){}}else if(s.textContent){let u=location.href+'#inline-script-'+i,c=s.textContent,step=120000,total=Math.ceil(c.length/step);for(let n=0;n<total;n++){try{EvrasiaResearch.scriptChunk(u,n,total,c.slice(n*step,(n+1)*step))}catch(e){}}}});
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun addRecord(record: JSONObject) {
        archive.addRecord(record)
        runOnUiThread { updateBadge() }
    }

    private fun updateBadge() {
        badge.text = "${archive.records.length()} событий · ${archive.scripts.size} JS"
    }

    private fun exportZip() {
        capturePageSnapshot()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "evrasia-research-$stamp.zip")
        }
        startActivityForResult(intent, 501)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 501 && resultCode == RESULT_OK) {
            data?.data?.let { uri -> contentResolver.openOutputStream(uri)?.use { archive.writeZip(it, web.url ?: "") } }
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    inner class Bridge(private val context: Context) {
        @JavascriptInterface fun record(json: String) { try { addRecord(JSONObject(json)) } catch (_: Exception) {} }
        @JavascriptInterface fun snapshot(json: String) { try { archive.snapshot = JSONObject(json) } catch (_: Exception) {} }
        @JavascriptInterface fun externalScript(url: String) { if (url.isNotBlank()) captureExternalScript(url, emptyMap()) }
        @JavascriptInterface fun scriptChunk(url: String, index: Int, total: Int, chunk: String) {
            try {
                val map = scriptChunks.getOrPut(url) { ConcurrentHashMap() }
                map[index] = chunk
                if (map.size == total) {
                    val out = StringBuilder()
                    for (i in 0 until total) out.append(map[i] ?: "")
                    archive.scripts[url] = out.toString().toByteArray(Charsets.UTF_8)
                    scriptChunks.remove(url)
                    runOnUiThread { updateBadge() }
                }
            } catch (e: Exception) { archive.scriptErrors[url] = e.toString() }
        }
    }
}
