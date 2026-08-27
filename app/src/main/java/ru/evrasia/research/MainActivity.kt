package ru.evrasia.research

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var badge: TextView
    private val records = JSONArray()

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
            text = "0 запросов"
            setTextColor(Color.WHITE)
            setPadding(12, 0, 12, 0)
        }
        val export = Button(this).apply {
            text = "Экспорт JSON"
            setOnClickListener { exportJson() }
        }
        val clear = Button(this).apply {
            text = "Очистить"
            setOnClickListener { clearRecords(); updateBadge() }
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
        web.settings.userAgentString = web.settings.userAgentString + " EvrasiaResearch/1"
        web.addJavascriptInterface(Bridge(this), "EvrasiaResearch")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectHooks()
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                request?.let {
                    addRecord(
                        JSONObject()
                            .put("source", "webview")
                            .put("time", System.currentTimeMillis())
                            .put("method", it.method)
                            .put("url", it.url.toString())
                            .put("headers", JSONObject(it.requestHeaders))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
        web.loadUrl("https://evrasia.rest/")
    }

    private fun injectHooks() {
        val js = """(function(){if(window.__ER)return;window.__ER=true;const send=o=>{try{EvrasiaResearch.record(JSON.stringify(o))}catch(e){}};const f=window.fetch;window.fetch=async function(i,n){let u=typeof i==='string'?i:(i&&i.url)||'';let m=(n&&n.method)||(i&&i.method)||'GET';let b=n&&n.body;let t=Date.now();try{let r=await f.apply(this,arguments);let c=r.clone();let x='';try{x=await c.text()}catch(e){}send({source:'fetch',time:t,method:m,url:u,requestBody:b==null?'':String(b),status:r.status,responseBody:x});return r}catch(e){send({source:'fetch',time:t,method:m,url:u,requestBody:b==null?'':String(b),error:String(e)});throw e}};const O=XMLHttpRequest.prototype.open,S=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(m,u){this.__erm=m;this.__eru=u;return O.apply(this,arguments)};XMLHttpRequest.prototype.send=function(b){let x=this,t=Date.now();x.addEventListener('loadend',function(){send({source:'xhr',time:t,method:x.__erm||'GET',url:String(x.__eru||''),requestBody:b==null?'':String(b),status:x.status,responseBody:x.responseType===''||x.responseType==='text'?x.responseText:'[non-text response]'})});return S.apply(this,arguments)};send({source:'hook',time:Date.now(),method:'',url:location.href,status:0,responseBody:'hook installed'});})();"""
        web.evaluateJavascript(js, null)
    }

    @Synchronized private fun addRecord(o: JSONObject) {
        records.put(o)
        runOnUiThread { updateBadge() }
    }

    @Synchronized private fun clearRecords() {
        while (records.length() > 0) records.remove(records.length() - 1)
    }

    private fun updateBadge() {
        badge.text = "${records.length()} запросов"
    }

    private fun exportJson() {
        val root = JSONObject()
            .put("format", "evrasia-research-v1")
            .put("exportedAt", System.currentTimeMillis())
            .put("page", web.url ?: "")
            .put("records", records)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "evrasia-research-$stamp.json")
        }
        pendingJson = root.toString(2)
        startActivityForResult(intent, 501)
    }

    private var pendingJson = ""

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 501 && resultCode == RESULT_OK) {
            data?.data?.let {
                contentResolver.openOutputStream(it)?.use { s ->
                    s.write(pendingJson.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    inner class Bridge(private val context: Context) {
        @JavascriptInterface
        fun record(json: String) {
            try {
                addRecord(JSONObject(json))
            } catch (_: Exception) {
            }
        }
    }
}
