package ru.evrasia.research

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
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
    private val downloadingResources = ConcurrentHashMap.newKeySet<String>()
    private val scriptChunks = ConcurrentHashMap<String, MutableMap<Int, String>>()
    private val artifactChunks = ConcurrentHashMap<String, MutableMap<Int, String>>()
    private lateinit var userAgent: String

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(8, 19, 16)) }
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(10, 8, 10, 8); setBackgroundColor(Color.argb(235, 13, 26, 21)) }
        badge = TextView(this).apply { text = "0 событий"; setTextColor(Color.WHITE); setPadding(12, 0, 12, 0) }
        val export = Button(this).apply { text = "Экспорт ZIP"; setOnClickListener { exportZip() } }
        val clear = Button(this).apply {
            text = "Очистить"
            setOnClickListener {
                archive.clear(); downloadingScripts.clear(); downloadingResources.clear(); scriptChunks.clear(); artifactChunks.clear(); updateBadge()
            }
        }
        controls.addView(badge, LinearLayout.LayoutParams(0, -2, 1f)); controls.addView(clear); controls.addView(export)
        root.addView(controls, LinearLayout.LayoutParams(-1, -2)); setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        ViewCompat.requestApplyInsets(root)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        userAgent = web.settings.userAgentString + " EvrasiaResearch/3"
        web.settings.userAgentString = userAgent
        WebView.setWebContentsDebuggingEnabled(true)
        web.addJavascriptInterface(Bridge(this), "EvrasiaResearch")

        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                addRecord(JSONObject().put("source", "console").put("time", System.currentTimeMillis()).put("level", message.messageLevel().name).put("message", message.message()).put("sourceId", message.sourceId()).put("line", message.lineNumber()))
                return super.onConsoleMessage(message)
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
            override fun onPageFinished(view: WebView, url: String) { super.onPageFinished(view, url); injectHooks(); capturePageSnapshot() }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                request?.let {
                    val url = it.url.toString()
                    val headers = HashMap(it.requestHeaders)
                    addRecord(JSONObject().put("source", "webview").put("time", System.currentTimeMillis()).put("method", it.method).put("url", url).put("headers", JSONObject(headers)))
                    if (it.method.equals("GET", true) && (url.startsWith("http://") || url.startsWith("https://"))) captureResource(url, headers)
                    if (looksLikeJs(url)) captureExternalScript(url, headers)
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
                val c = openConnection(url, headers)
                val started = System.currentTimeMillis()
                val status = c.responseCode
                val input = if (status in 200..399) c.inputStream else c.errorStream
                val bytes = input?.use { it.readBytes() } ?: ByteArray(0)
                val responseHeaders = JSONObject()
                c.headerFields.filterKeys { it != null }.forEach { (k, v) -> responseHeaders.put(k, v.joinToString(", ")) }
                val meta = JSONObject().put("status", status).put("contentType", c.contentType ?: "").put("finalUrl", c.url.toString()).put("responseHeaders", responseHeaders)
                archive.resources[url] = bytes; archive.resourceMeta[url] = meta
                if (looksLikeJs(url)) archive.scripts[url] = bytes
                addRecord(JSONObject().put("source", "resource-copy").put("time", started).put("duration", System.currentTimeMillis() - started).put("method", "GET").put("url", url).put("status", status).put("responseHeaders", responseHeaders).put("mimeType", c.contentType ?: "").put("responseSize", bytes.size).put("redirectURL", if (c.url.toString() != url) c.url.toString() else ""))
                c.disconnect()
            } catch (e: Exception) {
                archive.resourceMeta[url] = JSONObject().put("error", e.toString())
                addRecord(JSONObject().put("source", "resource-copy").put("time", System.currentTimeMillis()).put("method", "GET").put("url", url).put("error", e.toString()))
            } finally { downloadingResources.remove(url); runOnUiThread { updateBadge() } }
        }.start()
    }

    private fun captureExternalScript(url: String, headers: Map<String, String>) {
        if (url.startsWith("blob:") || url.startsWith("data:")) return
        if (archive.scripts.containsKey(url) || !downloadingScripts.add(url)) return
        Thread {
            try {
                val c = openConnection(url, headers); val status = c.responseCode; val input = if (status in 200..399) c.inputStream else c.errorStream
                if (input != null) { val bytes = input.use { it.readBytes() }; archive.scripts[url] = bytes; addRecord(JSONObject().put("source", "script-archive").put("time", System.currentTimeMillis()).put("method", "GET").put("url", url).put("status", status).put("bytes", bytes.size)) }
                else archive.scriptErrors[url] = "HTTP $status: empty body"
                c.disconnect()
            } catch (e: Exception) { archive.scriptErrors[url] = e.toString() }
            finally { downloadingScripts.remove(url); runOnUiThread { updateBadge() } }
        }.start()
    }

    private fun injectHooks() {
        val js = """
            (function(){
              if(window.__ER)return;window.__ER=true;
              const send=o=>{try{EvrasiaResearch.record(JSON.stringify(o))}catch(e){}};
              const hobj=h=>{let o={};try{new Headers(h||{}).forEach((v,k)=>o[k]=v)}catch(e){}return o};
              const chunks=(key,text,script)=>{text=String(text==null?'':text);let step=120000,total=Math.max(1,Math.ceil(text.length/step));for(let n=0;n<total;n++){try{script?EvrasiaResearch.scriptChunk(key,n,total,text.slice(n*step,(n+1)*step)):EvrasiaResearch.artifactChunk(key,n,total,text.slice(n*step,(n+1)*step))}catch(e){}}};
              const saveScript=async u=>{try{let s=String(u||'');if(!s)return;if(s.startsWith('blob:')||s.startsWith('data:')){let r=await fetch(s),t=await r.text();chunks(s,t,true)}else EvrasiaResearch.externalScript(s)}catch(e){send({source:'script-capture-error',time:Date.now(),url:String(u),error:String(e)})}};

              const f=window.fetch;
              window.fetch=async function(i,n){let u=typeof i==='string'?i:(i&&i.url)||'';let m=(n&&n.method)||(i&&i.method)||'GET';let b=n&&n.body;let t=Date.now();try{let r=await f.apply(this,arguments);let c=r.clone(),x='';try{x=await c.text()}catch(e){}let rh={};try{r.headers.forEach((v,k)=>rh[k]=v)}catch(e){}send({source:'fetch',time:t,duration:Date.now()-t,method:m,url:String(u),requestHeaders:hobj((n&&n.headers)||(i&&i.headers)),requestBody:b==null?'':String(b),status:r.status,statusText:r.statusText,responseHeaders:rh,responseBody:x,mimeType:r.headers.get('content-type')||''});return r}catch(e){send({source:'fetch',time:t,duration:Date.now()-t,method:m,url:String(u),requestBody:b==null?'':String(b),error:String(e)});throw e}};

              const O=XMLHttpRequest.prototype.open,S=XMLHttpRequest.prototype.send,H=XMLHttpRequest.prototype.setRequestHeader;
              XMLHttpRequest.prototype.open=function(m,u){this.__erm=m;this.__eru=u;this.__erh={};return O.apply(this,arguments)};
              XMLHttpRequest.prototype.setRequestHeader=function(k,v){try{this.__erh[k]=v}catch(e){}return H.apply(this,arguments)};
              XMLHttpRequest.prototype.send=function(b){let x=this,t=Date.now();x.addEventListener('loadend',function(){let raw='';try{raw=x.getAllResponseHeaders()}catch(e){}let body='';try{body=x.responseType===''||x.responseType==='text'?x.responseText:'[non-text response]'}catch(e){}send({source:'xhr',time:t,duration:Date.now()-t,method:x.__erm||'GET',url:String(x.__eru||''),requestHeaders:x.__erh||{},requestBody:b==null?'':String(b),status:x.status,statusText:x.statusText,responseHeadersRaw:raw,responseBody:body})});return S.apply(this,arguments)};

              if(navigator.sendBeacon){const B=navigator.sendBeacon.bind(navigator);navigator.sendBeacon=function(u,d){send({source:'beacon',time:Date.now(),method:'POST',url:String(u),requestBody:d==null?'':String(d)});return B(u,d)}}

              if(window.WebSocket){const W=window.WebSocket;window.WebSocket=class extends W{constructor(u,p){super(u,p);this.__eru=String(u);send({source:'websocket-open',time:Date.now(),method:'WS',url:this.__eru});this.addEventListener('open',()=>send({source:'websocket-state',time:Date.now(),method:'WS',url:this.__eru,state:'open',protocol:this.protocol||''}));this.addEventListener('message',e=>send({source:'websocket-receive',time:Date.now(),method:'WS',url:this.__eru,data:typeof e.data==='string'?e.data:'[binary message]'}));this.addEventListener('close',e=>send({source:'websocket-state',time:Date.now(),method:'WS',url:this.__eru,state:'close',code:e.code,reason:e.reason||''}));this.addEventListener('error',()=>send({source:'websocket-state',time:Date.now(),method:'WS',url:this.__eru,state:'error'}))}send(d){send({source:'websocket-send',time:Date.now(),method:'WS',url:this.__eru,data:typeof d==='string'?d:'[binary message]'});return super.send(d)}};window.WebSocket.CONNECTING=W.CONNECTING;window.WebSocket.OPEN=W.OPEN;window.WebSocket.CLOSING=W.CLOSING;window.WebSocket.CLOSED=W.CLOSED;}

              if(window.EventSource){const E=window.EventSource;window.EventSource=class extends E{constructor(u,o){super(u,o);this.__eru=String(u);send({source:'sse-open',time:Date.now(),method:'GET',url:this.__eru});this.addEventListener('open',()=>send({source:'sse-state',time:Date.now(),method:'GET',url:this.__eru,state:'open'}));this.addEventListener('message',e=>send({source:'sse-message',time:Date.now(),method:'GET',url:this.__eru,event:e.type,data:e.data,lastEventId:e.lastEventId||''}));this.addEventListener('error',()=>send({source:'sse-state',time:Date.now(),method:'GET',url:this.__eru,state:'error'}))}};}

              if(window.Worker){const W=window.Worker;window.Worker=function(u,o){saveScript(u);send({source:'worker-create',time:Date.now(),method:'GET',url:String(u)});return new W(u,o)};window.Worker.prototype=W.prototype;}
              if(window.SharedWorker){const W=window.SharedWorker;window.SharedWorker=function(u,o){saveScript(u);send({source:'shared-worker-create',time:Date.now(),method:'GET',url:String(u)});return new W(u,o)};window.SharedWorker.prototype=W.prototype;}

              const scanScripts=()=>Array.from(document.scripts).forEach((s,i)=>{if(s.src)saveScript(s.src);else if(s.textContent)chunks(location.href+'#inline-script-'+i,s.textContent,true)});
              scanScripts();new MutationObserver(ms=>ms.forEach(m=>m.addedNodes&&m.addedNodes.forEach(n=>{if(n&&n.tagName==='SCRIPT'){if(n.src)saveScript(n.src);else if(n.textContent)chunks(location.href+'#dynamic-inline-'+Date.now(),n.textContent,true)}if(n&&n.querySelectorAll)n.querySelectorAll('script').forEach(save=>save.src?saveScript(save.src):save.textContent&&chunks(location.href+'#dynamic-inline-'+Date.now(),save.textContent,true))}))).observe(document.documentElement,{childList:true,subtree:true});

              addEventListener('error',e=>send({source:'js-error',time:Date.now(),message:e.message,url:e.filename||location.href,line:e.lineno||0,column:e.colno||0}));
              addEventListener('unhandledrejection',e=>send({source:'promise-rejection',time:Date.now(),message:String(e.reason)}));
              send({source:'hook',time:Date.now(),method:'',url:location.href,status:0,responseBody:'hook installed'});
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun capturePageSnapshot() {
        val nativeCookies = CookieManager.getInstance().getCookie(web.url ?: "") ?: ""
        val js = """
            (async function(){
              const chunks=(key,text)=>{text=String(text==null?'':text);let step=120000,total=Math.max(1,Math.ceil(text.length/step));for(let n=0;n<total;n++){try{EvrasiaResearch.artifactChunk(key,n,total,text.slice(n*step,(n+1)*step))}catch(e){}}};
              function store(s){let o={};try{for(let i=0;i<s.length;i++){let k=s.key(i);o[k]=s.getItem(k)}}catch(e){o.__error=String(e)}return o}
              let sw=[];try{if(navigator.serviceWorker){let rs=await navigator.serviceWorker.getRegistrations();sw=rs.map(r=>({scope:r.scope,active:r.active&&r.active.scriptURL,waiting:r.waiting&&r.waiting.scriptURL,installing:r.installing&&r.installing.scriptURL}));sw.forEach(r=>[r.active,r.waiting,r.installing].filter(Boolean).forEach(u=>{try{EvrasiaResearch.externalScript(String(u))}catch(e){}}))}}catch(e){sw=[{error:String(e)}]}
              let caches=[];try{if(window.caches){for(const name of await window.caches.keys()){let c=await window.caches.open(name),items=[];for(const req of await c.keys()){try{let res=await c.match(req),text='';try{text=await res.clone().text()}catch(e){}items.push({request:{url:req.url,method:req.method,headers:Object.fromEntries(req.headers.entries())},response:{status:res.status,statusText:res.statusText,headers:Object.fromEntries(res.headers.entries()),body:text}})}catch(e){items.push({url:req.url,error:String(e)})}}caches.push({name,items})}chunks('cache-storage.json',JSON.stringify(caches))}}catch(e){chunks('cache-storage.json',JSON.stringify([{error:String(e)}]))}
              let dbMeta=[];try{if(indexedDB.databases){let dbs=await indexedDB.databases();for(const d of dbs){if(!d.name)continue;await new Promise(resolve=>{let q=indexedDB.open(d.name);q.onerror=()=>{dbMeta.push({name:d.name,error:String(q.error)});resolve()};q.onsuccess=()=>{let db=q.result,names=Array.from(db.objectStoreNames),out={name:d.name,version:db.version,stores:{}};if(!names.length){dbMeta.push(out);db.close();resolve();return}let tx=db.transaction(names,'readonly'),left=names.length;names.forEach(name=>{let vals=[];try{let st=tx.objectStore(name),cur=st.openCursor();cur.onerror=()=>{out.stores[name]={error:String(cur.error)};if(--left===0){dbMeta.push(out);db.close();resolve()}};cur.onsuccess=e=>{let c=e.target.result;if(c){let v;try{v=JSON.parse(JSON.stringify(c.value,(k,x)=>x instanceof Blob?{__blob:true,type:x.type,size:x.size}:x))}catch(err){v=String(c.value)}vals.push({key:c.key,value:v});c.continue()}else{out.stores[name]=vals;if(--left===0){dbMeta.push(out);db.close();resolve()}}}}catch(err){out.stores[name]={error:String(err)};if(--left===0){dbMeta.push(out);db.close();resolve()}}})}}) }chunks('indexeddb.json',JSON.stringify(dbMeta))}}catch(e){chunks('indexeddb.json',JSON.stringify([{error:String(e)}]))}
              let resources=[];try{resources=performance.getEntriesByType('resource').map(r=>({name:r.name,initiatorType:r.initiatorType,startTime:r.startTime,duration:r.duration,transferSize:r.transferSize,encodedBodySize:r.encodedBodySize,decodedBodySize:r.decodedBodySize,nextHopProtocol:r.nextHopProtocol||''}))}catch(e){}
              let snap={time:Date.now(),url:location.href,title:document.title,cookie:document.cookie,localStorage:store(localStorage),sessionStorage:store(sessionStorage),serviceWorkers:sw,resources:resources,html:document.documentElement.outerHTML};
              try{EvrasiaResearch.snapshot(JSON.stringify(snap))}catch(e){}
              Array.from(document.scripts).forEach((s,i)=>{if(s.src){try{EvrasiaResearch.externalScript(String(s.src))}catch(e){}}else if(s.textContent){let u=location.href+'#inline-script-'+i,c=s.textContent,step=120000,total=Math.ceil(c.length/step);for(let n=0;n<total;n++){try{EvrasiaResearch.scriptChunk(u,n,total,c.slice(n*step,(n+1)*step))}catch(e){}}}});
            })();
        """.trimIndent()
        archive.snapshot.put("nativeCookies", nativeCookies)
        web.evaluateJavascript(js, null)
    }

    private fun addRecord(record: JSONObject) { archive.addRecord(record); runOnUiThread { updateBadge() } }
    private fun updateBadge() { badge.text = "${archive.records.length()} событий · ${archive.scripts.size} JS · ${archive.resources.size} ресурсов" }

    private fun exportZip() {
        capturePageSnapshot()
        archive.snapshot.put("nativeCookies", CookieManager.getInstance().getCookie(web.url ?: "") ?: "")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"; putExtra(Intent.EXTRA_TITLE, "evrasia-research-$stamp.zip") }
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
        @JavascriptInterface fun snapshot(json: String) { try { val oldNative = archive.snapshot.optString("nativeCookies", ""); archive.snapshot = JSONObject(json); archive.snapshot.put("nativeCookies", oldNative) } catch (_: Exception) {} }
        @JavascriptInterface fun externalScript(url: String) { if (url.isNotBlank()) captureExternalScript(url, emptyMap()) }
        @JavascriptInterface fun scriptChunk(url: String, index: Int, total: Int, chunk: String) { assembleChunk(scriptChunks, url, index, total, chunk) { key, text -> archive.scripts[key] = text.toByteArray(Charsets.UTF_8); runOnUiThread { updateBadge() } } }
        @JavascriptInterface fun artifactChunk(key: String, index: Int, total: Int, chunk: String) { assembleChunk(artifactChunks, key, index, total, chunk) { name, text -> archive.extraArtifacts[name] = text.toByteArray(Charsets.UTF_8) } }
    }

    private fun assembleChunk(store: ConcurrentHashMap<String, MutableMap<Int, String>>, key: String, index: Int, total: Int, chunk: String, done: (String, String) -> Unit) {
        try {
            val map = store.getOrPut(key) { ConcurrentHashMap() }; map[index] = chunk
            if (map.size == total) { val out = StringBuilder(); for (i in 0 until total) out.append(map[i] ?: ""); store.remove(key); done(key, out.toString()) }
        } catch (_: Exception) {}
    }
}
