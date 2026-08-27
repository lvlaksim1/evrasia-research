package ru.evrasia.research

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
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
import java.net.URL
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
    private var lastRevision = -1L
    private var jsOnly = false

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

        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(6)) }
        header.addView(TextView(this).apply { text="NETWORK"; setTextColor(textColor); textSize=18f; typeface=Typeface.DEFAULT_BOLD; letterSpacing=.08f })
        header.addView(TextView(this).apply { text="requests · responses · js · cookies"; setTextColor(accent); textSize=11f })
        root.addView(header)

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

    private fun isRequestEvent(e:JSONObject)=e.optString("source") in setOf("fetch","xhr","webview","resource-copy","navigation","new-window","websocket-open","websocket-send","websocket-receive","sse-open","sse-message","beacon","js-file","script-archive")
    private fun isJsEvent(e:JSONObject):Boolean { val u=e.optString("url","").substringBefore('?').lowercase(Locale.US); return e.optString("source") in setOf("js-file","script-archive","source-map") || u.endsWith(".js") || u.endsWith(".mjs") || e.optString("mimeType","").contains("javascript",true) }
    private fun hostOf(url:String):String?=try { if(url.startsWith("http://")||url.startsWith("https://")) URL(url).host else null } catch(_:Exception){null}

    private fun showDetails(event:JSONObject, query:String){
        val url=event.optString("url","")
        val cookies=if(url.startsWith("http")) CookieManager.getInstance().getCookie(url).orEmpty() else ""
        val body=buildString {
            append("ОБЩЕЕ\nИсточник: ${event.optString("source","—")}\nМетод: ${event.optString("method","—")}\nURL: ${url.ifBlank{"—"}}\n")
            if(event.has("status")) append("Статус: ${event.optInt("status")} ${event.optString("statusText","")}\n")
            if(event.has("duration")) append("Длительность: ${event.optLong("duration")} ms\n")
            if(event.has("responseSize")) append("Размер: ${event.optLong("responseSize")} bytes\n")
            append("\nREQUEST HEADERS\n${event.optJSONObject("requestHeaders")?:event.optJSONObject("headers")?:JSONObject()}\n")
            append("\nREQUEST BODY\n${event.optString("requestBody","—")}\n")
            append("\nRESPONSE HEADERS\n${event.optJSONObject("responseHeaders")?:event.optString("responseHeadersRaw","—")}\n")
            append("\nRESPONSE BODY\n${event.optString("responseBody",event.optString("data","—"))}\n")
            append("\nCOOKIES\n${cookies.ifBlank{"—"}}\n")
            append("\nRAW EVENT\n${event.toString(2)}")
        }
        val span=SpannableString(body)
        var first=-1
        if(query.isNotBlank()){
            var p=body.indexOf(query,0,true)
            while(p>=0){ if(first<0) first=p; span.setSpan(BackgroundColorSpan(Color.rgb(90,110,30)),p,p+query.length,0); p=body.indexOf(query,p+query.length,true) }
        }
        val tv=TextView(this).apply { text=span; setTextColor(textColor); setBackgroundColor(bg); textSize=12f; setTextIsSelectable(true); setPadding(dp(12),dp(10),dp(12),dp(14)) }
        val sv=ScrollView(this).apply { addView(tv) }
        val dialog=AlertDialog.Builder(this).setTitle(if(query.isNotBlank()) "Совпадение: $query" else "Детали запроса").setView(sv).setPositiveButton("Закрыть",null).create()
        dialog.setOnShowListener { if(first>=0) sv.post { val fraction=first.toFloat()/body.length.coerceAtLeast(1); sv.scrollTo(0,(tv.height*fraction).toInt().coerceAtLeast(0)) } }
        dialog.show()
    }

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
        super.onActivityResult(requestCode,resultCode,data); if(requestCode!=701||resultCode!=RESULT_OK)return
        data?.data?.let{uri->contentResolver.openOutputStream(uri)?.use{o->ZipOutputStream(o).use{z->addZip(z,"network-events.json",JSONObject().put("recording",NetworkDebugStore.recording).put("events",NetworkDebugStore.json()).toString(2));addZip(z,"cookies.json",buildCookiesJson().toString(2))}}};Toast.makeText(this,"ZIP экспортирован",Toast.LENGTH_SHORT).show()
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
            val status=e.optInt("status",0);val source=e.optString("source","");val method=e.optString("method",if(isJsEvent(e))"JS" else source.uppercase(Locale.US))
            top.text=buildString{append(if(isJsEvent(e))"JS" else method);if(status>0)append("  ").append(status);if(e.has("duration"))append("  ").append(e.optLong("duration")).append(" ms");if(e.has("responseSize"))append("  ").append(e.optLong("responseSize")).append(" B");append("  · ").append(source)}
            top.setTextColor(if(status>=400||e.has("error"))bad else if(isJsEvent(e)||status in 200..399)accent else textColor)
            url.text=e.optString("url",e.optString("message","—"));url.setTextColor(muted);return row
        }
    }
}
