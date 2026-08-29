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
    private val line = Color.rgb(50,76,65)
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
    private lateinit var mergeButton: Button
    private lateinit var apiButton: Button
    private lateinit var domainSpinner: Spinner
    private lateinit var typeSpinner: Spinner
    private lateinit var methodSpinner: Spinner
    private lateinit var search: EditText

    private val allItems = mutableListOf<JSONObject>()
    private val items = mutableListOf<JSONObject>()
    private val domains = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val listTimeFormat = SimpleDateFormat("HH:mm:ss.SSS",Locale.US)
    private var lastRevision = -1L
    private var mergeMode = false
    private var apiOnly = false
    private var pendingBinary: ByteArray? = null
    private var pendingBinaryName = "response.bin"
    private var pendingBinaryMime = "application/octet-stream"

    private val displayMergeSources = setOf("webview","resource-copy","resource-timing","fetch","fetch-meta","xhr","xhr-meta")
    private val displaySourceOrder = listOf("webview","fetch","fetch-meta","xhr","xhr-meta","resource-timing","resource-copy")
    private val displayMergeWindowMs = 1200L
    private val strongDuplicateWindowMs = 90L
    private val typeFilters = listOf("ALL","JSON","HTML","JS","CSS","IMG","PDF","TEXT","BIN","OTHER")
    private val methodFilters = listOf("ALL","GET","POST","PUT","PATCH","DELETE","OPTIONS","HEAD","WS","SSE","OTHER")

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val toolbarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(panel)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8),dp(5),dp(8),dp(5))
        }
        recordButton = compactButton("") {
            NetworkDebugStore.recording = !NetworkDebugStore.recording
            updateRecordButton()
        }
        updateRecordButton()
        toolbar.addView(recordButton)
        toolbar.addView(compactButton("Очистить") { NetworkDebugStore.clear(); reload() })
        toolbar.addView(compactButton("ZIP") { exportZip() })
        mergeButton = compactButton("") { mergeMode = !mergeMode; updateMergeButton(); applyFilters() }
        toolbar.addView(mergeButton)
        updateMergeButton()
        apiButton = compactButton("") { apiOnly = !apiOnly; updateApiButton(); applyFilters() }
        toolbar.addView(apiButton)
        updateApiButton()
        toolbarScroll.addView(toolbar)
        root.addView(toolbarScroll, LinearLayout.LayoutParams(-1,dp(48)))

        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(bg)
            isFillViewport = true
        }
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8),dp(5),dp(8),dp(5))
        }

        domainSpinner = Spinner(this,Spinner.MODE_DROPDOWN)
        typeSpinner = Spinner(this,Spinner.MODE_DROPDOWN)
        methodSpinner = Spinner(this,Spinner.MODE_DROPDOWN)

        typeSpinner.adapter = spinnerAdapter(typeFilters)
        attachFilterListener(typeSpinner)
        methodSpinner.adapter = spinnerAdapter(methodFilters)
        attachFilterListener(methodSpinner)

        filterRow.addView(filterCard("ДОМЕН",domainSpinner,dp(178)))
        filterRow.addView(filterCard("ТИП ОТВЕТА",typeSpinner,dp(104)),LinearLayout.LayoutParams(dp(104),dp(56)).apply{marginStart=dp(6)})
        filterRow.addView(filterCard("МЕТОД",methodSpinner,dp(108)),LinearLayout.LayoutParams(dp(108),dp(56)).apply{marginStart=dp(6)})

        filterScroll.addView(filterRow)
        root.addView(filterScroll,LinearLayout.LayoutParams(-1,dp(66)))

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8),dp(2),dp(8),dp(4))
        }
        search = EditText(this).apply {
            hint = "Поиск по URL, headers, body..."
            setHintTextColor(muted)
            setTextColor(textColor)
            textSize = 12f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = rounded(panel2,11f,line)
            setPadding(dp(10),0,dp(10),0)
            setOnEditorActionListener { _, id, _ -> if(id==EditorInfo.IME_ACTION_SEARCH){applyFilters();true}else false }
        }
        searchRow.addView(search,LinearLayout.LayoutParams(0,dp(40),1f))
        searchRow.addView(compactButton("⌕"){applyFilters()},LinearLayout.LayoutParams(dp(44),dp(40)).apply{marginStart=dp(5)})
        root.addView(searchRow,LinearLayout.LayoutParams(-1,dp(46)))

        counter = TextView(this).apply {
            setTextColor(muted)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12),dp(2),dp(12),dp(5))
        }
        root.addView(counter)

        list = ListView(this).apply {
            divider = null
            dividerHeight = dp(2)
            setBackgroundColor(bg)
            setPadding(dp(4),0,dp(4),dp(4))
            clipToPadding = false
        }
        adapter = EventAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _,_,position,_ ->
            val event = items[position]
            if (!isActionEvent(event)) showDetails(event,search.text.toString().trim())
        }
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root){v,i->
            val bars:Insets=i.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            i
        }
        ViewCompat.requestApplyInsets(root)
        reload()
    }

    override fun onResume(){
        super.onResume()
        (application as? WebResearchApp)?.syncNetworkStore()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause(){
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun updateRecordButton(){
        if(::recordButton.isInitialized){
            recordButton.text=if(NetworkDebugStore.recording)"● REC" else "○ STOP"
            recordButton.setTextColor(if(NetworkDebugStore.recording)accent else muted)
        }
    }

    private fun updateMergeButton(){
        if(::mergeButton.isInitialized){
            mergeButton.text=if(mergeMode)"Объединено ✓" else "Раздельно"
            mergeButton.setTextColor(if(mergeMode)accent else textColor)
        }
    }

    private fun updateApiButton(){
        if(::apiButton.isInitialized){
            apiButton.text=if(apiOnly)"API ✓" else "API"
            apiButton.setTextColor(if(apiOnly)cyan else textColor)
        }
    }

    private fun reload(){
        allItems.clear()
        allItems.addAll(NetworkDebugStore.snapshot().asReversed())
        rebuildDynamicFilters()
        applyFilters()
    }

    private fun selected(spinner:Spinner, fallback:String):String =
        if(spinner.selectedItem!=null) spinner.selectedItem.toString() else fallback

    private fun rebuildDynamicFilters(){
        val selectedDomain=if(::domainSpinner.isInitialized)selected(domainSpinner,"Все домены") else "Все домены"

        domains.clear()
        domains.add("Все домены")
        allItems.mapNotNull{hostOf(eventLocation(it))}.distinct().sorted().forEach{domains.add(it)}
        domainSpinner.adapter=spinnerAdapter(domains)
        domainSpinner.setSelection(domains.indexOf(selectedDomain).takeIf{it>=0}?:0)
        attachFilterListener(domainSpinner)
    }

    private fun filterCard(label:String,spinner:Spinner,width:Int)=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL
        gravity=Gravity.CENTER_VERTICAL
        background=rounded(panel,12f,line)
        setPadding(dp(9),dp(4),dp(7),dp(4))
        addView(TextView(this@NetworkDebuggerActivity).apply{
            text=label
            setTextColor(muted)
            textSize=7.5f
            typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
            letterSpacing=.08f
            setPadding(dp(2),0,dp(2),0)
        },LinearLayout.LayoutParams(-1,dp(14)))
        spinner.background=rounded(panel2,8f,Color.TRANSPARENT)
        spinner.popupBackgroundDrawable=rounded(panel,12f,line)
        spinner.dropDownVerticalOffset=dp(4)
        addView(spinner,LinearLayout.LayoutParams(-1,dp(32)))
        layoutParams=LinearLayout.LayoutParams(width,dp(56))
    }

    private fun spinnerAdapter(values:List<String>)=object:ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,values){
        private fun selectedView(position:Int)=TextView(this@NetworkDebuggerActivity).apply{
            text=getItem(position)
            setTextColor(textColor)
            textSize=11f
            typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
            gravity=Gravity.CENTER_VERTICAL
            maxLines=1
            setPadding(dp(8),0,dp(18),0)
            background=Color.TRANSPARENT.toDrawableCompat()
        }
        private fun dropView(position:Int)=TextView(this@NetworkDebuggerActivity).apply{
            text=getItem(position)
            setTextColor(if(position==0)muted else textColor)
            textSize=11f
            typeface=Typeface.MONOSPACE
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(14),dp(11),dp(14),dp(11))
            background=rounded(panel2,8f,line)
        }
        override fun getView(position:Int,convertView:View?,parent:ViewGroup)=selectedView(position)
        override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup)=dropView(position)
    }

    private fun Int.toDrawableCompat()=android.graphics.drawable.ColorDrawable(this)

    private fun attachFilterListener(spinner:Spinner){
        spinner.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){applyFilters()}
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){ }
        }
    }

    private fun applyFilters(){
        val domain=if(::domainSpinner.isInitialized)selected(domainSpinner,"Все домены") else "Все домены"
        val type=if(::typeSpinner.isInitialized)selected(typeSpinner,"ALL") else "ALL"
        val method=if(::methodSpinner.isInitialized)selected(methodSpinner,"ALL") else "ALL"
        val q=if(::search.isInitialized)search.text.toString().trim() else ""

        val displayItems=if(mergeMode)mergeForDisplay(allItems) else allItems.map{JSONObject(it.toString())}
        markChanged(displayItems)

        items.clear()
        displayItems.filterTo(items){event->
            val action=isActionEvent(event)
            val domainOk=domain=="Все домены"||hostOf(eventLocation(event))==domain
            val typeOk=type=="ALL"||(action&&type=="OTHER")||(!action&&responseKind(event)==type)
            val methodValue=methodOf(event)
            val methodOk=method=="ALL"||methodValue==method||(method=="OTHER"&&methodValue !in methodFilters)
            val apiOk=!apiOnly||isApiRelevant(event)
            val searchOk=q.isBlank()||event.toString().contains(q,true)
            domainOk&&typeOk&&methodOk&&apiOk&&searchOk
        }

        if(::adapter.isInitialized)adapter.notifyDataSetChanged()
        if(::counter.isInitialized){
            val requests=displayItems.count{isRequestEvent(it)}
            val actions=displayItems.count{isActionEvent(it)}
            val errors=displayItems.count{it.has("error")||it.optInt("status",0)>=400}
            val prefix=if(mergeMode)"${allItems.size} событий → ${displayItems.size} строк" else "${allItems.size} событий"
            val filtered=domain!="Все домены"||type!="ALL"||method!="ALL"||apiOnly||q.isNotBlank()
            counter.text="$prefix · $requests запросов · $actions действий · $errors ошибок${if(filtered)" · показано ${items.size}" else ""}"
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
            if(time>0L)candidates.removeAll{candidate->
                val candidateTime=candidate.optLong("time",0L)
                candidateTime>0L&&abs(candidateTime-time)>displayMergeWindowMs
            }
            val incomingSources=eventSources(event)
            val target=candidates.filter{candidate->eventSources(candidate).intersect(incomingSources).isEmpty()}.minByOrNull{candidate->
                val candidateTime=candidate.optLong("time",0L)
                if(time>0L&&candidateTime>0L)abs(candidateTime-time) else Long.MAX_VALUE
            }
            if(target!=null&&timesCompatible(target,event)){
                val targetTime=target.optLong("time",0L)
                val delta=if(time>0L&&targetTime>0L)abs(time-targetTime) else displayMergeWindowMs
                mergeDisplayInto(target,event,delta)
            }else{
                if(incomingSources.size>1)event.put("_displaySources",orderedSourceLabel(incomingSources))
                out.add(event)
                candidates.add(event)
            }
        }
        return collapseStrongDuplicates(out)
    }

    private fun collapseStrongDuplicates(source:List<JSONObject>):List<JSONObject>{
        val out=mutableListOf<JSONObject>()
        val recent=HashMap<String,MutableList<JSONObject>>()
        source.forEach{event->
            if(!isDisplayMergeable(event)){
                out.add(event)
                return@forEach
            }
            val key=displayMergeKey(event)
            val time=event.optLong("time",0L)
            val candidates=recent.getOrPut(key){mutableListOf()}
            if(time>0L)candidates.removeAll{candidate->
                val ct=candidate.optLong("time",0L)
                ct>0L&&abs(ct-time)>strongDuplicateWindowMs
            }
            val target=candidates.minByOrNull{candidate->
                val ct=candidate.optLong("time",0L)
                if(time>0L&&ct>0L)abs(ct-time) else Long.MAX_VALUE
            }
            if(target!=null&&strongDuplicateCandidate(target,event)){
                val delta=abs(target.optLong("time",0L)-time)
                mergeDisplayInto(target,event,delta)
                target.put("_deduplicated",true)
            }else{
                out.add(event)
                candidates.add(event)
            }
        }
        return out
    }

    private fun strongDuplicateCandidate(a:JSONObject,b:JSONObject):Boolean{
        val ta=a.optLong("time",0L)
        val tb=b.optLong("time",0L)
        if(ta<=0L||tb<=0L||abs(ta-tb)>strongDuplicateWindowMs)return false
        val sa=a.optInt("status",0)
        val sb=b.optInt("status",0)
        if(sa>0&&sb>0&&sa!=sb)return false

        val sourcesA=eventSources(a)
        val sourcesB=eventSources(b)
        if(sourcesA==sourcesB)return false
        if(sourcesA.intersect(sourcesB).isEmpty())return false

        var evidence=0
        val sizeA=bestResponseSize(a)
        val sizeB=bestResponseSize(b)
        if(sizeA>0L&&sizeB>0L){if(sizeA!=sizeB)return false else evidence++}

        val durationA=a.optDouble("duration",-1.0)
        val durationB=b.optDouble("duration",-1.0)
        if(durationA>=0.0&&durationB>=0.0){
            if(abs(durationA-durationB)>3.0)return false
            evidence++
        }

        val bodyA=a.optString("responseBody",a.optString("data","")).takeIf{it.isNotBlank()&&it!="[binary]"&&it!="[non-text response]"}
        val bodyB=b.optString("responseBody",b.optString("data","")).takeIf{it.isNotBlank()&&it!="[binary]"&&it!="[non-text response]"}
        if(bodyA!=null&&bodyB!=null){if(bodyA!=bodyB)return false else evidence+=2}

        val kindA=responseKind(a)
        val kindB=responseKind(b)
        if(kindA!="OTHER"&&kindB!="OTHER"){if(kindA!=kindB)return false else evidence++}

        val subset=sourcesA.containsAll(sourcesB)||sourcesB.containsAll(sourcesA)
        return evidence>=2||(subset&&evidence>=1&&abs(ta-tb)<=25L)
    }

    private fun bestResponseSize(event:JSONObject):Long{
        listOf("responseSize","decodedBodySize","encodedBodySize","transferSize").forEach{key->
            if(event.has(key)){
                val value=event.optLong(key,-1L)
                if(value>0L)return value
            }
        }
        return -1L
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
        val ta=a.optLong("time",0L)
        val tb=b.optLong("time",0L)
        if(ta<=0L||tb<=0L)return false
        if(abs(ta-tb)>displayMergeWindowMs)return false
        val sa=a.optInt("status",0)
        val sb=b.optInt("status",0)
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

    private fun sourceSummary(event:JSONObject):String{
        val sources=eventSources(event)
        return if(mergeMode&&sources.size>1)"${sources.size} src" else displaySource(event)
    }

    private fun mergeDisplayInto(target:JSONObject,incoming:JSONObject,delta:Long){
        val targetSnapshot=JSONObject(target.toString()).apply{remove("_mergedEvents");remove("_displaySources");remove("_mergeConfidence")}
        val raw=target.optJSONArray("_mergedEvents")?:JSONArray().also{it.put(targetSnapshot);target.put("_mergedEvents",it)}
        val incomingRaw=incoming.optJSONArray("_mergedEvents")
        if(incomingRaw!=null){
            for(i in 0 until incomingRaw.length())incomingRaw.optJSONObject(i)?.let{raw.put(JSONObject(it.toString()))}
        }else raw.put(JSONObject(incoming.toString()).apply{remove("_displaySources");remove("_mergeConfidence")})

        val sources=eventSources(target).apply{addAll(eventSources(incoming))}
        val captured=JSONArray()
        sources.forEach{captured.put(it)}
        target.put("capturedSources",captured)
        target.put("_displaySources",orderedSourceLabel(sources))

        val confidence=if(delta<=250L)"HIGH" else "MEDIUM"
        val oldConfidence=target.optString("_mergeConfidence","")
        target.put("_mergeConfidence",if(oldConfidence=="MEDIUM"||confidence=="MEDIUM")"MEDIUM" else "HIGH")

        val targetTime=target.optLong("time",0L)
        val incomingTime=incoming.optLong("time",0L)
        if(incomingTime>0L&&(targetTime<=0L||incomingTime<targetTime))target.put("time",incomingTime)

        val keys=incoming.keys()
        while(keys.hasNext()){
            val key=keys.next()
            if(key in setOf("source","url","method","time","capturedSources","_displaySources","_mergedEvents","_mergeConfidence"))continue
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
            val key=keys.next()
            val value=incoming.opt(key)?:continue
            if(value==JSONObject.NULL)continue
            val current=target.opt(key)
            if(current is JSONObject&&value is JSONObject)mergeJsonObjects(current,value)
            else if(!meaningful(current)||betterNumeric(current,value))target.put(key,value)
        }
    }

    private fun meaningful(value:Any?):Boolean=when(value){
        null,JSONObject.NULL->false
        is String->value.isNotBlank()&&value!="—"
        is Number->value.toDouble()!=0.0
        else->true
    }

    private fun betterNumeric(current:Any?,incoming:Any?):Boolean=
        current is Number&&incoming is Number&&current.toDouble()==0.0&&incoming.toDouble()!=0.0

    private fun markChanged(events:List<JSONObject>){
        val previous=HashMap<String,String>()
        events.asReversed().forEach{event->
            if(!isRequestEvent(event))return@forEach
            val fingerprint=responseFingerprint(event)
            if(fingerprint.isBlank())return@forEach
            val sourcePart=if(mergeMode)"" else "\n${event.optString("source","")}"
            val key="${methodOf(event)}\n${event.optString("url","")}$sourcePart"
            val old=previous[key]
            if(old!=null&&old!=fingerprint)event.put("_changed",true)
            previous[key]=fingerprint
        }
    }

    private fun responseFingerprint(event:JSONObject):String{
        val body=event.optString("responseBody",event.optString("data",""))
        val headers=event.optJSONObject("responseHeaders")
        val etag=headerValue(headers,"ETag")
        val lastModified=headerValue(headers,"Last-Modified")
        val hasEvidence=body.isNotBlank()||event.has("status")||event.has("responseSize")||event.has("decodedBodySize")||etag.isNotBlank()||lastModified.isNotBlank()
        if(!hasEvidence)return ""
        return buildString{
            append(event.optInt("status",0)).append('|')
            append(responseKind(event)).append('|')
            append(event.optLong("responseSize",event.optLong("decodedBodySize",-1L))).append('|')
            append(etag).append('|').append(lastModified).append('|')
            if(body.isNotBlank())append(body.hashCode())
        }
    }

    private fun responseKind(event:JSONObject):String{
        if(isActionEvent(event))return "ACTION"
        if(event.optString("source","") in setOf("js-file","script-archive","source-map"))return "JS"
        val headers=event.optJSONObject("responseHeaders")
        val mime=event.optString("mimeType",headerValue(headers,"Content-Type")).substringBefore(';').trim().lowercase(Locale.US)
        when{
            mime.contains("json")->return "JSON"
            mime.contains("html")->return "HTML"
            mime.contains("javascript")||mime.contains("ecmascript")->return "JS"
            mime.contains("css")->return "CSS"
            mime.startsWith("image/")->return "IMG"
            mime.contains("pdf")->return "PDF"
            mime.startsWith("text/")||mime.contains("xml")||mime.contains("x-www-form-urlencoded")->return "TEXT"
            mime.contains("octet-stream")||mime.startsWith("font/")||mime.startsWith("audio/")||mime.startsWith("video/")||mime.contains("zip")||mime.contains("gzip")->return "BIN"
        }
        val path=event.optString("url","").substringBefore('?').substringBefore('#').lowercase(Locale.US)
        when{
            path.endsWith(".json")||path.endsWith(".map")->return "JSON"
            path.endsWith(".html")||path.endsWith(".htm")->return "HTML"
            path.endsWith(".js")||path.endsWith(".mjs")->return "JS"
            path.endsWith(".css")->return "CSS"
            path.endsWith(".png")||path.endsWith(".jpg")||path.endsWith(".jpeg")||path.endsWith(".gif")||path.endsWith(".webp")||path.endsWith(".svg")||path.endsWith(".ico")->return "IMG"
            path.endsWith(".pdf")->return "PDF"
            path.endsWith(".txt")||path.endsWith(".xml")||path.endsWith(".csv")->return "TEXT"
            path.endsWith(".woff")||path.endsWith(".woff2")||path.endsWith(".ttf")||path.endsWith(".otf")||path.endsWith(".zip")||path.endsWith(".gz")||path.endsWith(".mp4")||path.endsWith(".webm")||path.endsWith(".mp3")->return "BIN"
        }
        val body=event.optString("responseBody",event.optString("data","")).trim()
        if(body=="[binary]"||body=="[non-text response]")return "BIN"
        if(body.startsWith("{")||body.startsWith("["))return "JSON"
        if(body.startsWith("<!doctype",true)||body.startsWith("<html",true)||body.startsWith("<body",true))return "HTML"
        if(body.isNotBlank())return "TEXT"
        return if(mime.isNotBlank())"BIN" else "OTHER"
    }

    private fun eventLocation(event:JSONObject):String =
        event.optString("url","").ifBlank{event.optString("page",event.optString("newURL",""))}

    private fun methodOf(event:JSONObject):String{
        val source=event.optString("source","")
        if(source.startsWith("websocket"))return "WS"
        if(source.startsWith("sse"))return "SSE"
        if(isActionEvent(event))return "ACTION"
        return event.optString("method","").ifBlank{"OTHER"}.uppercase(Locale.US)
    }

    private fun hasRequestBody(event:JSONObject)=event.optString("requestBody","").isNotBlank()

    private fun isCached(event:JSONObject):Boolean{
        val cache=event.optString("cache","")
        if(cache.isNotBlank()&&!cache.equals("network",true))return true
        return event.has("transferSize")&&event.optLong("transferSize",-1L)==0L&&event.optLong("decodedBodySize",0L)>0L
    }

    private fun isRedirect(event:JSONObject):Boolean=
        event.optBoolean("redirected",false)||event.optString("redirectURL","").isNotBlank()||event.optInt("status",0) in 300..399

    private fun hasAuth(event:JSONObject):Boolean{
        val headers=event.optJSONObject("requestHeaders")?:event.optJSONObject("headers")?:return false
        return headerValue(headers,"Authorization").isNotBlank()||headerValue(headers,"Proxy-Authorization").isNotBlank()
    }

    private fun rowFlags(event:JSONObject):String=buildList{
        if(eventSources(event).size>1)add(if(event.optString("_mergeConfidence")=="MEDIUM")"~MERGE" else "✓MERGE")
        if(hasRequestBody(event))add("BODY")
        if(isCached(event))add("CACHE")
        if(isRedirect(event))add("REDIRECT")
        if(hasAuth(event))add("AUTH")
        if(event.optBoolean("_changed",false))add("CHANGED")
    }.joinToString("  ")

    private fun isApiRelevant(event:JSONObject):Boolean{
        if(isActionEvent(event))return true
        val sources=eventSources(event)
        if(sources.any{it in setOf("fetch","fetch-meta","xhr","xhr-meta","websocket-open","websocket-send","websocket-receive","sse-open","sse-message","beacon")})return true
        val method=methodOf(event)
        if(method in setOf("POST","PUT","PATCH","DELETE","WS","SSE"))return true
        if(hasRequestBody(event))return true
        if(responseKind(event)=="JSON")return true
        val url=event.optString("url","").lowercase(Locale.US)
        return url.contains("/api/")||url.contains("/graphql")||url.contains("/ajax")||url.contains("/rest/")
    }

    private fun isRequestEvent(e:JSONObject)=e.optString("source") in setOf(
        "fetch","fetch-meta","xhr","xhr-meta","webview","resource-copy","resource-timing","navigation","navigation-timing","new-window",
        "websocket-open","websocket-send","websocket-receive","sse-open","sse-message","beacon","js-file","script-archive"
    )

    private fun isActionEvent(e:JSONObject)=e.optString("source","")=="user-action"

    private fun isJsEvent(e:JSONObject):Boolean{
        val u=e.optString("url","").substringBefore('?').lowercase(Locale.US)
        return e.optString("source") in setOf("js-file","script-archive","source-map")||u.endsWith(".js")||u.endsWith(".mjs")||e.optString("mimeType","").contains("javascript",true)
    }

    private fun hostOf(url:String):String?=try{
        if(url.startsWith("http://")||url.startsWith("https://"))URL(url).host else null
    }catch(_:Exception){null}

    private fun showDetails(event:JSONObject,query:String){
        val url=event.optString("url","")
        val requestCookies=if(url.startsWith("http"))CookieManager.getInstance().getCookie(url).orEmpty() else ""
        val responseHeaders=event.optJSONObject("responseHeaders")
        val mime=event.optString("mimeType",headerValue(responseHeaders,"Content-Type")).substringBefore(';').trim()
        val responseBody=event.optString("responseBody",event.optString("data",""))
        val requestHeadersList=requestHeaderPairs(event)
        val responseHeadersList=responseHeaderPairs(event)
        val bytes=responseBytes(event)
        val binary=bytes!=null&&bytes.isNotEmpty()&&isBinaryPayload(mime,responseBody,bytes)
        val imageBitmap=if(binary&&bytes!=null)try{BitmapFactory.decodeByteArray(bytes,0,bytes.size)}catch(_:Exception){null}else null
        val originalTexts=java.util.IdentityHashMap<TextView,CharSequence>()
        var decoded=false
        var dialog:AlertDialog?=null

        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(10),dp(10),dp(10),dp(10))
        }

        val header=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            background=rounded(panel,14f,line)
            setPadding(dp(12),dp(10),dp(12),dp(10))
        }
        val titleRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val status=event.optInt("status",0)
        titleRow.addView(TextView(this).apply{
            text=buildString{
                append(methodOf(event))
                if(status>0)append("  ").append(status)
                if(event.has("duration"))append("  ").append(formatDuration(event.optDouble("duration",0.0)))
                if(event.has("responseSize"))append("  ").append(formatBytes(event.optLong("responseSize")))
            }
            setTextColor(if(status>=400||event.has("error"))bad else accent)
            textSize=14f
            typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
        },LinearLayout.LayoutParams(0,-2,1f))
        titleRow.addView(chip(responseKind(event),kindColor(responseKind(event))))
        titleRow.addView(compactButton("✕"){dialog?.dismiss()},LinearLayout.LayoutParams(dp(42),dp(34)).apply{marginStart=dp(7)})
        header.addView(titleRow)
        header.addView(TextView(this).apply{
            text=url
            setTextColor(textColor)
            textSize=11f
            typeface=Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0,dp(7),0,0)
        })
        val flags=rowFlags(event)
        if(flags.isNotBlank())header.addView(TextView(this).apply{
            text=flags
            setTextColor(amber)
            textSize=9f
            typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
            setPadding(0,dp(6),0,0)
        })
        root.addView(header,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,0,0,dp(7))})

        val actionScroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false}
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        actions.addView(detailButton("URL"){copyText("URL",url)})
        actions.addView(detailButton("cURL"){copyText("cURL",buildCurl(event))})
        actions.addView(detailButton("REQUEST"){copyText("REQUEST",buildRequestText(event,requestCookies))})
        actions.addView(detailButton("REQ HEADERS"){copyText("REQUEST HEADERS",formatHeaders(requestHeadersList))})
        actions.addView(detailButton("RESPONSE"){copyText("RESPONSE",buildResponseCopy(event,requestCookies))})
        actions.addView(detailButton("RESP HEADERS"){copyText("RESPONSE HEADERS",formatHeaders(responseHeadersList))})
        if(responseKind(event)=="JSON"&&responseBody.isNotBlank())actions.addView(detailButton("JSON"){copyText("JSON",prettyBody(responseBody,mime))})
        val decodeButton=detailButton("URL DECODE"){}
        actions.addView(decodeButton)
        actionScroll.addView(actions)
        root.addView(actionScroll,LinearLayout.LayoutParams(-1,dp(42)).apply{setMargins(0,0,0,dp(7))})

        val content=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(0,0,0,dp(10))
        }

        val requestPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        requestPanel.addView(codeText(highlightPlain(buildRequestSummary(event),query)))
        requestPanel.addView(subtitle("HEADERS"))
        requestPanel.addView(headerBlock(requestHeadersList,query))
        addCollapsible(content,"REQUEST",true,requestPanel)

        val responsePanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        responsePanel.addView(codeText(highlightPlain(buildResponseSummary(event),query)))
        responsePanel.addView(subtitle("HEADERS"))
        responsePanel.addView(headerBlock(responseHeadersList,query))
        addCollapsible(content,"RESPONSE",true,responsePanel)

        val bodyPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val requestBody=event.optString("requestBody","")
        if(requestBody.isNotBlank()){
            bodyPanel.addView(subtitle("REQUEST BODY"))
            bodyPanel.addView(codeText(decorateResponseBody(requestBody,event.optString("requestMimeType",""),query)))
        }
        bodyPanel.addView(subtitle("RESPONSE BODY"))
        if(binary){
            val info=buildString{
                append(if(imageBitmap!=null)"Image payload\n" else "Binary payload\n")
                append("MIME: ").append(mime.ifBlank{"application/octet-stream"}).append('\n')
                append("Size: ").append(formatBytes(bytes!!.size.toLong())).append(" (").append(bytes.size).append(" bytes)\n")
                append("File: ").append(suggestFileName(event,mime))
            }
            bodyPanel.addView(codeText(highlightPlain(info,query)))
            if(imageBitmap!=null)bodyPanel.addView(ImageView(this).apply{
                setImageBitmap(imageBitmap)
                adjustViewBounds=true
                scaleType=ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(panel2)
                contentDescription="Изображение из ответа сервера"
                setPadding(dp(6),dp(6),dp(6),dp(6))
            },LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(6),0,0)})
            bodyPanel.addView(compactButton("СОХРАНИТЬ БИНАРНИК"){beginBinarySave(event,bytes,mime)},LinearLayout.LayoutParams(-1,dp(40)).apply{setMargins(0,dp(6),0,0)})
        }else bodyPanel.addView(codeText(decorateResponseBody(responseBody.ifBlank{"—"},mime,query)))
        addCollapsible(content,"BODY",true,bodyPanel)

        addCollapsible(content,"TIMING",false,codeText(highlightPlain(buildTimingText(event),query)))
        addCollapsible(content,"COOKIES",false,codeText(highlightPlain(requestCookies.ifBlank{"—"},query)))
        addCollapsible(content,"SOURCES",false,codeText(highlightPlain(buildSourcesText(event),query)))
        val mergedRaw=event.optJSONArray("_mergedEvents")
        val rawText=if(mergedRaw!=null&&mergedRaw.length()>0)mergedRaw.toString(2) else event.toString(2)
        addCollapsible(content,"RAW",false,codeText(highlightPlain(rawText,query)))

        captureDisplayTexts(content,originalTexts)
        decodeButton.setOnClickListener{
            decoded=!decoded
            applyDecodedMode(content,originalTexts,decoded)
            decodeButton.text=if(decoded)"DECODED ✓" else "URL DECODE"
            decodeButton.setTextColor(if(decoded)accent else textColor)
        }

        val scroll=ScrollView(this).apply{
            setBackgroundColor(bg)
            addView(content)
        }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))

        dialog=AlertDialog.Builder(this).setView(root).create()
        dialog.setOnShowListener{
            val dm=resources.displayMetrics
            dialog.window?.setLayout((dm.widthPixels*0.97).toInt(),(dm.heightPixels*0.92).toInt())
            dialog.window?.setBackgroundDrawable(rounded(bg,18f,line))
        }
        dialog.show()
    }

    private fun detailButton(label:String,click:()->Unit)=compactButton(label,click).apply{
        typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
        textSize=9f
        layoutParams=LinearLayout.LayoutParams(-2,dp(38)).apply{marginEnd=dp(5)}
    }

    private fun chip(label:String,color:Int)=TextView(this).apply{
        text=label
        setTextColor(color)
        textSize=10f
        typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
        gravity=Gravity.CENTER
        setPadding(dp(8),dp(4),dp(8),dp(4))
        background=rounded(panel2,8f,line)
    }

    private fun kindColor(kind:String)=when(kind){
        "JSON"->cyan
        "HTML"->violet
        "JS"->amber
        "CSS"->accent
        "IMG"->amber
        "PDF"->bad
        "TEXT"->textColor
        "BIN"->muted
        else->muted
    }

    private fun subtitle(label:String)=TextView(this).apply{
        text=label
        setTextColor(muted)
        textSize=9f
        typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
        letterSpacing=.08f
        setPadding(dp(3),dp(6),dp(3),dp(4))
    }

    private fun headerBlock(headers:List<Pair<String,String>>,query:String)=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL
        background=rounded(panel,10f,line)
        setPadding(dp(6),dp(4),dp(6),dp(4))
        if(headers.isEmpty()){
            addView(TextView(this@NetworkDebuggerActivity).apply{
                text="—"
                setTextColor(muted)
                textSize=10.5f
                typeface=Typeface.MONOSPACE
                setPadding(dp(8),dp(8),dp(8),dp(8))
            })
        }else{
            headers.forEach{(name,value)->
                val raw="$name: $value"
                val styled=SpannableString(raw)
                styled.setSpan(ForegroundColorSpan(cyan),0,name.length,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                applyQueryHighlight(styled,query)
                addView(TextView(this@NetworkDebuggerActivity).apply{
                    text=styled
                    setTextColor(textColor)
                    textSize=10.5f
                    typeface=Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(dp(9),dp(7),dp(9),dp(7))
                    background=rounded(panel2,8f,Color.rgb(40,64,70))
                },LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(2),0,dp(2))})
            }
        }
    }

    private fun addCollapsible(root:LinearLayout,title:String,open:Boolean,body:View){
        var expanded=open
        val button=Button(this).apply{
            text="$title  ${if(expanded)"▴" else "▾"}"
            setTextColor(cyan)
            textSize=10f
            typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
            isAllCaps=false
            gravity=Gravity.START or Gravity.CENTER_VERTICAL
            minHeight=0
            minimumHeight=0
            setPadding(dp(12),0,dp(12),0)
            background=rounded(panel,10f,line)
        }
        body.visibility=if(expanded)View.VISIBLE else View.GONE
        button.setOnClickListener{
            expanded=!expanded
            body.visibility=if(expanded)View.VISIBLE else View.GONE
            button.text="$title  ${if(expanded)"▴" else "▾"}"
        }
        root.addView(button,LinearLayout.LayoutParams(-1,dp(38)).apply{setMargins(0,dp(5),0,dp(4))})
        root.addView(body,LinearLayout.LayoutParams(-1,-2))
    }

    private fun buildRequestSummary(event:JSONObject)=buildString{
        append("Method: ").append(methodOf(event)).append('\n')
        append("URL: ").append(event.optString("url","—")).append('\n')
        appendUrlParts(this,event.optString("url",""))
        append("Source: ").append(displaySource(event).ifBlank{"—"}).append('\n')
        if(event.has("time"))append("Time: ").append(formatTime(event.optLong("time"))).append("  (").append(event.optLong("time")).append(")\n")
        appendField(this,event,"initiatorType","Initiator type")
        appendField(this,event,"initiatorStack","Initiator stack")
    }.trimEnd()

    private fun buildResponseSummary(event:JSONObject)=buildString{
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
        if(event.has("duration"))append("Duration: ").append(formatDuration(event.optDouble("duration",0.0))).append("  (").append(event.opt("duration")).append(" ms)\n")
        listOf("responseSize" to "Response size","transferSize" to "Transferred","encodedBodySize" to "Encoded body","decodedBodySize" to "Decoded body").forEach{(key,label)->
            if(event.has(key)){val bytes=event.optLong(key);append(label).append(": ").append(formatBytes(bytes)).append("  (").append(bytes).append(" bytes)\n")}
        }
        if(event.has("error"))append("\nERROR\n").append(event.optString("error")).append('\n')
    }.trimEnd()

    private fun requestHeaderPairs(event:JSONObject):List<Pair<String,String>>{
        val headers=event.optJSONObject("requestHeaders")?:event.optJSONObject("headers")
        return objectHeaderPairs(headers)
    }

    private fun responseHeaderPairs(event:JSONObject):List<Pair<String,String>>{
        val headers=event.optJSONObject("responseHeaders")
        if(headers!=null)return objectHeaderPairs(headers)
        return rawHeaderPairs(event.optString("responseHeadersRaw",""))
    }

    private fun objectHeaderPairs(headers:JSONObject?):List<Pair<String,String>>{
        if(headers==null)return emptyList()
        val out=mutableListOf<Pair<String,String>>()
        val keys=headers.keys()
        while(keys.hasNext()){
            val key=keys.next()
            out.add(key to headers.opt(key).toString())
        }
        return out.sortedBy{it.first.lowercase(Locale.US)}
    }

    private fun rawHeaderPairs(raw:String):List<Pair<String,String>>{
        if(raw.isBlank())return emptyList()
        return raw.lines().mapNotNull{line->
            val split=line.indexOf(':')
            if(split<=0)null else line.substring(0,split).trim().takeIf{it.isNotBlank()}?.let{name->name to line.substring(split+1).trim()}
        }
    }

    private fun formatHeaders(headers:List<Pair<String,String>>):String=
        if(headers.isEmpty())"—" else headers.joinToString("\n"){(name,value)->"$name: $value"}

    private fun buildRequestSectionText(event:JSONObject)=buildString{
        append(buildRequestSummary(event))
        append("\n\nHEADERS\n")
        append(formatHeaders(requestHeaderPairs(event)))
    }

    private fun buildResponseSectionText(event:JSONObject)=buildString{
        append(buildResponseSummary(event))
        append("\n\nHEADERS\n")
        append(formatHeaders(responseHeaderPairs(event)))
    }

    private fun buildHeadersText(event:JSONObject)=buildString{
        append("REQUEST HEADERS\n").append(formatHeaders(requestHeaderPairs(event)))
        append("\n\nRESPONSE HEADERS\n").append(formatHeaders(responseHeaderPairs(event)))
    }

    private fun buildTimingText(event:JSONObject)=buildString{
        appendField(this,event,"requestStart","Request start")
        appendField(this,event,"workerStart","Worker start")
        appendField(this,event,"responseStart","Response start")
        appendField(this,event,"responseEnd","Response end")
        val timing=event.optJSONObject("timing")
        if(timing!=null){
            if(isNotEmpty())append('\n')
            val keys=timing.keys()
            while(keys.hasNext()){
                val key=keys.next()
                append(key).append(": ").append(timing.opt(key)).append(" ms\n")
            }
        }
        if(isEmpty())append("—")
    }.trimEnd()

    private fun buildSourcesText(event:JSONObject)=buildString{
        val sources=eventSources(event)
        append("Sources: ").append(if(sources.isEmpty())"—" else orderedSourceLabel(sources)).append('\n')
        if(sources.size>1){
            append("Count: ").append(sources.size).append('\n')
            append("Merge confidence: ").append(if(event.optString("_mergeConfidence")=="MEDIUM")"approximate (~)" else "high (✓)").append('\n')
        }
        if(event.optBoolean("_deduplicated",false))append("Duplicate groups collapsed: yes\n")
    }.trimEnd()

    private fun buildRequestText(event:JSONObject,cookies:String)=buildString{
        append(buildRequestSummary(event))
        append("\n\nREQUEST HEADERS\n").append(formatHeaders(requestHeaderPairs(event)))
        append("\n\nREQUEST COOKIES\n").append(cookies.ifBlank{"—"})
        append("\n\nREQUEST BODY\n")
        val body=event.optString("requestBody","")
        append(if(body.isBlank())"—" else prettyBody(body,event.optString("requestMimeType","")))
        append("\n\nTIMING\n").append(buildTimingText(event))
    }

    private fun buildResponseCopy(event:JSONObject,cookies:String)=buildString{
        append(buildResponseSummary(event))
        append("\n\nRESPONSE HEADERS\n").append(formatHeaders(responseHeaderPairs(event)))
        append("\n\nCOOKIES FOR URL\n").append(cookies.ifBlank{"—"})
        append("\n\nRESPONSE BODY\n").append(event.optString("responseBody",event.optString("data","—")))
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
        if(headers!=null){
            val keys=headers.keys()
            while(keys.hasNext()){
                val key=keys.next()
                append(" \\\n  -H ").append(shellQuote("$key: ${headers.optString(key,"")}"))
            }
        }
        val body=event.optString("requestBody","")
        if(body.isNotBlank())append(" \\\n  --data-raw ").append(shellQuote(body))
    }

    private fun captureDisplayTexts(view:View,originals:MutableMap<TextView,CharSequence>){
        when(view){
            is Button->Unit
            is TextView->originals[view]=SpannableString(view.text)
            is ViewGroup->for(i in 0 until view.childCount)captureDisplayTexts(view.getChildAt(i),originals)
        }
    }

    private fun applyDecodedMode(view:View,originals:Map<TextView,CharSequence>,decoded:Boolean){
        when(view){
            is Button->Unit
            is TextView->{
                val original=originals[view]?:view.text
                view.text=if(decoded)decodePercentText(original.toString()) else original
            }
            is ViewGroup->for(i in 0 until view.childCount)applyDecodedMode(view.getChildAt(i),originals,decoded)
        }
    }

    private fun appendUrlParts(out:StringBuilder,raw:String){
        try{
            val u=URL(raw)
            out.append("Scheme: ").append(u.protocol).append('\n')
            out.append("Host: ").append(u.host).append('\n')
            out.append("Port: ").append(if(u.port>0)u.port else u.defaultPort).append('\n')
            out.append("Path: ").append(u.path.ifBlank{"/"}).append('\n')
            if(!u.query.isNullOrBlank()){
                out.append("\nQUERY PARAMETERS\n")
                u.query.split('&').forEach{part->
                    val p=part.indexOf('=')
                    val key=if(p>=0)part.substring(0,p) else part
                    val value=if(p>=0)part.substring(p+1) else ""
                    out.append(key).append(" = ").append(value).append('\n')
                }
            }
        }catch(_:Exception){}
    }

    private fun appendField(out:StringBuilder,event:JSONObject,key:String,label:String){
        if(event.has(key)){
            val value=event.opt(key)
            if(value!=null&&value!=JSONObject.NULL&&value.toString().isNotBlank())out.append(label).append(": ").append(value).append('\n')
        }
    }

    private fun formatTime(ms:Long)=SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(Date(ms))
    private fun listTime(ms:Long)=listTimeFormat.format(Date(ms))

    private fun formatDuration(ms:Double):String = when{
        ms<1000.0->if(ms%1.0==0.0)"${ms.toLong()} ms" else String.format(Locale.US,"%.1f ms",ms)
        ms<60000.0->String.format(Locale.US,"%.2f s",ms/1000.0)
        else->String.format(Locale.US,"%.1f min",ms/60000.0)
    }

    private fun formatBytes(bytes:Long):String=when{
        bytes<0L->"—"
        bytes<1024L->"$bytes B"
        bytes<1024L*1024L->String.format(Locale.US,"%.1f KB",bytes/1024.0)
        bytes<1024L*1024L*1024L->String.format(Locale.US,"%.2f MB",bytes/(1024.0*1024.0))
        else->String.format(Locale.US,"%.2f GB",bytes/(1024.0*1024.0*1024.0))
    }

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
        try{
            if(t.startsWith("{"))return JSONObject(t).toString(2)
            if(t.startsWith("["))return JSONArray(t).toString(2)
        }catch(_:Exception){}
        if(mime.contains("json",true)){
            try{return JSONObject(t).toString(2)}catch(_:Exception){}
            try{return JSONArray(t).toString(2)}catch(_:Exception){}
        }
        if(mime.contains("xml",true)||mime.contains("html",true))return t.replace(Regex(">\\s*<"),">\n<")
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

    private fun highlightPlain(raw:String,query:String):CharSequence{
        val s=SpannableString(raw)
        applyQueryHighlight(s,query)
        return s
    }

    private fun colorRegex(s:SpannableString,r:Regex,color:Int){
        r.findAll(s.toString()).forEach{m->s.setSpan(ForegroundColorSpan(color),m.range.first,m.range.last+1,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)}
    }

    private fun applyQueryHighlight(s:SpannableString,query:String){
        if(query.isBlank())return
        var p=s.toString().indexOf(query,0,true)
        while(p>=0){
            s.setSpan(BackgroundColorSpan(Color.rgb(90,110,30)),p,p+query.length,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            p=s.toString().indexOf(query,p+query.length,true)
        }
    }

    private fun codeText(value:CharSequence)=TextView(this).apply{
        text=value
        setTextColor(textColor)
        textSize=10.5f
        typeface=Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(dp(10),dp(9),dp(10),dp(9))
        background=rounded(panel2,9f,Color.rgb(40,64,70))
    }

    private fun responseBytes(event:JSONObject):ByteArray?{
        val url=event.optString("url","")
        if(url.isBlank())return null
        return try{
            val app=application as? WebResearchApp?:return null
            val bf=WebResearchApp::class.java.getDeclaredField("browserRef")
            bf.isAccessible=true
            val ref=bf.get(app) as? WeakReference<*>?:return null
            val browser=ref.get() as? WebResearchV10Activity?:return null
            val af=WebResearchV10Activity::class.java.getDeclaredField("archive")
            af.isAccessible=true
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
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE)
            type=pendingBinaryMime
            putExtra(Intent.EXTRA_TITLE,pendingBinaryName)
        },702)
    }

    private fun suggestFileName(event:JSONObject,mime:String):String{
        val headers=event.optJSONObject("responseHeaders")
        val disposition=headerValue(headers,"Content-Disposition")
        Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)",RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.getOrNull(1)?.let{return sanitizeName(urlDecode(it.trim()))}
        val url=event.optString("finalUrl",event.optString("url",""))
        val path=try{URL(url).path.substringAfterLast('/')}catch(_:Exception){""}
        var name=path.ifBlank{"response"}
        if(!name.contains('.'))MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.substringBefore(';'))?.takeIf{it.isNotBlank()}?.let{name="$name.$it"}
        return sanitizeName(name)
    }

    private fun sanitizeName(raw:String)=raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").take(120).ifBlank{"response.bin"}

    private fun headerValue(headers:JSONObject?,name:String):String{
        if(headers==null)return ""
        val it=headers.keys()
        while(it.hasNext()){
            val key=it.next()
            if(key.equals(name,true))return headers.optString(key,"")
        }
        return ""
    }

    private fun exportZip(){
        val stamp=SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(Date())
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE)
            type="application/zip"
            putExtra(Intent.EXTRA_TITLE,"web-research-network-$stamp.zip")
        },701)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK)return
        if(requestCode==701){
            data?.data?.let{uri->
                contentResolver.openOutputStream(uri)?.use{o->
                    ZipOutputStream(o).use{z->
                        addZip(z,"network-events.json",JSONObject().put("recording",NetworkDebugStore.recording).put("events",NetworkDebugStore.json()).toString(2))
                        addZip(z,"cookies.json",buildCookiesJson().toString(2))
                    }
                }
            }
            Toast.makeText(this,"ZIP экспортирован",Toast.LENGTH_SHORT).show()
        }else if(requestCode==702){
            val bytes=pendingBinary
            if(bytes!=null){
                data?.data?.let{uri->contentResolver.openOutputStream(uri)?.use{it.write(bytes)}}
                Toast.makeText(this,"Бинарный ответ сохранён",Toast.LENGTH_SHORT).show()
            }
            pendingBinary=null
        }
    }

    private fun buildCookiesJson():JSONArray{
        val out=JSONArray()
        val seen=mutableSetOf<String>()
        allItems.forEach{event->
            val url=eventLocation(event)
            hostOf(url)?.let{host->
                if(seen.add(host))out.put(JSONObject().put("host",host).put("url",url).put("cookie",CookieManager.getInstance().getCookie(url).orEmpty()))
            }
        }
        return out
    }

    private fun addZip(z:ZipOutputStream,name:String,text:String){
        z.putNextEntry(ZipEntry(name))
        z.write(text.toByteArray(Charsets.UTF_8))
        z.closeEntry()
    }

    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()

    private fun rounded(fill:Int,radius:Float,stroke:Int=Color.TRANSPARENT)=GradientDrawable().apply{
        shape=GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius=dp(radius.toInt()).toFloat()
        if(stroke!=Color.TRANSPARENT)setStroke(dp(1),stroke)
    }

    private fun compactButton(label:String,click:()->Unit)=Button(this).apply{
        text=label
        setTextColor(textColor)
        textSize=10f
        isAllCaps=false
        minWidth=0
        minimumWidth=0
        minHeight=0
        minimumHeight=0
        setPadding(dp(10),0,dp(10),0)
        background=rounded(panel2,10f,line)
        setOnClickListener{click()}
    }

    private fun actionLabel(event:JSONObject):String{
        val action=event.optString("action","action").uppercase(Locale.US)
        val target=event.optJSONObject("target")
        val name=target?.optString("text","")?.trim()?.take(80).orEmpty().ifBlank{
            target?.optString("id","")?.takeIf{it.isNotBlank()}?:target?.optString("role","")?.takeIf{it.isNotBlank()}?:target?.optString("tag","").orEmpty()
        }
        return if(name.isBlank())action else "$action  \"$name\""
    }

    inner class EventAdapter:BaseAdapter(){
        override fun getCount()=items.size
        override fun getItem(position:Int)=items[position]
        override fun getItemId(position:Int)=position.toLong()

        override fun getView(position:Int,convertView:View?,parent:ViewGroup?):View{
            val event=getItem(position)
            val row=(convertView as? LinearLayout)?.takeIf{it.findViewWithTag<TextView>("top")!=null}?:LinearLayout(this@NetworkDebuggerActivity).apply{
                orientation=LinearLayout.VERTICAL
                setPadding(dp(10),dp(7),dp(10),dp(7))
                addView(LinearLayout(this@NetworkDebuggerActivity).apply{
                    orientation=LinearLayout.HORIZONTAL
                    gravity=Gravity.CENTER_VERTICAL
                    addView(TextView(this@NetworkDebuggerActivity).apply{
                        tag="top"
                        textSize=10.5f
                        typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
                        maxLines=2
                    },LinearLayout.LayoutParams(0,-2,1f))
                    addView(TextView(this@NetworkDebuggerActivity).apply{
                        tag="kind"
                        textSize=9f
                        typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
                        gravity=Gravity.CENTER
                        setPadding(dp(7),dp(3),dp(7),dp(3))
                        background=rounded(panel2,7f,line)
                    },LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
                })
                addView(TextView(this@NetworkDebuggerActivity).apply{
                    tag="url"
                    textSize=9.5f
                    typeface=Typeface.MONOSPACE
                    maxLines=2
                    setPadding(0,dp(3),0,0)
                })
                addView(TextView(this@NetworkDebuggerActivity).apply{
                    tag="flags"
                    textSize=8.5f
                    typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)
                    setPadding(0,dp(4),0,0)
                })
            }

            val top=row.findViewWithTag<TextView>("top")
            val url=row.findViewWithTag<TextView>("url")
            val kind=row.findViewWithTag<TextView>("kind")
            val flagsView=row.findViewWithTag<TextView>("flags")

            if(isActionEvent(event)){
                row.background=rounded(panel2,8f,line)
                kind.visibility=View.GONE
                flagsView.visibility=View.GONE
                val whenText=if(event.has("time"))listTime(event.optLong("time")) else "--:--:--.---"
                top.text="────  $whenText · ${actionLabel(event)}  ────"
                top.setTextColor(amber)
                url.text=event.optString("page","—")
                url.setTextColor(muted)
                return row
            }

            row.background=rounded(panel,8f,Color.rgb(26,48,39))
            kind.visibility=View.VISIBLE
            val status=event.optInt("status",0)
            val source=sourceSummary(event)
            val method=methodOf(event)
            val whenText=if(event.has("time"))listTime(event.optLong("time")) else "--:--:--.---"
            top.text=buildString{
                append(whenText).append("  ").append(if(isJsEvent(event))"JS" else method)
                if(status>0)append("  ").append(status)
                if(event.has("duration"))append("  ").append(formatDuration(event.optDouble("duration",0.0)))
                if(event.has("responseSize"))append("  ").append(formatBytes(event.optLong("responseSize")))
                append("  · ").append(source)
            }
            top.setTextColor(if(status>=400||event.has("error"))bad else if(isJsEvent(event)||status in 200..399)accent else textColor)

            val kindText=responseKind(event)
            kind.text=kindText
            kind.setTextColor(kindColor(kindText))
            url.text=event.optString("url",event.optString("message","—"))
            url.setTextColor(muted)

            val flags=rowFlags(event)
            flagsView.text=flags
            flagsView.visibility=if(flags.isBlank())View.GONE else View.VISIBLE
            flagsView.setTextColor(if(event.optBoolean("_changed",false)||hasAuth(event))amber else muted)
            return row
        }
    }
}
