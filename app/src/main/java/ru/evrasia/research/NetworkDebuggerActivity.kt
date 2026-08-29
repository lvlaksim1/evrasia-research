package ru.evrasia.research

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
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
import kotlin.math.abs

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
    private lateinit var mergeButton: Button
    private lateinit var domainSpinner: Spinner
    private lateinit var typeSpinner: Spinner
    private lateinit var search: EditText
    private val allItems = mutableListOf<JSONObject>()
    private val items = mutableListOf<JSONObject>()
    private val domains = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val listTimeFormat = SimpleDateFormat("HH:mm:ss.SSS",Locale.US)
    private var lastRevision = -1L
    private var jsOnly = false
    private var mergeMode = false
    private var pendingBinary: ByteArray? = null
    private var pendingBinaryName = "response.bin"
    private var pendingBinaryMime = "application/octet-stream"
    private val displayMergeSources = setOf("webview","resource-copy","resource-timing","fetch","fetch-meta","xhr","xhr-meta")
    private val displaySourceOrder = listOf("webview","fetch","fetch-meta","xhr","xhr-meta","resource-timing","resource-copy")
    private val displayMergeWindowMs = 1200L
    private val typeFilters = listOf("ALL","JSON","HTML","JS","CSS","IMG","PDF","TEXT","BIN","OTHER")

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
        mergeButton = compactButton("") { mergeMode=!mergeMode; updateMergeButton(); applyFilters() }
        toolbar.addView(mergeButton)
        updateMergeButton()
        toolbarScroll.addView(toolbar)
        root.addView(toolbarScroll, LinearLayout.LayoutParams(-1,dp(48)))

        val filterRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),dp(6),dp(8),dp(4)) }
        domainSpinner = Spinner(this)
        filterRow.addView(domainSpinner, LinearLayout.LayoutParams(dp(120),dp(42)))
        typeSpinner = Spinner(this)
        typeSpinner.adapter = object:ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,typeFilters){
            private fun v(p:Int)=TextView(this@NetworkDebuggerActivity).apply{text=getItem(p);setTextColor(textColor);textSize=11f;gravity=Gravity.CENTER;setPadding(dp(6),0,dp(6),0);background=rounded(panel2,10f,Color.rgb(50,76,65))}
            override fun getView(p:Int,c:View?,parent:ViewGroup)=v(p)
            override fun getDropDownView(p:Int,c:View?,parent:ViewGroup)=v(p).apply{setPadding(dp(10),dp(10),dp(10),dp(10));background=rounded(bg,0f)}
        }
        typeSpinner.setOnItemSelectedListener(object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){applyFilters()}
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){ }
        })
        filterRow.addView(typeSpinner, LinearLayout.LayoutParams(dp(78),dp(42)).apply { marginStart=dp(5) })
        search = EditText(this).apply {
            hint="Поиск"; setHintTextColor(muted); setTextColor(textColor); textSize=12f; setSingleLine(true); imeOptions=EditorInfo.IME_ACTION_SEARCH
            background=rounded(panel2,11f,Color.rgb(50,76,65)); setPadding(dp(10),0,dp(10),0)
            setOnEditorActionListener { _, id, _ -> if(id==EditorInfo.IME_ACTION_SEARCH){ applyFilters(); true } else false }
        }
        filterRow.addView(search, LinearLayout.LayoutParams(0,dp(42),1f).apply { marginStart=dp(5) })
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
    private fun updateMergeButton(){ if(::mergeButton.isInitialized){ mergeButton.text=if(mergeMode) "Объединено ✓" else "Раздельно"; mergeButton.setTextColor(if(mergeMode) accent else textColor) } }

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
        val type=if(::typeSpinner.isInitialized && typeSpinner.selectedItem!=null) typeSpinner.selectedItem.toString() else "ALL"
        val q=if(::search.isInitialized) search.text.toString().trim() else ""
        val displayItems=if(mergeMode) mergeForDisplay(allItems) else allItems
        items.clear()
        displayItems.filterTo(items){ e ->
            val domainOk = domain=="Все домены" || hostOf(e.optString("url",""))==domain
            val jsOk = !jsOnly || isJsEvent(e)
            val typeOk = type=="ALL" || responseKind(e)==type
            val searchOk = q.isBlank() || e.toString().contains(q,true)
            domainOk && jsOk && typeOk && searchOk
        }
        if(::adapter.isInitialized) adapter.notifyDataSetChanged()
        if(::counter.isInitialized){
            val requests=allItems.count{isRequestEvent(it)}
            val js=allItems.count{isJsEvent(it)}
            val resources=allItems.count{it.optString("source")=="resource-copy" || it.optString("source")=="webview"}
            val errors=allItems.count{it.has("error")||it.optInt("status",0)>=400}
            val prefix=if(mergeMode) "${allItems.size} событий → ${displayItems.size} строк" else "${allItems.size} событий"
            val filtered=if(q.isNotBlank()||type!="ALL"||domain!="Все домены"||jsOnly)" · показано ${items.size}" else ""
            counter.text="$prefix · $requests запросов · $js JS · $resources ресурсов · $errors ошибок$filtered"
        }
    }

    private fun mergeForDisplay(source:List<JSONObject>):List<JSONObject>{
        val out=mutableListOf<JSONObject>()
        val buckets=HashMap<String,MutableList<JSONObject>>()
        source.forEach{original->
            val event=JSONObject(original.toString())
            if(!isDisplayMergeable(event)){
                out.add(event)
                return@forEach
            }
            val time=event.optLong("time",0L)
            val key=displayMergeKey(event)
            val candidates=buckets.getOrPut(key){mutableListOf()}
            if(time>0L)candidates.removeAll{candidate->val candidateTime=candidate.optLong("time",0L);candidateTime>0L&&abs(candidateTime-time)>displayMergeWindowMs}
            val incomingSources=eventSources(event)
            val target=candidates.filter{candidate->eventSources(candidate).intersect(incomingSources).isEmpty()}.minByOrNull{candidate->
                val candidateTime=candidate.optLong("time",0L)
                if(time>0L&&candidateTime>0L)abs(candidateTime-time) else Long.MAX_VALUE
            }
            if(target!=null&&timesCompatible(target,event)){
                mergeDisplayInto(target,event)
            }else{
                if(incomingSources.size>1)event.put("_displaySources",orderedSourceLabel(incomingSources))
                out.add(event)
                candidates.add(event)
            }
        }
        return out
    }

    private fun isDisplayMergeable(event:JSONObject):Boolean{
        if(event.optString("url","").isBlank())return false
        return eventSources(event).any{it in displayMergeSources}
    }

    private fun displayMergeKey(event:JSONObject):String{
        val method=event.optString("method","GET").ifBlank{"GET"}.uppercase(Locale.US)
        return "$method\n${event.optString("url","")}"
    }

    private fun timesCompatible(a:JSONObject,b:JSONObject):Boolean{
        val ta=a.optLong("time",0L);val tb=b.optLong("time",0L)
        if(ta<=0L||tb<=0L)return false
        if(abs(ta-tb)>displayMergeWindowMs)return false
        val sa=a.optInt("status",0);val sb=b.optInt("status",0)
        return sa<=0||sb<=0||sa==sb
    }

    private fun eventSources(event:JSONObject):LinkedHashSet<String>{
        val out=linkedSetOf<String>()
        event.optString("source","").takeIf{it.isNotBlank()}?.let{out.add(it)}
        val captured=event.optJSONArray("capturedSources")
        if(captured!=null)for(i in 0 until captured.length())captured.optString(i).takeIf{it.isNotBlank()}?.let{out.add(it)}
        event.optString("_displaySources","").split('+').map{it.trim()}.filter{it.isNotBlank()}.forEach{out.add(it)}
        return out
    }

    private fun orderedSourceLabel(sources:Set<String>):String{
        val ordered=displaySourceOrder.filter{it in sources}.toMutableList()
        sources.filter{it !in ordered}.sorted().forEach{ordered.add(it)}
        return ordered.joinToString(" + ")
    }

    private fun displaySource(event:JSONObject)=event.optString("_displaySources",event.optString("source",""))
    private fun sourceSummary(event:JSONObject):String{val sources=eventSources(event);return if(mergeMode&&sources.size>1)"${sources.size} src" else displaySource(event)}

    private fun mergeDisplayInto(target:JSONObject,incoming:JSONObject){
        val targetSnapshot=JSONObject(target.toString()).apply{remove("_mergedEvents");remove("_displaySources")}
        val raw=target.optJSONArray("_mergedEvents")?:JSONArray().also{it.put(targetSnapshot);target.put("_mergedEvents",it)}
        val incomingRaw=incoming.optJSONArray("_mergedEvents")
        if(incomingRaw!=null){for(i in 0 until incomingRaw.length())incomingRaw.optJSONObject(i)?.let{raw.put(JSONObject(it.toString()))}}
        else raw.put(JSONObject(incoming.toString()).apply{remove("_displaySources")})

        val sources=eventSources(target).apply{addAll(eventSources(incoming))}
        val captured=JSONArray();sources.forEach{captured.put(it)}
        target.put("capturedSources",captured)
        target.put("_displaySources",orderedSourceLabel(sources))

        val targetTime=target.optLong("time",0L);val incomingTime=incoming.optLong("time",0L)
        if(incomingTime>0L&&(targetTime<=0L||incomingTime<targetTime))target.put("time",incomingTime)

        val keys=incoming.keys()
        while(keys.hasNext()){
            val key=keys.next()
            if(key in setOf("source","url","method","time","capturedSources","_displaySources","_mergedEvents"))continue
            val value=incoming.opt(key)?:continue
            if(value==JSONObject.NULL)continue
            val current=target.opt(key)
            if(current is JSONObject&&value is JSONObject){mergeJsonObjects(current,value);continue}
            if(!meaningful(current)||betterNumeric(current,value))target.put(key,value)
        }
    }

    private fun mergeJsonObjects(target:JSONObject,incoming:JSONObject){
        val keys=incoming.keys()
        while(keys.hasNext()){
            val key=keys.next();val value=incoming.opt(key)?:continue
            if(value==JSONObject.NULL)continue
            val current=target.opt(key)
            if(current is JSONObject&&value is JSONObject)mergeJsonObjects(current,value)
            else if(!meaningful(current)||betterNumeric(current,value))target.put(key,value)
        }
    }

    private fun meaningful(value:Any?):Boolean=when(value){null,JSONObject.NULL->false;is String->value.isNotBlank()&&value!="—";is Number->value.toDouble()!=0.0;else->true}
    private fun betterNumeric(current:Any?,incoming:Any?):Boolean=current is Number&&incoming is Number&&current.toDouble()==0.0&&incoming.toDouble()!=0.0

    private fun responseKind(event:JSONObject):String{
        if(event.optString("source","") in setOf("js-file","script-archive","source-map"))return "JS"
        val headers=event.optJSONObject("responseHeaders")
        val mime=event.optString("mimeType",headerValue(headers,"Content-Type")).substringBefore(';').trim().lowercase(Locale.US)
        when{
            mime.contains("json") -> return "JSON"
            mime.contains("html") -> return "HTML"
            mime.contains("javascript")||mime.contains("ecmascript") -> return "JS"
            mime.contains("css") -> return "CSS"
            mime.startsWith("image/") -> return "IMG"
            mime.contains("pdf") -> return "PDF"
            mime.startsWith("text/")||mime.contains("xml")||mime.contains("x-www-form-urlencoded") -> return "TEXT"
            mime.contains("octet-stream")||mime.startsWith("font/")||mime.startsWith("audio/")||mime.startsWith("video/")||mime.contains("zip")||mime.contains("gzip") -> return "BIN"
        }
        val path=event.optString("url","").substringBefore('?').substringBefore('#').lowercase(Locale.US)
        when{
            path.endsWith(".json")||path.endsWith(".map") -> return "JSON"
            path.endsWith(".html")||path.endsWith(".htm") -> return "HTML"
            path.endsWith(".js")||path.endsWith(".mjs") -> return "JS"
            path.endsWith(".css") -> return "CSS"
            path.endsWith(".png")||path.endsWith(".jpg")||path.endsWith(".jpeg")||path.endsWith(".gif")||path.endsWith(".webp")||path.endsWith(".svg")||path.endsWith(".ico") -> return "IMG"
            path.endsWith(".pdf") -> return "PDF"
            path.endsWith(".txt")||path.endsWith(".xml")||path.endsWith(".csv") -> return "TEXT"
            path.endsWith(".woff")||path.endsWith(".woff2")||path.endsWith(".ttf")||path.endsWith(".otf")||path.endsWith(".zip")||path.endsWith(".gz")||path.endsWith(".mp4")||path.endsWith(".webm")||path.endsWith(".mp3") -> return "BIN"
        }
        val body=event.optString("responseBody",event.optString("data","")).trim()
        if(body=="[binary]"||body=="[non-text response]")return "BIN"
        if(body.startsWith("{")||body.startsWith("["))return "JSON"
        if(body.startsWith("<!doctype",true)||body.startsWith("<html",true)||body.startsWith("<body",true))return "HTML"
        if(body.isNotBlank())return "TEXT"
        return if(mime.isNotBlank())"BIN" else "OTHER"
    }

    private fun hasRequestBody(event:JSONObject)=event.optString("requestBody","").isNotBlank()
    private fun isCached(event:JSONObject):Boolean{
        val cache=event.optString("cache","")
        if(cache.isNotBlank()&&!cache.equals("network",true))return true
        return event.has("transferSize")&&event.optLong("transferSize",-1L)==0L&&event.optLong("decodedBodySize",0L)>0L
    }
    private fun isRedirect(event:JSONObject):Boolean=event.optBoolean("redirected",false)||event.optString("redirectURL","").isNotBlank()||event.optInt("status",0) in 300..399
    private fun rowFlags(event:JSONObject):String=buildList{if(hasRequestBody(event))add("BODY");if(isCached(event))add("CACHE");if(isRedirect(event))add("REDIRECT")}.joinToString(" ")

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
        val originalTexts=java.util.IdentityHashMap<TextView,CharSequence>()
        var decoded=false

        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(bg);setPadding(dp(10),dp(8),dp(10),dp(14))}
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        actions.addView(compactButton("Копировать URL"){copyText("URL",url)},LinearLayout.LayoutParams(0,dp(40),1f).apply{marginEnd=dp(4)})
        actions.addView(compactButton("Копировать cURL"){copyText("cURL",buildCurl(event))},LinearLayout.LayoutParams(0,dp(40),1f).apply{marginStart=dp(4)})
        content.addView(actions,LinearLayout.LayoutParams(-1,dp(40)).apply{setMargins(0,0,0,dp(6))})
        val decodeButton=compactButton("URL-ДЕКОДИРОВАТЬ") {}
        content.addView(decodeButton,LinearLayout.LayoutParams(-1,dp(40)).apply{setMargins(0,0,0,dp(4))})
        addSection(content,"REQUEST",highlightPlain(requestText,query))
        addSection(content,"RESPONSE",highlightPlain(responseText,query))
        val sources=eventSources(event)
        if(sources.size>1)addSection(content,"SOURCES",highlightPlain(orderedSourceLabel(sources),query))

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

        val mergedRaw=event.optJSONArray("_mergedEvents")
        val rawLabel=if(mergedRaw!=null&&mergedRaw.length()>1)"RAW EVENTS  ▾" else "RAW EVENT  ▾"
        val rawButton=compactButton(rawLabel){}
        val rawText=if(mergedRaw!=null&&mergedRaw.length()>0)mergedRaw.toString(2) else event.toString(2)
        val rawView=codeText(highlightPlain(rawText,query)).apply{visibility=View.GONE}
        rawButton.setOnClickListener{rawView.visibility=if(rawView.visibility==View.VISIBLE)View.GONE else View.VISIBLE;rawButton.text=if(rawView.visibility==View.VISIBLE)rawLabel.replace('▾','▴') else rawLabel}
        content.addView(rawButton,LinearLayout.LayoutParams(-1,dp(38)).apply{setMargins(0,dp(9),0,dp(5))})
        content.addView(rawView)

        captureDisplayTexts(content,originalTexts,decodeButton)
        decodeButton.setOnClickListener{
            decoded=!decoded
            applyDecodedMode(content,originalTexts,decodeButton,decoded)
            decodeButton.text=if(decoded)"URL-ДЕКОДИРОВАНО · ВЕРНУТЬ" else "URL-ДЕКОДИРОВАТЬ"
        }

        val sv=ScrollView(this).apply{setBackgroundColor(bg);addView(content)}
        AlertDialog.Builder(this).setTitle("Детали запроса").setView(sv).setPositiveButton("Закрыть",null).show()
    }

    private fun copyText(label:String,value:String){
        val manager=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label,value))
        Toast.makeText(this,"$label скопирован",Toast.LENGTH_SHORT).show()
    }

    private fun shellQuote(value:String)="'"+value.replace("'","'\"'\"'")+"'"
    private fun buildCurl(event:JSONObject):String=buildString{
        val method=event.optString("method","GET").ifBlank{"GET"}.uppercase(Locale.US)
        append("curl -X ").append(shellQuote(method)).append(" ").append(shellQuote(event.optString("url","")))
        val headers=event.optJSONObject("requestHeaders")?:event.optJSONObject("headers")
        if(headers!=null){val keys=headers.keys();while(keys.hasNext()){val key=keys.next();append(" \\\n  -H ").append(shellQuote("$key: ${headers.optString(key,"")}"))}}
        val body=event.optString("requestBody","")
        if(body.isNotBlank())append(" \\\n  --data-raw ").append(shellQuote(body))
    }

    private fun captureDisplayTexts(view:View, originals:MutableMap<TextView,CharSequence>, skip:View){
        when{
            view===skip -> Unit
            view is Button -> Unit
            view is TextView -> originals[view]=SpannableString(view.text)
            view is ViewGroup -> for(i in 0 until view.childCount)captureDisplayTexts(view.getChildAt(i),originals,skip)
        }
    }

    private fun applyDecodedMode(view:View, originals:Map<TextView,CharSequence>, skip:View, decoded:Boolean){
        when{
            view===skip -> Unit
            view is Button -> Unit
            view is TextView -> {
                val original=originals[view]?:view.text
                view.text=if(decoded)decodePercentText(original.toString()) else original
            }
            view is ViewGroup -> for(i in 0 until view.childCount)applyDecodedMode(view.getChildAt(i),originals,skip,decoded)
        }
    }

    private fun buildRequestText(event:JSONObject,cookies:String)=buildString{
        append("Method: ").append(event.optString("method","—")).append('\n')
        append("URL: ").append(event.optString("url","—")).append('\n')
        appendUrlParts(this,event.optString("url",""))
        append("Source: ").append(displaySource(event).ifBlank{"—"}).append('\n')
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
        append("Content type: ").append(responseKind(event)).append('\n')
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
            val row=(convertView as? LinearLayout)?.takeIf{it.findViewWithTag<TextView>("top")!=null}?:LinearLayout(this@NetworkDebuggerActivity).apply{
                orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(7),dp(10),dp(7));background=rounded(panel,0f,Color.rgb(26,48,39))
                addView(LinearLayout(this@NetworkDebuggerActivity).apply{
                    orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
                    addView(TextView(this@NetworkDebuggerActivity).apply{tag="top";textSize=12f;typeface=Typeface.DEFAULT_BOLD;maxLines=2},LinearLayout.LayoutParams(0,-2,1f))
                    addView(TextView(this@NetworkDebuggerActivity).apply{tag="kind";textSize=10f;typeface=Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER;setPadding(dp(7),dp(3),dp(7),dp(3));background=rounded(panel2,7f,Color.rgb(50,76,65))},LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
                })
                addView(TextView(this@NetworkDebuggerActivity).apply{tag="url";textSize=11f;maxLines=2;setPadding(0,dp(2),0,0)})
            }
            val top=row.findViewWithTag<TextView>("top");val url=row.findViewWithTag<TextView>("url");val kind=row.findViewWithTag<TextView>("kind")
            val status=e.optInt("status",0);val source=sourceSummary(e);val method=e.optString("method",if(isJsEvent(e))"JS" else e.optString("source","").uppercase(Locale.US));val whenText=if(e.has("time"))listTime(e.optLong("time")) else "--:--:--.---";val flags=rowFlags(e)
            top.text=buildString{append(whenText).append("  ");append(if(isJsEvent(e))"JS" else method);if(status>0)append("  ").append(status);if(e.has("duration"))append("  ").append(e.optLong("duration")).append(" ms");if(e.has("responseSize"))append("  ").append(e.optLong("responseSize")).append(" B");append("  · ").append(source);if(flags.isNotBlank())append("  ").append(flags)}
            top.setTextColor(if(status>=400||e.has("error"))bad else if(isJsEvent(e)||status in 200..399)accent else textColor)
            kind.text=responseKind(e);kind.setTextColor(if(kind.text=="OTHER")muted else cyan)
            url.text=e.optString("url",e.optString("message","—"));url.setTextColor(muted);return row
        }
    }
}
