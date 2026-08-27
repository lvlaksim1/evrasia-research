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
    private val downloadingResources = ConcurrentHashMap.newKeySet<String>()
    private val scriptChunks = ConcurrentHashMap<String, MutableMap<Int, String>>()
    private val artifactChunks = ConcurrentHashMap<String, MutableMap<Int, String>>()
    private lateinit var userAgent: String

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
        val export = Button(this).apply { text = "Экспорт ZIP"; setOnClickListener { exportZip() } }
        val clear = Button(this).apply {
            text = "Очистить"
            setOnClickListener {
                archive.clear()
                downloadingScripts.clear()
                downloadingResources.clear()
                scriptChunks.clear()
                artifactChunks.clear()
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
        userAgent = web.settings.userAgentString + " EvrasiaResearch/4"
        web.settings.userAgentString = userAgent
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
                addRecord(JSONObject().put("source", "navigation").put("time", System.currentTimeMillis()).put("url", url).put("page", url).put("method", "GET"))
                injectHooks()
                capturePageSnapshot()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                request?.let {
                    val url = it.url.toString()
                    val headers = HashMap(it.requestHeaders)
                    addRecord(JSONObject()
                        .put("source", "webview")
                        .put("time", System.currentTimeMillis())
                        .put("method", it.method)
                        .put("url", url)
                        .put("headers", JSONObject(headers)))
                    if (it.method.equals("GET", true) && (url.startsWith("http://") || url.startsWith("https://"))) captureResource(url, headers)
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
        c.instanceFollowRedirects = true
        c.connectTimeout = 15000
        c.readTimeout = 45000
        c.requestMethod = "GET"
        headers.forEach { (k, v) ->
            if (!k.equals("Host", true) && !k.equals("Content-Length", true)) {
                try { c.setRequestProperty(k, v) } catch (_: Exception) {}
            }
        }
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
                val finalUrl = c.url.toString()
                val contentType = c.contentType ?: ""
                val meta = JSONObject()
                    .put("status", status)
                    .put("contentType", contentType)
                    .put("finalUrl", finalUrl)
                    .put("responseHeaders", responseHeaders)
                archive.resources[url] = bytes
                archive.resourceMeta[url] = meta
                if (looksLikeJs(url) || contentType.contains("javascript", true)) {
                    archive.scripts[url] = bytes
                    discoverSourceMap(url, bytes)
                }
                addRecord(JSONObject()
                    .put("source", "resource-copy")
                    .put("time", started)
                    .put("duration", System.currentTimeMillis() - started)
                    .put("method", "GET")
                    .put("url", url)
                    .put("status", status)
                    .put("responseHeaders", responseHeaders)
                    .put("mimeType", contentType)
                    .put("responseSize", bytes.size)
                    .put("redirectURL", if (finalUrl != url) finalUrl else ""))
                c.disconnect()
            } catch (e: Exception) {
                archive.resourceMeta[url] = JSONObject().put("error", e.toString())
                addRecord(JSONObject().put("source", "resource-copy").put("time", System.currentTimeMillis()).put("method", "GET").put("url", url).put("error", e.toString()))
            } finally {
                downloadingResources.remove(url)
                runOnUiThread { updateBadge() }
            }
        }.start()
    }

    private fun discoverSourceMap(scriptUrl: String, bytes: ByteArray) {
        try {
            if (bytes.size > 20_000_000) return
            val text = bytes.toString(Charsets.UTF_8)
            val match = Regex("(?m)[#@]\\s*sourceMappingURL\\s*=\\s*([^\\s*]+)").findAll(text).lastOrNull() ?: return
            val raw = match.groupValues[1].trim().trim('"', '\'')
            if (raw.startsWith("data:")) {
                archive.extraArtifacts["source-maps/${scriptUrl.hashCode()}-inline.txt"] = raw.toByteArray(Charsets.UTF_8)
            } else {
                val resolved = URL(URL(scriptUrl), raw).toString()
                captureResource(resolved, emptyMap())
                addRecord(JSONObject().put("source", "source-map").put("time", System.currentTimeMillis()).put("method", "GET").put("url", resolved).put("script", scriptUrl))
            }
        } catch (_: Exception) {}
    }

    private fun captureExternalScript(url: String, headers: Map<String, String>) {
        if (url.startsWith("blob:") || url.startsWith("data:")) return
        if (archive.scripts.containsKey(url) || !downloadingScripts.add(url)) return
        Thread {
            try {
                val c = openConnection(url, headers)
                val status = c.responseCode
                val input = if (status in 200..399) c.inputStream else c.errorStream
                if (input != null) {
                    val bytes = input.use { it.readBytes() }
                    archive.scripts[url] = bytes
                    archive.resources.putIfAbsent(url, bytes)
                    discoverSourceMap(url, bytes)
                    addRecord(JSONObject().put("source", "script-archive").put("time", System.currentTimeMillis()).put("method", "GET").put("url", url).put("status", status).put("bytes", bytes.size))
                } else archive.scriptErrors[url] = "HTTP $status: empty body"
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
              if(window.__ER4)return;window.__ER4=true;
              const send=o=>{try{EvrasiaResearch.record(JSON.stringify(o))}catch(e){}};
              const chunks=(key,text,script)=>{text=String(text==null?'':text);let step=120000,total=Math.max(1,Math.ceil(text.length/step));for(let n=0;n<total;n++){try{script?EvrasiaResearch.scriptChunk(key,n,total,text.slice(n*step,(n+1)*step)):EvrasiaResearch.artifactChunk(key,n,total,text.slice(n*step,(n+1)*step))}catch(e){}}};
              const hobj=h=>{let o={};try{new Headers(h||{}).forEach((v,k)=>o[k]=v)}catch(e){}return o};
              const pathOf=e=>{if(!e||e.nodeType!==1)return'';let a=[];while(e&&e.nodeType===1&&a.length<8){let s=e.tagName.toLowerCase();if(e.id){s+='#'+e.id;a.unshift(s);break}let p=e.parentElement;if(p){let sib=Array.from(p.children).filter(x=>x.tagName===e.tagName);if(sib.length>1)s+=':nth-of-type('+(sib.indexOf(e)+1)+')'}a.unshift(s);e=p}return a.join('>')};
              const target=e=>{if(!e||e.nodeType!==1)return{};let d={};try{for(const a of e.attributes||[])if(a.name.startsWith('data-'))d[a.name]=a.value}catch(x){}return{tag:(e.tagName||'').toLowerCase(),id:e.id||'',className:typeof e.className==='string'?e.className:'',name:e.name||'',type:e.type||'',role:e.getAttribute&&e.getAttribute('role')||'',href:e.href||'',text:(e.innerText||e.textContent||'').trim().slice(0,300),path:pathOf(e),data:d}};
              const safeValue=e=>{try{if((e.type||'').toLowerCase()==='password')return'[password-not-captured]';if(e.type==='file')return Array.from(e.files||[]).map(f=>({name:f.name,type:f.type,size:f.size}));if(e.type==='checkbox'||e.type==='radio')return e.checked;return String(e.value||'').slice(0,10000)}catch(x){return''}};
              const bodyInfo=async b=>{try{if(b==null)return{body:'',structured:null};if(b instanceof FormData){let o=[];for(const [k,v] of b.entries())o.push([k,v instanceof File?{file:true,name:v.name,type:v.type,size:v.size}:String(v)]);return{body:'[FormData]',structured:{type:'FormData',entries:o}}}if(b instanceof URLSearchParams)return{body:b.toString(),structured:{type:'URLSearchParams',entries:Array.from(b.entries())}};if(b instanceof Blob)return{body:'[Blob '+b.size+' bytes]',structured:{type:'Blob',mimeType:b.type,size:b.size}};if(b instanceof ArrayBuffer)return{body:'[ArrayBuffer '+b.byteLength+' bytes]',structured:{type:'ArrayBuffer',size:b.byteLength}};let s=String(b);let structured=null;try{structured=JSON.parse(s)}catch(e){}return{body:s,structured:structured}}catch(e){return{body:String(b),structured:{error:String(e)}}}};
              const graphqlOf=x=>{try{let o=x;if(typeof o==='string')o=JSON.parse(o);if(o&&typeof o==='object'&&(o.query||o.operationName))return{operationName:o.operationName||'',variables:o.variables||null,query:String(o.query||'').slice(0,20000)}}catch(e){}return null};
              const saveScript=async u=>{try{let s=String(u||'');if(!s)return;if(s.startsWith('blob:')||s.startsWith('data:')){let r=await fetch(s),t=await r.text();chunks(s,t,true)}else EvrasiaResearch.externalScript(s)}catch(e){send({source:'script-capture-error',time:Date.now(),url:String(u),error:String(e)})}};

              ['click','input','change','submit'].forEach(type=>document.addEventListener(type,e=>{let el=e.target;let rec={source:type==='submit'?'form-submit':'user-action',time:Date.now(),action:type,page:location.href,target:target(el)};if(type==='input'||type==='change')rec.value=safeValue(el);if(type==='submit'){try{rec.form=Array.from(new FormData(el).entries()).map(([k,v])=>[k,v instanceof File?{file:true,name:v.name,type:v.type,size:v.size}:String(v)])}catch(x){}}send(rec)},true));
              document.addEventListener('keydown',e=>{if(['Enter','Escape','Tab'].includes(e.key))send({source:'user-action',time:Date.now(),action:'keydown',key:e.key,page:location.href,target:target(e.target)})},true);

              const HP=history.pushState.bind(history),HR=history.replaceState.bind(history);
              history.pushState=function(s,t,u){let r=HP(s,t,u);send({source:'history',time:Date.now(),action:'pushState',page:location.href,url:location.href,state:s});setTimeout(()=>EvrasiaResearch.requestSnapshot(),0);return r};
              history.replaceState=function(s,t,u){let r=HR(s,t,u);send({source:'history',time:Date.now(),action:'replaceState',page:location.href,url:location.href,state:s});return r};
              addEventListener('popstate',e=>send({source:'history',time:Date.now(),action:'popstate',page:location.href,url:location.href,state:e.state}));
              addEventListener('hashchange',e=>send({source:'history',time:Date.now(),action:'hashchange',page:location.href,url:location.href,oldURL:e.oldURL,newURL:e.newURL}));

              const f=window.fetch;
              window.fetch=async function(i,n){let u=typeof i==='string'?i:(i&&i.url)||'',m=(n&&n.method)||(i&&i.method)||'GET',b=n&&n.body,t=Date.now(),bi=await bodyInfo(b);try{let r=await f.apply(this,arguments),c=r.clone(),x='';try{x=await c.text()}catch(e){}let rh={};try{r.headers.forEach((v,k)=>rh[k]=v)}catch(e){}send({source:'fetch',time:t,duration:Date.now()-t,method:m,url:String(u),page:location.href,requestHeaders:hobj((n&&n.headers)||(i&&i.headers)),requestBody:bi.body,requestStructured:bi.structured,graphql:graphqlOf(bi.structured||bi.body),status:r.status,statusText:r.statusText,responseHeaders:rh,responseBody:x,mimeType:r.headers.get('content-type')||''});return r}catch(e){send({source:'fetch',time:t,duration:Date.now()-t,method:m,url:String(u),page:location.href,requestBody:bi.body,requestStructured:bi.structured,graphql:graphqlOf(bi.structured||bi.body),error:String(e)});throw e}};

              const O=XMLHttpRequest.prototype.open,S=XMLHttpRequest.prototype.send,H=XMLHttpRequest.prototype.setRequestHeader;
              XMLHttpRequest.prototype.open=function(m,u){this.__erm=m;this.__eru=u;this.__erh={};return O.apply(this,arguments)};
              XMLHttpRequest.prototype.setRequestHeader=function(k,v){try{this.__erh[k]=v}catch(e){}return H.apply(this,arguments)};
              XMLHttpRequest.prototype.send=function(b){let x=this,t=Date.now();bodyInfo(b).then(bi=>x.__erb=bi);x.addEventListener('loadend',function(){let raw='';try{raw=x.getAllResponseHeaders()}catch(e){}let body='';try{body=x.responseType===''||x.responseType==='text'?x.responseText:'[non-text response]'}catch(e){}let bi=x.__erb||{body:b==null?'':String(b),structured:null};send({source:'xhr',time:t,duration:Date.now()-t,method:x.__erm||'GET',url:String(x.__eru||''),page:location.href,requestHeaders:x.__erh||{},requestBody:bi.body,requestStructured:bi.structured,graphql:graphqlOf(bi.structured||bi.body),status:x.status,statusText:x.statusText,responseHeadersRaw:raw,responseBody:body})});return S.apply(this,arguments)};

              if(navigator.sendBeacon){const B=navigator.sendBeacon.bind(navigator);navigator.sendBeacon=function(u,d){bodyInfo(d).then(bi=>send({source:'beacon',time:Date.now(),method:'POST',url:String(u),page:location.href,requestBody:bi.body,requestStructured:bi.structured}));return B(u,d)}}

              if(window.WebSocket){const W=window.WebSocket;window.WebSocket=class extends W{constructor(u,p){super(u,p);this.__eru=String(u);send({source:'websocket-open',time:Date.now(),method:'WS',url:this.__eru,page:location.href});this.addEventListener('open',()=>send({source:'websocket-state',time:Date.now(),method:'WS',url:this.__eru,state:'open',protocol:this.protocol||''}));this.addEventListener('message',e=>send({source:'websocket-receive',time:Date.now(),method:'WS',url:this.__eru,data:typeof e.data==='string'?e.data:'[binary message]'}));this.addEventListener('close',e=>send({source:'websocket-state',time:Date.now(),method:'WS',url:this.__eru,state:'close',code:e.code,reason:e.reason||''}));this.addEventListener('error',()=>send({source:'websocket-state',time:Date.now(),method:'WS',url:this.__eru,state:'error'}))}send(d){send({source:'websocket-send',time:Date.now(),method:'WS',url:this.__eru,data:typeof d==='string'?d:'[binary message]'});return super.send(d)}};window.WebSocket.CONNECTING=W.CONNECTING;window.WebSocket.OPEN=W.OPEN;window.WebSocket.CLOSING=W.CLOSING;window.WebSocket.CLOSED=W.CLOSED;}
              if(window.EventSource){const E=window.EventSource;window.EventSource=class extends E{constructor(u,o){super(u,o);this.__eru=String(u);send({source:'sse-open',time:Date.now(),method:'GET',url:this.__eru,page:location.href});this.addEventListener('open',()=>send({source:'sse-state',time:Date.now(),method:'GET',url:this.__eru,state:'open'}));this.addEventListener('message',e=>send({source:'sse-message',time:Date.now(),method:'GET',url:this.__eru,event:e.type,data:e.data,lastEventId:e.lastEventId||''}));this.addEventListener('error',()=>send({source:'sse-state',time:Date.now(),method:'GET',url:this.__eru,state:'error'}))}};}

              if(window.Worker){const W=window.Worker;window.Worker=function(u,o){saveScript(u);send({source:'worker-create',time:Date.now(),method:'GET',url:String(u)});return new W(u,o)};window.Worker.prototype=W.prototype;}
              if(window.SharedWorker){const W=window.SharedWorker;window.SharedWorker=function(u,o){saveScript(u);send({source:'shared-worker-create',time:Date.now(),method:'GET',url:String(u)});return new W(u,o)};window.SharedWorker.prototype=W.prototype;}
              if(navigator.serviceWorker&&navigator.serviceWorker.register){const R=navigator.serviceWorker.register.bind(navigator.serviceWorker);navigator.serviceWorker.register=function(u,o){saveScript(u);send({source:'service-worker-register',time:Date.now(),method:'GET',url:String(u),options:o||null});return R(u,o)}}

              if(Element.prototype.attachShadow){const A=Element.prototype.attachShadow;Element.prototype.attachShadow=function(init){let root=A.call(this,init);send({source:'shadow-root',time:Date.now(),mode:init&&init.mode||'',target:target(this),page:location.href});return root}}
              if(window.customElements&&customElements.define){const D=customElements.define.bind(customElements);customElements.define=function(n,c,o){send({source:'custom-element',time:Date.now(),name:String(n),page:location.href});return D(n,c,o)}}

              const scanScripts=()=>Array.from(document.scripts).forEach((s,i)=>{if(s.src)saveScript(s.src);else if(s.textContent)chunks(location.href+'#inline-script-'+i,s.textContent,true)});
              scanScripts();
              new MutationObserver(ms=>{let out=[];for(const m of ms.slice(0,80)){let x={type:m.type,target:target(m.target),attribute:m.attributeName||'',oldValue:m.oldValue||'',added:[],removed:[]};for(const n of Array.from(m.addedNodes||[]).slice(0,8)){if(n.nodeType===1){x.added.push({tag:n.tagName.toLowerCase(),html:(n.outerHTML||'').slice(0,2500)});if(n.tagName==='SCRIPT')n.src?saveScript(n.src):n.textContent&&chunks(location.href+'#dynamic-inline-'+Date.now(),n.textContent,true);if(n.querySelectorAll)n.querySelectorAll('script').forEach(s=>s.src?saveScript(s.src):s.textContent&&chunks(location.href+'#dynamic-inline-'+Date.now(),s.textContent,true))}else x.added.push({text:String(n.textContent||'').slice(0,500)})}for(const n of Array.from(m.removedNodes||[]).slice(0,8))x.removed.push({tag:n.tagName&&n.tagName.toLowerCase()||'',text:String(n.textContent||'').slice(0,500)});out.push(x)}if(out.length)send({source:'dom-mutation',time:Date.now(),page:location.href,mutations:out})}).observe(document.documentElement,{childList:true,subtree:true,attributes:true,characterData:true,attributeOldValue:true,characterDataOldValue:true});

              const perfSend=list=>list.getEntries().forEach(r=>{let o={source:r.entryType==='longtask'?'long-task':(r.entryType==='resource'?'resource-timing':'performance'),time:Date.now(),entryType:r.entryType,name:r.name,startTime:r.startTime,duration:r.duration,page:location.href};for(const k of ['initiatorType','transferSize','encodedBodySize','decodedBodySize','nextHopProtocol','domComplete','loadEventEnd','type'])if(k in r)o[k]=r[k];send(o);if(r.entryType==='resource'&&(r.initiatorType==='script'||r.name.match(/\.(m?js)(\?|$)/i)))saveScript(r.name)});
              try{new PerformanceObserver(perfSend).observe({entryTypes:['resource','navigation','longtask','mark','measure']})}catch(e){}

              const wa=WebAssembly;
              if(wa&&wa.instantiate){const I=wa.instantiate.bind(wa);wa.instantiate=async function(src,imp){try{if(src instanceof ArrayBuffer||ArrayBuffer.isView(src)){let b=new Uint8Array(src.buffer||src,src.byteOffset||0,src.byteLength||src.byteLength),bin='',step=0x8000;for(let i=0;i<b.length;i+=step)bin+=String.fromCharCode.apply(null,b.subarray(i,Math.min(i+step,b.length)));chunks('wasm/module-'+Date.now()+'.base64',btoa(bin),false);send({source:'wasm',time:Date.now(),action:'instantiate',bytes:b.length,page:location.href})}}catch(e){}return I(src,imp)}}
              if(wa&&wa.instantiateStreaming){const IS=wa.instantiateStreaming.bind(wa);wa.instantiateStreaming=async function(src,imp){send({source:'wasm',time:Date.now(),action:'instantiateStreaming',page:location.href});return IS(src,imp)}}

              addEventListener('error',e=>send({source:'js-error',time:Date.now(),message:e.message,url:e.filename||location.href,line:e.lineno||0,column:e.colno||0,page:location.href}));
              addEventListener('unhandledrejection',e=>send({source:'promise-rejection',time:Date.now(),message:String(e.reason),page:location.href}));
              send({source:'hook',time:Date.now(),method:'',url:location.href,page:location.href,status:0,responseBody:'v4 hooks installed'});
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
              const attrs=e=>{let o={};try{for(const a of e.attributes)o[a.name]=a.value}catch(x){}return o};
              const elInfo=e=>({tag:(e.tagName||'').toLowerCase(),id:e.id||'',name:e.name||'',type:e.type||'',role:e.getAttribute&&e.getAttribute('role')||'',text:(e.innerText||e.textContent||'').trim().slice(0,500),attrs:attrs(e)});
              let sw=[];try{if(navigator.serviceWorker){let rs=await navigator.serviceWorker.getRegistrations();sw=rs.map(r=>({scope:r.scope,active:r.active&&r.active.scriptURL,waiting:r.waiting&&r.waiting.scriptURL,installing:r.installing&&r.installing.scriptURL}));sw.forEach(r=>[r.active,r.waiting,r.installing].filter(Boolean).forEach(u=>{try{EvrasiaResearch.externalScript(String(u))}catch(e){}}))}}catch(e){sw=[{error:String(e)}]}
              let caches=[];try{if(window.caches){for(const name of await window.caches.keys()){let c=await window.caches.open(name),items=[];for(const req of await c.keys()){try{let res=await c.match(req),body='';try{body=await res.clone().text()}catch(e){body='[unreadable/binary]'}items.push({request:{url:req.url,method:req.method,headers:Object.fromEntries(req.headers.entries())},response:{status:res.status,statusText:res.statusText,headers:Object.fromEntries(res.headers.entries()),body:body}})}catch(e){items.push({url:req.url,error:String(e)})}}caches.push({name,items})}chunks('storage/cache-storage.json',JSON.stringify(caches))}}catch(e){chunks('storage/cache-storage.json',JSON.stringify([{error:String(e)}]))}
              let dbMeta=[];try{if(indexedDB.databases){let dbs=await indexedDB.databases();for(const d of dbs){if(!d.name)continue;await new Promise(resolve=>{let q=indexedDB.open(d.name);q.onerror=()=>{dbMeta.push({name:d.name,error:String(q.error)});resolve()};q.onsuccess=()=>{let db=q.result,names=Array.from(db.objectStoreNames),out={name:d.name,version:db.version,stores:{}};if(!names.length){dbMeta.push(out);db.close();resolve();return}let tx=db.transaction(names,'readonly'),left=names.length;names.forEach(name=>{let vals=[];try{let st=tx.objectStore(name),cur=st.openCursor();cur.onerror=()=>{out.stores[name]={error:String(cur.error)};if(--left===0){dbMeta.push(out);db.close();resolve()}};cur.onsuccess=e=>{let c=e.target.result;if(c){let v;try{v=JSON.parse(JSON.stringify(c.value,(k,x)=>x instanceof Blob?{__blob:true,type:x.type,size:x.size}:x))}catch(err){v=String(c.value)}vals.push({key:c.key,value:v});c.continue()}else{out.stores[name]=vals;if(--left===0){dbMeta.push(out);db.close();resolve()}}}}catch(err){out.stores[name]={error:String(err)};if(--left===0){dbMeta.push(out);db.close();resolve()}}})}})}}chunks('storage/indexeddb.json',JSON.stringify(dbMeta))}}catch(e){chunks('storage/indexeddb.json',JSON.stringify([{error:String(e)}]))}
              let resources=[];try{resources=performance.getEntriesByType('resource').map(r=>({name:r.name,initiatorType:r.initiatorType,startTime:r.startTime,duration:r.duration,transferSize:r.transferSize,encodedBodySize:r.encodedBodySize,decodedBodySize:r.decodedBodySize,nextHopProtocol:r.nextHopProtocol||''}))}catch(e){}
              let nav=[];try{nav=performance.getEntriesByType('navigation').map(r=>({type:r.type,startTime:r.startTime,duration:r.duration,domInteractive:r.domInteractive,domContentLoadedEventEnd:r.domContentLoadedEventEnd,domComplete:r.domComplete,loadEventEnd:r.loadEventEnd,transferSize:r.transferSize,encodedBodySize:r.encodedBodySize,decodedBodySize:r.decodedBodySize,nextHopProtocol:r.nextHopProtocol||''}))}catch(e){}
              let forms=Array.from(document.forms).map(f=>({action:f.action,method:f.method,enctype:f.enctype,id:f.id||'',name:f.name||'',controls:Array.from(f.elements).map(elInfo)}));
              let buttons=Array.from(document.querySelectorAll('button,input[type=button],input[type=submit],[role=button]')).map(elInfo);
              let links=Array.from(document.links).map(a=>({href:a.href,text:(a.innerText||a.textContent||'').trim().slice(0,500),attrs:attrs(a)}));
              let iframes=Array.from(document.querySelectorAll('iframe')).map((f,i)=>{let x={index:i,src:f.src||'',attrs:attrs(f)};try{x.url=f.contentWindow.location.href;x.html=f.contentDocument.documentElement.outerHTML}catch(e){x.crossOrigin=true}return x});
              let custom=Array.from(document.querySelectorAll('*')).filter(e=>e.tagName.includes('-')).map(elInfo);
              let shadows=[];Array.from(document.querySelectorAll('*')).forEach(e=>{try{if(e.shadowRoot)shadows.push({host:elInfo(e),html:e.shadowRoot.innerHTML})}catch(x){}});
              let configs={};for(const k of Object.getOwnPropertyNames(window)){if(!/(config|setting|env|api|route|state|store)/i.test(k))continue;try{let v=window[k];if(v&&typeof v==='object'){let s=JSON.stringify(v);if(s&&s.length<200000)configs[k]=JSON.parse(s)}else if(['string','number','boolean'].includes(typeof v))configs[k]=v}catch(e){configs[k]={__error:String(e)}}}
              chunks('dom/forms.json',JSON.stringify(forms));chunks('dom/buttons.json',JSON.stringify(buttons));chunks('dom/links.json',JSON.stringify(links));chunks('dom/iframes.json',JSON.stringify(iframes));chunks('dom/custom-elements.json',JSON.stringify(custom));chunks('dom/shadow-roots.json',JSON.stringify(shadows));chunks('browser/global-config-candidates.json',JSON.stringify(configs));
              let snap={time:Date.now(),url:location.href,title:document.title,cookie:document.cookie,localStorage:store(localStorage),sessionStorage:store(sessionStorage),serviceWorkers:sw,resources:resources,navigationTiming:nav,html:document.documentElement.outerHTML};
              try{EvrasiaResearch.snapshot(JSON.stringify(snap))}catch(e){}
              Array.from(document.scripts).forEach((s,i)=>{if(s.src){try{EvrasiaResearch.externalScript(String(s.src))}catch(e){}}else if(s.textContent){let u=location.href+'#inline-script-'+i,c=s.textContent,step=120000,total=Math.max(1,Math.ceil(c.length/step));for(let n=0;n<total;n++){try{EvrasiaResearch.scriptChunk(u,n,total,c.slice(n*step,(n+1)*step))}catch(e){}}}});
            })();
        """.trimIndent()
        archive.snapshot.put("nativeCookies", nativeCookies)
        web.evaluateJavascript(js, null)
    }

    private fun addRecord(record: JSONObject) {
        archive.addRecord(record)
        runOnUiThread { updateBadge() }
    }

    private fun updateBadge() {
        badge.text = "${archive.records.length()} событий · ${archive.scripts.size} JS · ${archive.resources.size} ресурсов"
    }

    private fun exportZip() {
        capturePageSnapshot()
        archive.snapshot.put("nativeCookies", CookieManager.getInstance().getCookie(web.url ?: "") ?: "")
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
        @JavascriptInterface fun snapshot(json: String) {
            try {
                val oldNative = archive.snapshot.optString("nativeCookies", "")
                archive.snapshot = JSONObject(json)
                archive.snapshot.put("nativeCookies", oldNative)
            } catch (_: Exception) {}
        }
        @JavascriptInterface fun externalScript(url: String) { if (url.isNotBlank()) captureExternalScript(url, emptyMap()) }
        @JavascriptInterface fun requestSnapshot() { runOnUiThread { capturePageSnapshot() } }
        @JavascriptInterface fun scriptChunk(url: String, index: Int, total: Int, chunk: String) {
            assembleChunk(scriptChunks, url, index, total, chunk) { key, text ->
                archive.scripts[key] = text.toByteArray(Charsets.UTF_8)
                runOnUiThread { updateBadge() }
            }
        }
        @JavascriptInterface fun artifactChunk(key: String, index: Int, total: Int, chunk: String) {
            assembleChunk(artifactChunks, key, index, total, chunk) { name, text -> archive.extraArtifacts[name] = text.toByteArray(Charsets.UTF_8) }
        }
    }

    private fun assembleChunk(
        store: ConcurrentHashMap<String, MutableMap<Int, String>>,
        key: String,
        index: Int,
        total: Int,
        chunk: String,
        done: (String, String) -> Unit
    ) {
        try {
            val map = store.getOrPut(key) { ConcurrentHashMap() }
            map[index] = chunk
            if (map.size == total) {
                val out = StringBuilder()
                for (i in 0 until total) out.append(map[i] ?: "")
                store.remove(key)
                done(key, out.toString())
            }
        } catch (_: Exception) {}
    }
}
