package ru.evrasia.research

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NetworkDebuggerActivity : AppCompatActivity() {
    private val bg = Color.rgb(6,14,12)
    private val panel = Color.rgb(14,29,24)
    private val panel2 = Color.rgb(20,39,33)
    private val accent = Color.rgb(151,231,92)
    private val textColor = Color.rgb(238,245,241)
    private val muted = Color.rgb(157,177,166)
    private val bad = Color.rgb(255,118,118)
    private val cyan = Color.rgb(0,226,239)
    private val amber = Color.rgb(255,205,112)
    private val violet = Color.rgb(196,162,255)

    private lateinit var list: ListView
    private lateinit var adapter: EventAdapter
    private lateinit var counter: TextView
    private lateinit var recordButton: Button
    private lateinit var jsButton: Button
    private lateinit var domainSpinner: Spinner
    private lateinit var search: EditText
    private val allItems = mutableListOf<JSONObject>()
    private val items = mutableListOf<JSONObject>()
    private val domains = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val listTimeFormat = SimpleDateFormat("HH:mm:ss.SSS",Locale.US)
    private var lastRevision = -1L
    private var jsOnly = false
    private var pendingBinary: ByteArray? = null
    private var pendingBinaryName = "response.bin"
    private var pendingBinaryMime = "application/octet-stream"

    private val refresh = object : Runnable {
        override fun run() {
            val rev = NetworkDebugStore.revision()
            if (rev != lastRevision) { lastRevision = rev; reload() }
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        val toolbarScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; setBackgroundColor(panel) }
        val toolbar = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),dp(5),dp(8),dp(5)) }
        recordButton = compactButton("") { NetworkDebugStore.recording=!NetworkDebugStore.recording; updateRecordButton() }
        updateRecordButton()
        toolbar.addView(recordButton)
        toolbar.addView(compactButton("Очистить") { NetworkDebugStore.clear(); reload() })
        toolbar.addView(compactButton("Cookies") { showAllCookies() })
        toolbar.addView(compactButton("ZIP") { exportZip() })
        jsButton = compactButton("JS") { jsOnly=!jsOnly; updateJsButton(); applyFilters() }
        toolbar.addView(jsButton)
        updateJsButton()
        toolbarScroll.addView(toolbar)
        root.addView(toolbarScroll, LinearLayout.LayoutParams(-1,dp(48)))

        val filterRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),dp(6),dp(8),dp(4)) }
        domainSpinner = Spinner(this)
        filterRow.addView(domainSpinner, LinearLayout.LayoutParams(dp(150),dp(42)))
        search = EditText(this).apply {
            hint="Поиск по всем данным"; setHintTextColor(muted); setTextColor(textColor); textSize=12f; setSingleLine(true); imeOptions=EditorInfo.IME_ACTION_SEARCH
            background=rounded(panel2,11f,Color.rgb(50,76,65)); setPadding(dp(10),0,dp(10),0)
            setOnEditorActionListener { _, id, _ -> if(id==EditorInfo.IME_ACTION_SEARCH){ applyFilters(); true } else false }
        }
        filterRow.addView(search, LinearLayout.LayoutParams(0,dp(42),1f).apply { marginStart=dp(6) })
        filterRow.addView(compactButton("⌕") { applyFilters() }, LinearLayout.LayoutParams(dp(44),dp(42)).apply { marginStart=dp(5) })
        root.addView(filterRow)

        counter = TextView(this).apply { setTextColor(muted); textSize=11f; setPadding(dp(12),dp(4),dp(12),dp(6)) }
        root.addView(counter)

        list = ListView(this).apply { divider=null; dividerHeight=dp(1); setBackgroundColor(bg) }
        adapter=EventAdapter(); list.adapter=adapter
        list.setOnItemClickListener { _,_,position,_ -> showDetails(items[position], search.text.toString().trim()) }
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root){v,i-> val bars:Insets=i.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(bars.left,bars.top,bars.right,bars.bottom); i }
        ViewCompat.requestApplyInsets(root)
        reload()
    }

    override fun onResume(){ super.onResume(); (application as? WebResearchApp)?.syncNetworkStore(); handler.removeCallbacks(refresh); handler.post(refresh) }
    override fun onPause(){ handler.removeCallbacks(refresh); super.onPause() }

    private fun updateRecordButton(){ if(::recordButton.isInitialized){ recordButton.text=if(NetworkDebugStore.recording) "● REC" else "○ STOP"; recordButton.setTextColor(if(NetworkDebugStore.recording) accent else muted) } }
    private fun updateJsButton(){ if(::jsButton.isInitialized){ jsButton.text=if(jsOnly) "JS ✓" else "JS"; jsButton.setTextColor(if(jsOnly) accent else textColor) } }

    private fun reload(){
        allItems.clear(); allItems.addAll(NetworkDebugStore.snapshot().asReversed())
        rebuildDomains(); applyFilters()
    }

    private fun rebuildDomains(){
        val selected=if(::domainSpinner.isInitialized && domainSpinner.selectedItem!=null) domainSpinner.selectedItem.toString() else "Все домены"
        domains.clear(); domains.add("Все домены")
        allItems.mapNotNull { hostOf(it.optString("url","")) }.distinct().sorted().forEach { domains.add(it) }
        val a=object:ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,domains){
            private fun v(p:Int)=TextView(this@NetworkDebuggerActivity).apply { text=getItem(p); setTextColor(textColor); textSize=12f; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(10),0,dp(10),0); background=rounded(panel2,10f,Color.rgb(50,76,65)) }
            override fun getView(p:Int,c:View?,parent:ViewGroup)=v(p)
            override fun getDropDownView(p:Int,c:View?,parent:ViewGroup)=v(p).apply { setPadding(dp(12),dp(10),dp(12),dp(10)); background=rounded(bg,0f) }
        }
        domainSpinner.adapter=a
        domainSpinner.setSelection(domains.indexOf(selected).takeIf{it>=0}?:0)
        domainSpinner.setOnItemSelectedListener(object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){ applyFilters() }
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){ }
        })
    }

    private fun applyFilters(){
        val domain=if(::domainSpinner.isInitialized && domainSpinner.selectedItem!=null) domainSpinner.selectedItem.toString() else "Все домены"
        val q=if(::search.isInitialized) search.text.toString().trim() else ""
        items.clear()
        allItems.filterTo(items){ e ->
            val domainOk = domain=="Все домены" || hostOf(e.optString("url",""))==domain
            val jsOk = !jsOnly || isJsEvent(e)
            val searchOk = q.isBlank() || e.toString().contains(q,true)
            domainOk && jsOk && searchOk
        }
        if(::adapter.isInitialized) adapter.notifyDataSetChanged()
        if(::counter.isInitialized){
            val requests=allItems.count{isRequestEvent(it)}
            val js=allItems.count{isJsEvent(it)}
            val resources=allItems.count{it.optString("source")=="resource-copy" || it.optString("source")=="webview"}
            val errors=allItems.count{it.has("error")||it.optInt("status",0)>=400}
            counter.text="${allItems.size} событий · $requests запросов · $js JS · $resources ресурсов · $errors ошибок${if(q.isNotBlank()) " · найдено ${items.size}" else ""}"
        }
    }

    private fun isRequestEvent(e:JSONObject)=e.optString("source") in setOf("fetch","fetch-meta","xhr","xhr-meta","webview","resource-copy","resource-timing","navigation","navigation-timing","new-window","websocket-open","websocket-send","websocket-receive","sse-open","sse-message","beacon","js-file","script-archive")
    private fun isJsEvent(e:JSONObject):Boolean { val u=e.optString("url","").substringBefore('?').lowercase(Locale.US); return e.optString("source") in setOf("js-file","script-archive","source-map") || u.endsWith(".js") || u.endsWith(".mjs") || e.optString("mimeType","").contains("javascript",true) }
    private fun hostOf(url:String):String?=try { if(url.startsWith("http://")||url.startsWith("https://")) URL(url).host else null } catch(_:Exception){null}

    private fun showDetails(event:JSONObject, query:String){
        val url=event.optString("url","")
        val requestCookies=if(url.startsWith("http")) CookieManager.getInstance().getCookie(url).orEmpty() else ""
        val responseHeaders=event.optJSONObject("responseHeaders")
        val mime=event.optString("mimeType",headerValue(responseHeaders,"Content-Type")).substringBefore(';').trim()
        val responseBody=event.optString("responseBody",event.optString("data",""))
        val bytes=responseBytes(event)
        val binary=bytes!=null && bytes.isNotEmpty() && isBinaryPayload(mime,responseBody,bytes)
        val imageBitmap=if(binary&&bytes!=null)try{BitmapFactory.decodeByteArray(bytes,0,bytes.size)}catch(_:Exception){null}else null

        val requestText=buildRequestText(event,requestCookies)
        val responseText=buildResponseText(event,requestCookies)

        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(bg);setPadding(dp(10),dp(8),dp(10),dp(14))}
        val decodeButton=compactButton("URL-ДЕКОДИРОВАТЬ") { decodeAllText(content) }
        content.addView(decodeButton,LinearLayout.LayoutParams(-1,dp(40)).apply{setMargins(0,0,0,dp(4))})
        addSection(content,"REQUEST",highlightPlain(requestText,query))
        addSection(content,"RESPONSE",highlightPlain(responseText,query))

        addSectionTitle(content,"RESPONSE BODY")
        if(binary){
            val info=buildString{
                append(if(imageBitmap!=null)"Image payload\n" else "Binary payload\n")
                append("MIME: ").append(mime.ifBlank{"application/octet-stream"}).append('\n')
                append("Size: ").append(bytes!!.size).append(" bytes\n")
                append("File: ").append(suggestFileName(event,mime))
            }
            content.addView(codeText(highlightPlain(info,query)))
            if(imageBitmap!=null){
                content.addView(ImageView(this).apply{
                    setImageBitmap(imageBitmap)
                    adjustViewBounds=true
                    scaleType=ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(panel2)
                    contentDescription="Изображение из ответа сервера"
                    setPadding(dp(6),dp(6),dp(6),dp(6))
                },LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(7),0,dp(4))})
            }
            content.addView(compactButton("СОХРАНИТЬ БИНАРНИК") { beginBinarySave(event,bytes,mime) },LinearLayout.LayoutParams(-1,dp(42)).apply{setMargins(0,dp(7),0,dp(4))})
        }else{
            val decorated=decorateResponseBody(responseBody.ifBlank{"—"},mime,query)
            content.addView(codeText(decorated))
        }

        val rawButton=compactButton("RAW EVENT  ▾"){}
        val rawView=codeText(highlightPlain(event.toString(2),query)).apply{visibility=View.GONE}
        rawButton.setOnClickListener{rawView.visibility=if(rawView.visibility==View.VISIBLE)View.GONE else View.VISIBLE;rawButton.text=if(rawView.visibility==View.VISIBLE)"RAW EVENT  ▴" else "RAW EVENT  ▾"}
        content.addView(rawButton,LinearLayout.LayoutParams(-1,dp(38)).apply{setMargins(0,dp(9),0,dp(5))})
        content.addView(rawView)

        val sv=ScrollView(this).apply{setBackgroundColor(bg);addView(content)}
        AlertDialog.Builder(this).setTitle("Детали запроса").setView(sv).setPositiveButton("Закрыть",null).show()
    }

    private fun decodeAllText(view:View){
        when(view){
            is Button -> Unit
            is TextView -> view.text=decodePercentText(view.text.toString())
            is ViewGroup -> for(i in 0 until view.childCount)decodeAllText(view.getChildAt(i))
        }
    }

    private fun buildRequestText(event:JSONObject,cookies:String)=buildString{
        append("Method: ").append(event.optString("method","—")).append('\n')
        append("URL: ").append(event.optString("url","—")).append('\n')
        appendUrlParts(this,event.optString("url",""))
        append("Source: ").append(event.optString("source","—")).append('\n')
        if(event.has("time"))append("Time: ").append(formatTime(event.optLong("time"))).append("  (").append(event.optLong("time")).append(")\n")
        appendField(this,event,"initiatorType","Initiator type")
        appendField(this,event,"initiatorStack","Initiator stack")
        appendField(this,event,"requestStart","Request start")
        appendField(this,event,"workerStart","Worker start")
        val headers=event.optJSONObject("requestHeaders")?:event.optJSONObject("headers")
        append("\nREQUEST HEADERS\n").append(prettyJson(headers?:JSONObject())).append('\n')
        append("\nREQUEST COOKIES\n").append(cookies.ifBlank{"—"}).append('\n')
        val body=event.optString("requestBody","")
        append("\nREQUEST BODY\n").append(if(body.isBlank())"—" else prettyBody(body,event.optString("requestMimeType",""))).append('\n')
        val timing=event.optJSONObject("timing")
        if(timing!=null){append("\nREQUEST / CONNECTION TIMING\n");listOf("queueing","dns","connect","ssl","request").forEach{k->if(timing.has(k))append(k).append(": ").append(timing.opt(k)).append(" ms\n")}}
    }.trimEnd()

    private fun buildResponseText(event:JSONObject,cookies:String)=buildString{
        if(event.has("status"))append("Status: ").append(event.optInt("status")).append(' ').append(event.optString("statusText","")).append('\n')
        appendField(this,event,"finalUrl","Final URL")
        appendField(this,event,"redirectURL","Redirect URL")
        appendField(this,event,"redirected","Redirected")
        appendField(this,event,"redirectCount","Redirect count")
        appendField(this,event,"httpVersion","Protocol")
        appendField(this,event,"cache","Delivery/cache")
        appendField(this,event,"deliveryType","Delivery type")
        appendField(this,event,"renderBlockingStatus","Render blocking")
        appendField(this,event,"responseType","Response type")
        appendField(this,event,"mimeType","MIME type")
        if(event.has("duration"))append("Duration: ").append(event.opt("duration")).append(" ms\n")
        listOf("responseSize" to "Response size","transferSize" to "Transferred","encodedBodySize" to "Encoded body","decodedBodySize" to "Decoded body").forEach{(k,n)->if(event.has(k))append(n).append(": ").append(event.optLong(k)).append(" bytes\n")}
        appendField(this,event,"responseStart","Response start")
        appendField(this,event,"responseEnd","Response end")
        val timing=event.optJSONObject("timing")
        if(timing!=null){append("\nTIMING\n");timing.keys().forEach{k->append(k).append(": ").append(timing.opt(k)).append(" ms\n")}}
        val headers=event.optJSONObject("responseHeaders")
        append("\nRESPONSE HEADERS\n").append(if(headers!=null)prettyJson(headers) else event.optString("responseHeadersRaw","—")).append('\n')
        append("\nCOOKIES FOR URL\n").append(cookies.ifBlank{"—"}).append('\n')
        if(event.has("error"))append("\nERROR\n").append(event.optString("error")).append('\n')
    }.trimEnd()

    private fun appendUrlParts(out:StringBuilder,raw:String){
        try{val u=URL(raw);out.append("Scheme: ").append(u.protocol).append('\n');out.append("Host: ").append(u.host).append('\n');out.append("Port: ").append(if(u.port>0)u.port else u.defaultPort).append('\n');out.append("Path: ").append(u.path.ifBlank{"/"}).append('\n');if(!u.query.isNullOrBlank()){out.append("\nQUERY PARAMETERS\n");u.query.split('&').forEach{part->val p=part.indexOf('=');val k=if(p>=0)part.substring(0,p) else part;val v=if(p>=0)part.substring(p+1) else "";out.append(k).append(" = ").append(v).append('\n')}}}catch(_:Exception){}
    }

    private fun appendField(out:StringBuilder,event:JSONObject,key:String,label:String){if(event.has(key)){val v=event.opt(key);if(v!=null&&v!=JSONObject.NULL&&v.toString().isNotBlank())out.append(label).append(": ").append(v).append('\n')}}
    private fun formatTime(ms:Long)=SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(Date(ms))
    private fun listTime(ms:Long)=listTimeFormat.format(Date(ms))
    private fun urlDecode(s:String)=try{URLDecoder.decode(s,"UTF-8")}catch(_:Exception){s}
    private fun prettyJson(obj:JSONObject)=try{obj.toString(2)}catch(_:Exception){obj.toString()}

    private fun decodePercentText(raw:String):String{
        var value=raw
        repeat(3){
            if(!Regex("%[0-9A-Fa-f]{2}").containsMatchIn(value))return value
            val decoded=try{URLDecoder.decode(value,"UTF-8")}catch(_:Exception){return value}
            if(decoded==value)return value
            value=decoded
        }
        return value
    }

    private fun prettyBody(raw:String,mime:String):String{
        val t=raw.trim()
        if(t.isBlank())return "—"
        try{if(t.startsWith("{"))return JSONObject(t).toString(2);if(t.startsWith("["))return JSONArray(t).toString(2)}catch(_:Exception){}
        if(mime.contains("json",true)){try{return JSONObject(t).toString(2)}catch(_:Exception){};try{return JSONArray(t).toString(2)}catch(_:Exception){}}
        if(mime.contains("xml",true)||mime.contains("html",true)){return t.replace(Regex(">\\s*<"),">\n<")}
        return raw
    }

    private fun decorateResponseBody(raw:String,mime:String,query:String):CharSequence{
        val pretty=prettyBody(raw,mime)
        val s=SpannableString(pretty)
        if(mime.contains("json",true)||pretty.trim().startsWith("{")||pretty.trim().startsWith("[")){
            colorRegex(s,Regex("\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)",RegexOption.DOT_MATCHES_ALL),cyan)
            colorRegex(s,Regex("(?<=:)\\s*\"(?:\\\\.|[^\"\\\\])*\"",RegexOption.DOT_MATCHES_ALL),accent)
            colorRegex(s,Regex("\\b(true|false|null)\\b"),violet)
            colorRegex(s,Regex("(?<![A-Za-z0-9_])-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?"),amber)
        }else if(mime.contains("html",true)||mime.contains("xml",true)||pretty.trim().startsWith("<")){
            colorRegex(s,Regex("</?[A-Za-z][^>]*>"),cyan)
            colorRegex(s,Regex("\\b[A-Za-z_:][-A-Za-z0-9_:.]*(?=\\s*=)"),accent)
            colorRegex(s,Regex("\"[^\"]*\"|'[^']*'"),amber)
        }else if(mime.contains("javascript",true)||mime.contains("ecmascript",true)||mime.contains("css",true)){
            colorRegex(s,Regex("//.*?$|/\\*.*?\\*/",setOf(RegexOption.MULTILINE,RegexOption.DOT_MATCHES_ALL)),muted)
            colorRegex(s,Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`",RegexOption.DOT_MATCHES_ALL),accent)
            colorRegex(s,Regex("\\b(const|let|var|function|class|return|if|else|for|while|try|catch|throw|async|await|new|this|true|false|null|undefined|import|export|from)\\b"),cyan)
            colorRegex(s,Regex("\\b\\d+(?:\\.\\d+)?\\b"),amber)
        }
        applyQueryHighlight(s,query)
        return s
    }

    private fun highlightPlain(raw:String,query:String):CharSequence{val s=SpannableString(raw);applyQueryHighlight(s,query);return s}
    private fun colorRegex(s:SpannableString,r:Regex,color:Int){r.findAll(s.toString()).forEach{m->s.setSpan(ForegroundColorSpan(color),m.range.first,m.range.last+1,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)}}
    private fun applyQueryHighlight(s:SpannableString,query:String){if(query.isBlank())return;var p=s.toString().indexOf(query,0,true);while(p>=0){s.setSpan(BackgroundColorSpan(Color.rgb(90,110,30)),p,p+query.length,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);p=s.toString().indexOf(query,p+query.length,true)}}

    private fun addSection(root:LinearLayout,title:String,value:CharSequence){addSectionTitle(root,title);root.addView(codeText(value))}
    private fun addSectionTitle(root:LinearLayout,title:String){root.addView(TextView(this).apply{text=title;setTextColor(cyan);textSize=12f;typeface=Typeface.DEFAULT_BOLD;letterSpacing=.08f;setPadding(dp(2),dp(10),dp(2),dp(5))})}
    private fun codeText(value:CharSequence)=TextView(this).apply{text=value;setTextColor(textColor);textSize=11f;typeface=Typeface.MONOSPACE;setTextIsSelectable(true);setPadding(dp(9),dp(8),dp(9),dp(8));background=rounded(panel2,8f,Color.rgb(40,64,70))}

    private fun responseBytes(event:JSONObject):ByteArray?{
        val url=event.optString("url","")
        if(url.isBlank())return null
        return try{
            val app=application as? WebResearchApp?:return null
            val bf=WebResearchApp::class.java.getDeclaredField("browserRef");bf.isAccessible=true
            val ref=bf.get(app) as? WeakReference<*>?:return null
            val browser=ref.get() as? WebResearchV10Activity?:return null
            val af=WebResearchV10Activity::class.java.getDeclaredField("archive");af.isAccessible=true
            val archive=af.get(browser) as? ResearchArchive?:return null
            archive.resources[url]
        }catch(_:Exception){null}
    }

    private fun isBinaryPayload(mime:String,body:String,bytes:ByteArray):Boolean{
        val m=mime.lowercase(Locale.US)
        if(body=="[non-text response]"||body=="[binary]")return true
        if(m.startsWith("text/")||m.contains("json")||m.contains("javascript")||m.contains("xml")||m.contains("html")||m.contains("css")||m.contains("x-www-form-urlencoded"))return false
        if(m.isNotBlank())return true
        val sample=bytes.take(512)
        if(sample.any{it.toInt()==0})return true
        val printable=sample.count{val n=it.toInt() and 255;n==9||n==10||n==13||n in 32..126||n>=160}
        return sample.isNotEmpty()&&printable.toDouble()/sample.size<0.82
    }

    private fun beginBinarySave(event:JSONObject,bytes:ByteArray,mime:String){
        pendingBinary=bytes
        pendingBinaryMime=mime.ifBlank{"application/octet-stream"}
        pendingBinaryName=suggestFileName(event,pendingBinaryMime)
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type=pendingBinaryMime;putExtra(Intent.EXTRA_TITLE,pendingBinaryName)},702)
    }

    private fun suggestFileName(event:JSONObject,mime:String):String{
        val headers=event.optJSONObject("responseHeaders")
        val disposition=headerValue(headers,"Content-Disposition")
        Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)",RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.getOrNull(1)?.let{return sanitizeName(urlDecode(it.trim()))}
        val url=event.optString("finalUrl",event.optString("url",""))
        val path=try{URL(url).path.substringAfterLast('/')}catch(_:Exception){""}
        var name=path.ifBlank{"response"}
        if(!name.contains('.')){MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.substringBefore(';'))?.takeIf{it.isNotBlank()}?.let{name="$name.$it"}}
        return sanitizeName(name)
    }

    private fun sanitizeName(raw:String)=raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").take(120).ifBlank{"response.bin"}
    private fun headerValue(headers:JSONObject?,name:String):String{if(headers==null)return "";val it=headers.keys();while(it.hasNext()){val k=it.next();if(k.equals(name,true))return headers.optString(k,"")};return ""}

    private fun showAllCookies(){
        val rows=linkedMapOf<String,String>()
        allItems.map{it.optString("url","")}.distinct().forEach{u-> hostOf(u)?.let{h->CookieManager.getInstance().getCookie(u)?.takeIf{it.isNotBlank()}?.let{rows[h]=it}}}
        val value=if(rows.isEmpty()) "Куки не обнаружены." else rows.entries.joinToString("\n\n"){"${it.key}\n${it.value}"}
        val tv=TextView(this).apply{text=value;setTextColor(textColor);setBackgroundColor(bg);textSize=12f;setTextIsSelectable(true);setPadding(dp(12),dp(10),dp(12),dp(14))}
        AlertDialog.Builder(this).setTitle("Cookies").setView(ScrollView(this).apply{addView(tv)}).setPositiveButton("Закрыть",null).show()
    }

    private fun exportZip(){
        val stamp=SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(Date())
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/zip";putExtra(Intent.EXTRA_TITLE,"web-research-network-$stamp.zip")},701)
    }

    @Deprecated("Deprecated in Java") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK)return
        if(requestCode==701){
            data?.data?.let{uri->contentResolver.openOutputStream(uri)?.use{o->ZipOutputStream(o).use{z->addZip(z,"network-events.json",JSONObject().put("recording",NetworkDebugStore.recording).put("events",NetworkDebugStore.json()).toString(2));addZip(z,"cookies.json",buildCookiesJson().toString(2))}}}
            Toast.makeText(this,"ZIP экспортирован",Toast.LENGTH_SHORT).show()
        }else if(requestCode==702){
            val bytes=pendingBinary
            if(bytes!=null){data?.data?.let{uri->contentResolver.openOutputStream(uri)?.use{it.write(bytes)}};Toast.makeText(this,"Бинарный ответ сохранён",Toast.LENGTH_SHORT).show()}
            pendingBinary=null
        }
    }

    private fun buildCookiesJson():JSONArray { val out=JSONArray(); val seen=mutableSetOf<String>(); allItems.forEach{e->val u=e.optString("url","");hostOf(u)?.let{h->if(seen.add(h))out.put(JSONObject().put("host",h).put("url",u).put("cookie",CookieManager.getInstance().getCookie(u).orEmpty()))}};return out }
    private fun addZip(z:ZipOutputStream,name:String,text:String){z.putNextEntry(ZipEntry(name));z.write(text.toByteArray(Charsets.UTF_8));z.closeEntry()}

    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun rounded(fill:Int,radius:Float,stroke:Int=Color.TRANSPARENT)=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;setColor(fill);cornerRadius=dp(radius.toInt()).toFloat();if(stroke!=Color.TRANSPARENT)setStroke(dp(1),stroke)}
    private fun compactButton(label:String,click:()->Unit)=Button(this).apply{text=label;setTextColor(textColor);textSize=11f;isAllCaps=false;minWidth=0;minimumWidth=0;setPadding(dp(10),0,dp(10),0);background=rounded(panel2,11f,Color.rgb(50,76,65));setOnClickListener{click()}}

    inner class EventAdapter:BaseAdapter(){
        override fun getCount()=items.size
        override fun getItem(position:Int)=items[position]
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,convertView:View?,parent:ViewGroup?):View{
            val e=getItem(position)
            val row=(convertView as? LinearLayout)?:LinearLayout(this@NetworkDebuggerActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(7),dp(10),dp(7));background=rounded(panel,0f,Color.rgb(26,48,39));addView(TextView(this@NetworkDebuggerActivity).apply{tag="top";textSize=12f;typeface=Typeface.DEFAULT_BOLD});addView(TextView(this@NetworkDebuggerActivity).apply{tag="url";textSize=11f;maxLines=2;setPadding(0,dp(2),0,0)})}
            val top=row.findViewWithTag<TextView>("top");val url=row.findViewWithTag<TextView>("url")
            val status=e.optInt("status",0);val source=e.optString("source","");val method=e.optString("method",if(isJsEvent(e))"JS" else source.uppercase(Locale.US));val whenText=if(e.has("time"))listTime(e.optLong("time")) else "--:--:--.---"
            top.text=buildString{append(whenText).append("  ");append(if(isJsEvent(e))"JS" else method);if(status>0)append("  ").append(status);if(e.has("duration"))append("  ").append(e.optLong("duration")).append(" ms");if(e.has("responseSize"))append("  ").append(e.optLong("responseSize")).append(" B");append("  · ").append(source)}
            top.setTextColor(if(status>=400||e.has("error"))bad else if(isJsEvent(e)||status in 200..399)accent else textColor)
            url.text=e.optString("url",e.optString("message","—"));url.setTextColor(muted);return row
        }
    }
}
