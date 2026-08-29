package ru.evrasia.research

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URL
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class CookieTraceProvider : ContentProvider(), Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var browserRef = WeakReference<WebResearchV10Activity>(null)
    private var debuggerRef = WeakReference<NetworkDebuggerActivity>(null)
    private var processedRecords = 0
    private var lastHookUrl = ""
    private var lastProgress = -1

    private val snapshots = linkedMapOf<String, MutableMap<String, String>>()
    private val events = mutableListOf<JSONObject>()
    private val recentRequests = ArrayDeque<JSONObject>()
    private val lastExact = mutableMapOf<String, Long>()
    private val fingerprints = linkedSetOf<String>()

    private val ink = Color.rgb(3, 10, 15)
    private val surface2 = Color.rgb(10, 25, 34)
    private val line = Color.rgb(21, 57, 69)
    private val cyan = Color.rgb(0, 226, 239)
    private val white = Color.rgb(232, 244, 248)

    private data class ParsedCookie(
        val name: String,
        val value: String,
        val action: String,
        val domain: String,
        val path: String,
        val raw: String
    )

    private val ticker = object : Runnable {
        override fun run() {
            browserRef.get()?.let { sampleBrowser(it) }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(this)
        handler.post(ticker)
        return true
    }

    private fun sampleBrowser(activity: WebResearchV10Activity) {
        val web = webOf(activity) ?: return
        val page = web.url.orEmpty()
        val progress = web.progress
        if (page.isNotBlank() && (page != lastHookUrl || progress < 100 || lastProgress < 100)) {
            injectCookieHooks(web)
            lastHookUrl = page
        }
        lastProgress = progress

        val archive = archiveOf(activity) ?: return
        synchronized(archive) {
            val count = archive.records.length()
            if (count < processedRecords) resetTrace()
            for (i in processedRecords until count) {
                archive.records.optJSONObject(i)?.let { ingestNetworkRecord(JSONObject(it.toString())) }
            }
            processedRecords = count
            ingestJsArtifacts(archive)
        }

        if (page.startsWith("http://") || page.startsWith("https://")) {
            val raw = CookieManager.getInstance().getCookie(page).orEmpty()
            observeCookieSnapshot(page, raw, System.currentTimeMillis())
        }

        archive.extraArtifacts["cookie-trace.json"] = exportJson(page).toString(2).toByteArray(Charsets.UTF_8)
    }

    private fun webOf(activity: WebResearchV10Activity): WebView? = try {
        val field = WebResearchV10Activity::class.java.getDeclaredField("web")
        field.isAccessible = true
        field.get(activity) as? WebView
    } catch (_: Exception) { null }

    private fun archiveOf(activity: WebResearchV10Activity): ResearchArchive? = try {
        val field = WebResearchV10Activity::class.java.getDeclaredField("archive")
        field.isAccessible = true
        field.get(activity) as? ResearchArchive
    } catch (_: Exception) { null }

    private fun injectCookieHooks(web: WebView) {
        val js = """
            (function(){
              if(window.__WR_COOKIE_TRACE)return;window.__WR_COOKIE_TRACE=true;
              const emit=o=>{try{const k='cookie-trace-event/'+Date.now()+'-'+Math.random().toString(36).slice(2);EvrasiaResearch.artifactChunk(k,0,1,JSON.stringify(o))}catch(e){}};
              const first=raw=>{raw=String(raw||'');const p=raw.indexOf(';'),head=(p>=0?raw.slice(0,p):raw),eq=head.indexOf('=');return {raw:raw,name:(eq>=0?head.slice(0,eq):head).trim(),value:eq>=0?head.slice(eq+1):''}};
              const hasName=(raw,name)=>String(raw||'').split(';').some(x=>x.trim().startsWith(name+'='));
              const deletion=raw=>/max-age\s*=\s*0/i.test(raw)||/expires\s*=\s*(?:thu,\s*)?0?1[-\s]jan[-\s]1970/i.test(raw);
              const stack=()=>{try{return (new Error()).stack||''}catch(e){return''}};
              try{
                const d=Object.getOwnPropertyDescriptor(Document.prototype,'cookie')||Object.getOwnPropertyDescriptor(HTMLDocument.prototype,'cookie');
                if(d&&d.get&&d.set){
                  Object.defineProperty(document,'cookie',{configurable:true,enumerable:d.enumerable,get:function(){return d.get.call(document)},set:function(v){
                    const raw=String(v),p=first(raw);let before='';try{before=d.get.call(document)||''}catch(e){}
                    const existed=p.name?hasName(before,p.name):false;const action=deletion(raw)?'DELETE':(existed?'UPDATE':'CREATE');const s=stack();
                    const r=d.set.call(document,v);
                    if(p.name)emit({source:'cookie-js-trace',time:Date.now(),action:action,mechanism:'document.cookie',name:p.name,value:p.value,raw:raw,page:location.href,stack:s,confidence:'EXACT'});
                    return r;
                  }});
                }
              }catch(e){}
              try{
                const cs=window.cookieStore;
                if(cs&&!cs.__wrCookieTrace){
                  try{Object.defineProperty(cs,'__wrCookieTrace',{value:true})}catch(e){cs.__wrCookieTrace=true}
                  ['set','delete'].forEach(fn=>{try{const orig=cs[fn]&&cs[fn].bind(cs);if(!orig)return;cs[fn]=function(){
                    const a=arguments,o=(a[0]&&typeof a[0]==='object')?a[0]:null,name=String(o?.name??a[0]??''),value=fn==='set'?String(o?.value??a[1]??''):'';
                    let before='';try{before=document.cookie||''}catch(e){};const existed=name?hasName(before,name):false;const action=fn==='delete'?'DELETE':(existed?'UPDATE':'CREATE');
                    if(name)emit({source:'cookie-js-trace',time:Date.now(),action:action,mechanism:'CookieStore.'+fn,name:name,value:value,raw:o?JSON.stringify(o):name+(fn==='set'?'='+value:''),page:location.href,stack:stack(),confidence:'EXACT'});
                    return orig(...a);
                  }}catch(e){}});
                }
              }catch(e){}
            })();
        """.trimIndent()
        try { web.evaluateJavascript(js, null) } catch (_: Exception) {}
    }

    private fun ingestJsArtifacts(archive: ResearchArchive) {
        val keys = archive.extraArtifacts.keys.filter { it.startsWith("cookie-trace-event/") }
        keys.forEach { key ->
            val bytes = archive.extraArtifacts.remove(key) ?: return@forEach
            try {
                val event = JSONObject(bytes.toString(Charsets.UTF_8))
                val name = event.optString("name", "").trim()
                if (name.isBlank()) return@forEach
                val page = event.optString("page", "")
                val time = event.optLong("time", System.currentTimeMillis())
                val action = event.optString("action", "SET")
                addTrace(JSONObject()
                    .put("time", time)
                    .put("action", action)
                    .put("name", name)
                    .put("value", event.optString("value", ""))
                    .put("page", page)
                    .put("mechanism", event.optString("mechanism", "JavaScript"))
                    .put("confidence", "EXACT")
                    .put("origin", "JAVASCRIPT")
                    .put("raw", event.optString("raw", ""))
                    .put("stack", event.optString("stack", "")))
                lastExact[cookieKey(scopeHost(page, ""), name)] = time
            } catch (_: Exception) {}
        }
    }

    private fun ingestNetworkRecord(record: JSONObject) {
        val url = record.optString("finalUrl", record.optString("url", ""))
        val source = record.optString("source", "")
        if ((url.startsWith("http://") || url.startsWith("https://")) && source in setOf("fetch", "xhr", "resource-copy", "resource-timing", "navigation", "navigation-timing", "replay")) {
            val start = record.optLong("time", System.currentTimeMillis())
            val duration = record.optDouble("duration", 0.0).coerceAtLeast(0.0).toLong()
            recentRequests.addLast(JSONObject()
                .put("time", start + duration)
                .put("method", record.optString("method", "GET").ifBlank { "GET" })
                .put("url", url)
                .put("status", record.optInt("status", 0))
                .put("source", source))
            trimRequests(System.currentTimeMillis())
        }

        val setCookies = mutableListOf<String>()
        record.optJSONObject("responseHeaders")?.let { headers ->
            val iterator = headers.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                if (key.equals("Set-Cookie", true)) splitSetCookieHeader(headers.opt(key)?.toString().orEmpty()).forEach { setCookies.add(it) }
            }
        }
        record.optString("responseHeadersRaw", "").lines().forEach { line ->
            val p = line.indexOf(':')
            if (p > 0 && line.substring(0, p).trim().equals("Set-Cookie", true)) splitSetCookieHeader(line.substring(p + 1).trim()).forEach { setCookies.add(it) }
        }
        if (setCookies.isEmpty()) return

        val time = record.optLong("time", System.currentTimeMillis()) + record.optDouble("duration", 0.0).coerceAtLeast(0.0).toLong()
        setCookies.forEach { raw ->
            val parsed = parseSetCookie(raw) ?: return@forEach
            val host = scopeHost(url, parsed.domain)
            addTrace(JSONObject()
                .put("time", time)
                .put("action", parsed.action)
                .put("name", parsed.name)
                .put("value", parsed.value)
                .put("page", url)
                .put("domain", parsed.domain)
                .put("path", parsed.path)
                .put("mechanism", "HTTP Set-Cookie")
                .put("confidence", "EXACT")
                .put("origin", "HTTP_RESPONSE")
                .put("method", record.optString("method", "GET"))
                .put("status", record.optInt("status", 0))
                .put("url", url)
                .put("raw", raw))
            lastExact[cookieKey(host, parsed.name)] = time
        }
    }

    private fun splitSetCookieHeader(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(Regex(",\\s*(?=[!#$%&'*+.^_`|~0-9A-Za-z-]+=)")).map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun parseSetCookie(raw: String): ParsedCookie? {
        val parts = raw.split(';')
        val head = parts.firstOrNull()?.trim().orEmpty()
        val eq = head.indexOf('=')
        if (eq <= 0) return null
        val name = head.substring(0, eq).trim()
        val value = head.substring(eq + 1)
        var domain = ""
        var path = ""
        parts.drop(1).forEach { part ->
            val p = part.trim()
            when {
                p.startsWith("domain=", true) -> domain = p.substringAfter('=').trim().trimStart('.')
                p.startsWith("path=", true) -> path = p.substringAfter('=').trim()
            }
        }
        val lower = raw.lowercase(Locale.US)
        val delete = Regex("max-age\\s*=\\s*0", RegexOption.IGNORE_CASE).containsMatchIn(raw) || lower.contains("01 jan 1970") || lower.contains("01-jan-1970")
        return ParsedCookie(name, value, if (delete) "DELETE" else "SET", domain, path, raw)
    }

    private fun observeCookieSnapshot(page: String, raw: String, now: Long) {
        val host = scopeHost(page, "")
        if (host.isBlank()) return
        val current = parseCookieHeader(raw)
        val previous = snapshots[host]
        if (previous == null) {
            snapshots[host] = current.toMutableMap()
            current.forEach { (name, value) ->
                val exactTime = lastExact[cookieKey(host, name)] ?: 0L
                if (now - exactTime > 1800L) addTrace(JSONObject()
                    .put("time", now)
                    .put("action", "OBSERVED")
                    .put("name", name)
                    .put("value", value)
                    .put("page", page)
                    .put("mechanism", "CookieManager initial snapshot")
                    .put("confidence", "UNKNOWN")
                    .put("origin", "PREEXISTING_OR_UNKNOWN"))
            }
            return
        }

        current.forEach { (name, value) ->
            val old = previous[name]
            if (old == null) inferChange(page, host, name, value, "CREATE", now)
            else if (old != value) inferChange(page, host, name, value, "UPDATE", now)
        }
        previous.keys.filter { it !in current }.forEach { name -> inferChange(page, host, name, "", "DELETE", now) }
        snapshots[host] = current.toMutableMap()
    }

    private fun inferChange(page: String, host: String, name: String, value: String, action: String, now: Long) {
        val exactTime = lastExact[cookieKey(host, name)] ?: 0L
        if (now - exactTime in 0..1800L) return
        val request = likelyRequest(now)
        val delta = request?.let { abs(now - it.optLong("time", now)) } ?: Long.MAX_VALUE
        val confidence = when {
            request == null -> "UNKNOWN"
            delta <= 350L -> "MEDIUM"
            delta <= 2000L -> "LOW"
            else -> "UNKNOWN"
        }
        val trace = JSONObject()
            .put("time", now)
            .put("action", action)
            .put("name", name)
            .put("value", value)
            .put("page", page)
            .put("mechanism", "CookieManager diff")
            .put("confidence", confidence)
            .put("origin", if (request == null) "UNKNOWN" else "LIKELY_HTTP_RESPONSE")
        if (request != null) {
            trace.put("method", request.optString("method", "GET"))
                .put("status", request.optInt("status", 0))
                .put("url", request.optString("url", ""))
                .put("requestSource", request.optString("source", ""))
                .put("deltaMs", delta)
        }
        addTrace(trace)
    }

    private fun likelyRequest(now: Long): JSONObject? {
        trimRequests(now)
        var best: JSONObject? = null
        var bestScore = Long.MAX_VALUE
        val iterator = recentRequests.descendingIterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            val delta = abs(now - candidate.optLong("time", now))
            if (delta > 2000L) continue
            val sourcePenalty = when (candidate.optString("source", "")) {
                "fetch", "xhr", "resource-copy", "replay" -> 0L
                "resource-timing", "navigation-timing" -> 80L
                else -> 160L
            }
            val score = delta + sourcePenalty
            if (score < bestScore) { best = candidate; bestScore = score }
        }
        return best
    }

    private fun trimRequests(now: Long) {
        while (recentRequests.isNotEmpty() && now - recentRequests.first.optLong("time", now) > 10000L) recentRequests.removeFirst()
        while (recentRequests.size > 200) recentRequests.removeFirst()
    }

    private fun parseCookieHeader(raw: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        raw.split(';').map { it.trim() }.filter { it.isNotBlank() }.forEach { part ->
            val eq = part.indexOf('=')
            if (eq > 0) out[part.substring(0, eq).trim()] = part.substring(eq + 1)
        }
        return out
    }

    private fun addTrace(event: JSONObject) {
        val time = event.optLong("time", System.currentTimeMillis())
        val fingerprint = buildString {
            append(event.optString("origin", "")).append('|')
            append(event.optString("action", "")).append('|')
            append(event.optString("name", "")).append('|')
            append(event.optString("value", "")).append('|')
            append(event.optString("url", event.optString("page", ""))).append('|')
            append(time / 100L)
        }
        if (!fingerprints.add(fingerprint)) return
        events.add(event)
        if (events.size > 2000) events.removeAt(0)
        if (fingerprints.size > 5000) fingerprints.clear()
    }

    private fun scopeHost(page: String, explicitDomain: String): String {
        if (explicitDomain.isNotBlank()) return explicitDomain.lowercase(Locale.US).trimStart('.')
        return try { URL(page).host.lowercase(Locale.US) } catch (_: Exception) { "" }
    }

    private fun cookieKey(host: String, name: String) = "${host.lowercase(Locale.US)}|$name"

    private fun relevantToHost(event: JSONObject, host: String): Boolean {
        val eventDomain = event.optString("domain", "").trimStart('.').lowercase(Locale.US)
        val eventHost = if (eventDomain.isNotBlank()) eventDomain else scopeHost(event.optString("page", event.optString("url", "")), "")
        if (eventHost.isBlank() || host.isBlank()) return true
        return host == eventHost || host.endsWith(".$eventHost") || eventHost.endsWith(".$host")
    }

    private fun report(page: String, raw: String): String {
        val current = parseCookieHeader(raw)
        val host = scopeHost(page, "")
        val relevant = events.filter { relevantToHost(it, host) }
        return buildString {
            append("COOKIE TRACE\n")
            append("Страница: ").append(page.ifBlank { "—" }).append('\n')
            append("Активных куки: ").append(current.size).append("\n\n")

            if (current.isEmpty()) append("Активных куки нет.\n")
            current.forEach { (name, value) ->
                append("════════════════════════════════\n")
                append(name).append('=').append(value).append('\n')
                val history = relevant.filter { it.optString("name") == name }.sortedByDescending { it.optLong("time", 0L) }
                val latest = history.firstOrNull { it.optString("action") != "DELETE" }
                val origin = if (latest != null && latest.optString("confidence") != "EXACT") {
                    history.firstOrNull { candidate ->
                        candidate.optString("confidence") == "EXACT" && candidate.optString("value") == value && abs(candidate.optLong("time") - latest.optLong("time")) <= 2500L
                    } ?: latest
                } else latest
                append("\nПРОИСХОЖДЕНИЕ ТЕКУЩЕГО ЗНАЧЕНИЯ\n")
                if (origin == null) append("Источник пока не определён.\n") else append(formatTrace(origin))
                if (history.isNotEmpty()) {
                    append("\nИСТОРИЯ\n")
                    history.take(12).forEach { append(formatTrace(it)).append('\n') }
                }
            }

            val inactive = relevant.map { it.optString("name", "") }.filter { it.isNotBlank() && it !in current }.distinct()
            if (inactive.isNotEmpty()) {
                append("\n════════════════════════════════\n")
                append("НЕАКТИВНЫЕ / УДАЛЁННЫЕ КУКИ\n")
                inactive.forEach { name ->
                    relevant.filter { it.optString("name") == name }.maxByOrNull { it.optLong("time", 0L) }?.let { append('\n').append(name).append('\n').append(formatTrace(it)) }
                }
            }
        }.trimEnd()
    }

    private fun formatTrace(event: JSONObject): String = buildString {
        val time = event.optLong("time", 0L)
        if (time > 0L) append(SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(time))).append("  ")
        append(event.optString("action", "EVENT")).append('\n')
        append("Источник: ").append(event.optString("mechanism", "—")).append('\n')
        append("Точность: ").append(when (event.optString("confidence", "UNKNOWN")) {
            "EXACT" -> "ТОЧНО"
            "MEDIUM" -> "ВЕРОЯТНО"
            "LOW" -> "ПРЕДПОЛОЖЕНИЕ"
            else -> "НЕИЗВЕСТНО"
        }).append('\n')
        val url = event.optString("url", "")
        if (url.isNotBlank()) {
            append("Запрос: ").append(event.optString("method", "GET"))
            val status = event.optInt("status", 0)
            if (status > 0) append("  ").append(status)
            append('\n').append(url).append('\n')
        } else event.optString("page", "").takeIf { it.isNotBlank() }?.let { append("Страница: ").append(it).append('\n') }
        if (event.has("deltaMs")) append("Изменение куки через ").append(event.optLong("deltaMs")).append(" мс после запроса\n")
        event.optString("raw", "").takeIf { it.isNotBlank() }?.let { append("Raw: ").append(it).append('\n') }
        event.optString("stack", "").takeIf { it.isNotBlank() }?.let { append("JS stack:\n").append(it.take(2500)).append('\n') }
    }.trimEnd()

    private fun exportJson(page: String): JSONObject {
        val arr = JSONArray()
        events.forEach { arr.put(JSONObject(it.toString())) }
        return JSONObject()
            .put("format", "evrasia-cookie-trace-v1")
            .put("generatedAt", System.currentTimeMillis())
            .put("page", page)
            .put("events", arr)
    }

    private fun resetTrace() {
        processedRecords = 0
        snapshots.clear()
        events.clear()
        recentRequests.clear()
        lastExact.clear()
        fingerprints.clear()
        lastHookUrl = ""
        lastProgress = -1
    }

    private fun rewireCookieButton(activity: NetworkDebuggerActivity) {
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? LinearLayout) ?: return
        val toolbarScroll = root.getChildAt(0) as? HorizontalScrollView ?: return
        val toolbar = toolbarScroll.getChildAt(0) as? LinearLayout ?: return
        val button = toolbar.findViewWithTag<Button>("debugger-cookies") ?: return
        button.text = "Куки"
        button.setOnClickListener { showCookieTrace(activity) }
    }

    private fun showCookieTrace(activity: NetworkDebuggerActivity) {
        val browser = browserRef.get()
        val web = browser?.let { webOf(it) }
        val page = web?.url.orEmpty()
        val raw = if (page.isBlank()) "" else CookieManager.getInstance().getCookie(page).orEmpty()
        val body = TextView(activity).apply {
            text = report(page, raw)
            setTextColor(white)
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 14))
            background = round(activity, surface2, 9, line)
        }
        val scroll = ScrollView(activity).apply {
            setBackgroundColor(ink)
            setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 6))
            addView(body, ViewGroup.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(activity).setTitle("COOKIE TRACE").setView(scroll).setNegativeButton("Закрыть", null).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(round(activity, ink, 16, line))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cyan)
        }
        dialog.show()
    }

    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun round(activity: Activity, fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(activity, radius).toFloat()
        stroke?.let { setStroke(dp(activity, 1), it) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is WebResearchV10Activity) {
            browserRef = WeakReference(activity)
            resetTrace()
        } else if (activity is NetworkDebuggerActivity) debuggerRef = WeakReference(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        when (activity) {
            is WebResearchV10Activity -> {
                browserRef = WeakReference(activity)
                webOf(activity)?.let { injectCookieHooks(it) }
                sampleBrowser(activity)
            }
            is NetworkDebuggerActivity -> {
                debuggerRef = WeakReference(activity)
                handler.postDelayed({ rewireCookieButton(activity) }, 150)
                handler.postDelayed({ rewireCookieButton(activity) }, 700)
            }
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is WebResearchV10Activity && browserRef.get() === activity) browserRef.clear()
        if (activity is NetworkDebuggerActivity && debuggerRef.get() === activity) debuggerRef.clear()
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
