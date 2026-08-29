package ru.evrasia.research

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URL
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class CookieTraceV47Provider : ContentProvider(), Application.ActivityLifecycleCallbacks {
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
    private val surface = Color.rgb(7, 18, 25)
    private val surface2 = Color.rgb(10, 25, 34)
    private val line = Color.rgb(21, 57, 69)
    private val cyan = Color.rgb(0, 226, 239)
    private val white = Color.rgb(232, 244, 248)
    private val muted = Color.rgb(113, 139, 151)
    private val accent = Color.rgb(151, 231, 92)
    private val amber = Color.rgb(255, 205, 112)

    private data class ParsedCookie(
        val name: String,
        val value: String,
        val action: String,
        val domain: String,
        val path: String
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
            observeCookieSnapshot(page, CookieManager.getInstance().getCookie(page).orEmpty(), System.currentTimeMillis())
        }

        archive.extraArtifacts["cookie-trace.json"] = exportJson(page).toString(2).toByteArray(Charsets.UTF_8)
    }

    private fun webOf(activity: WebResearchV10Activity): WebView? = try {
        val f = WebResearchV10Activity::class.java.getDeclaredField("web")
        f.isAccessible = true
        f.get(activity) as? WebView
    } catch (_: Exception) { null }

    private fun archiveOf(activity: WebResearchV10Activity): ResearchArchive? = try {
        val f = WebResearchV10Activity::class.java.getDeclaredField("archive")
        f.isAccessible = true
        f.get(activity) as? ResearchArchive
    } catch (_: Exception) { null }

    private fun injectCookieHooks(web: WebView) {
        val js = """
            (function(){
              if(window.__WR_COOKIE_TRACE_V47)return;window.__WR_COOKIE_TRACE_V47=true;
              const emit=o=>{try{const k='cookie-trace-event/'+Date.now()+'-'+Math.random().toString(36).slice(2);EvrasiaResearch.artifactChunk(k,0,1,JSON.stringify(o))}catch(e){}};
              const first=raw=>{raw=String(raw||'');const p=raw.indexOf(';'),head=(p>=0?raw.slice(0,p):raw),eq=head.indexOf('=');return {raw:raw,name:(eq>=0?head.slice(0,eq):head).trim(),value:eq>=0?head.slice(eq+1):''}};
              const hasName=(raw,name)=>String(raw||'').split(';').some(x=>x.trim().startsWith(name+'='));
              const deleted=raw=>/max-age\s*=\s*0/i.test(raw)||/expires\s*=\s*(?:thu,\s*)?0?1[-\s]jan[-\s]1970/i.test(raw);
              const stack=()=>{try{return (new Error()).stack||''}catch(e){return''}};
              try{
                const d=Object.getOwnPropertyDescriptor(Document.prototype,'cookie')||Object.getOwnPropertyDescriptor(HTMLDocument.prototype,'cookie');
                if(d&&d.get&&d.set){
                  Object.defineProperty(document,'cookie',{configurable:true,enumerable:d.enumerable,get:function(){return d.get.call(document)},set:function(v){
                    const raw=String(v),p=first(raw);let before='';try{before=d.get.call(document)||''}catch(e){}
                    const existed=p.name?hasName(before,p.name):false;const action=deleted(raw)?'DELETE':(existed?'UPDATE':'CREATE');const s=stack();
                    const r=d.set.call(document,v);
                    if(p.name)emit({source:'cookie-js-trace',time:Date.now(),action:action,mechanism:'document.cookie',name:p.name,value:p.value,raw:raw,page:location.href,stack:s,confidence:'EXACT'});
                    return r;
                  }});
                }
              }catch(e){}
              try{
                const cs=window.cookieStore;
                if(cs&&!cs.__wrCookieTraceV47){
                  try{Object.defineProperty(cs,'__wrCookieTraceV47',{value:true})}catch(e){cs.__wrCookieTraceV47=true}
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
        archive.extraArtifacts.keys.filter { it.startsWith("cookie-trace-event/") }.forEach { key ->
            val bytes = archive.extraArtifacts.remove(key) ?: return@forEach
            try {
                val src = JSONObject(bytes.toString(Charsets.UTF_8))
                val name = src.optString("name", "").trim()
                if (name.isBlank()) return@forEach
                val page = src.optString("page", "")
                val time = src.optLong("time", System.currentTimeMillis())
                addTrace(JSONObject()
                    .put("time", time)
                    .put("action", src.optString("action", "SET"))
                    .put("name", name)
                    .put("value", src.optString("value", ""))
                    .put("page", page)
                    .put("mechanism", src.optString("mechanism", "JavaScript"))
                    .put("confidence", "EXACT")
                    .put("origin", "JAVASCRIPT")
                    .put("raw", src.optString("raw", ""))
                    .put("stack", src.optString("stack", "")))
                lastExact[cookieKey(scopeHost(page, ""), name)] = time
            } catch (_: Exception) {}
        }
    }

    private fun ingestNetworkRecord(record: JSONObject) {
        val url = record.optString("finalUrl", record.optString("url", ""))
        val source = record.optString("source", "")
        val endTime = record.optLong("time", System.currentTimeMillis()) + record.optDouble("duration", 0.0).coerceAtLeast(0.0).toLong()

        if ((url.startsWith("http://") || url.startsWith("https://")) && source in setOf("fetch", "xhr", "resource-copy", "resource-timing", "navigation", "navigation-timing", "replay")) {
            val req = JSONObject()
                .put("time", endTime)
                .put("method", record.optString("method", "GET").ifBlank { "GET" })
                .put("url", url)
                .put("status", record.optInt("status", 0))
                .put("source", source)
                .put("requestBody", record.optString("requestBody", ""))
                .put("requestMimeType", record.optString("requestMimeType", ""))
            val headers = record.optJSONObject("requestHeaders") ?: record.optJSONObject("headers")
            if (headers != null) req.put("requestHeaders", JSONObject(headers.toString()))
            if (record.has("_storeId")) req.put("_storeId", record.optLong("_storeId"))
            recentRequests.addLast(req)
            trimRequests(System.currentTimeMillis())
        }

        val setCookies = mutableListOf<String>()
        record.optJSONObject("responseHeaders")?.let { headers ->
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.equals("Set-Cookie", true)) splitSetCookieHeader(headers.opt(key)?.toString().orEmpty()).forEach { setCookies.add(it) }
            }
        }
        record.optString("responseHeadersRaw", "").lines().forEach { line ->
            val p = line.indexOf(':')
            if (p > 0 && line.substring(0, p).trim().equals("Set-Cookie", true)) splitSetCookieHeader(line.substring(p + 1).trim()).forEach { setCookies.add(it) }
        }
        if (setCookies.isEmpty()) return

        setCookies.forEach { raw ->
            val parsed = parseSetCookie(raw) ?: return@forEach
            val host = scopeHost(url, parsed.domain)
            val trace = JSONObject()
                .put("time", endTime)
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
                .put("raw", raw)
                .put("requestBody", record.optString("requestBody", ""))
                .put("requestMimeType", record.optString("requestMimeType", ""))
            val headers = record.optJSONObject("requestHeaders") ?: record.optJSONObject("headers")
            if (headers != null) trace.put("requestHeaders", JSONObject(headers.toString()))
            if (record.has("_storeId")) trace.put("_storeId", record.optLong("_storeId"))
            addTrace(trace)
            lastExact[cookieKey(host, parsed.name)] = endTime
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
        parts.drop(1).forEach { p0 ->
            val p = p0.trim()
            when {
                p.startsWith("domain=", true) -> domain = p.substringAfter('=').trim().trimStart('.')
                p.startsWith("path=", true) -> path = p.substringAfter('=').trim()
            }
        }
        val lower = raw.lowercase(Locale.US)
        val delete = Regex("max-age\\s*=\\s*0", RegexOption.IGNORE_CASE).containsMatchIn(raw) || lower.contains("01 jan 1970") || lower.contains("01-jan-1970")
        return ParsedCookie(name, value, if (delete) "DELETE" else "SET", domain, path)
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
        if (request != null) copyRequestData(request, trace, delta)
        addTrace(trace)
    }

    private fun copyRequestData(from: JSONObject, to: JSONObject, delta: Long? = null) {
        to.put("method", from.optString("method", "GET"))
            .put("status", from.optInt("status", 0))
            .put("url", from.optString("url", ""))
            .put("requestSource", from.optString("source", ""))
            .put("requestBody", from.optString("requestBody", ""))
            .put("requestMimeType", from.optString("requestMimeType", ""))
        from.optJSONObject("requestHeaders")?.let { to.put("requestHeaders", JSONObject(it.toString())) }
        if (from.has("_storeId")) to.put("_storeId", from.optLong("_storeId"))
        if (delta != null) to.put("deltaMs", delta)
    }

    private fun likelyRequest(now: Long): JSONObject? {
        trimRequests(now)
        var best: JSONObject? = null
        var bestScore = Long.MAX_VALUE
        val it = recentRequests.descendingIterator()
        while (it.hasNext()) {
            val candidate = it.next()
            val delta = abs(now - candidate.optLong("time", now))
            if (delta > 2000L) continue
            val penalty = when (candidate.optString("source", "")) {
                "fetch", "xhr", "resource-copy", "replay" -> 0L
                "resource-timing", "navigation-timing" -> 80L
                else -> 160L
            }
            val score = delta + penalty
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
        val domain = event.optString("domain", "").trimStart('.').lowercase(Locale.US)
        val eventHost = if (domain.isNotBlank()) domain else scopeHost(event.optString("page", event.optString("url", "")), "")
        if (eventHost.isBlank() || host.isBlank()) return true
        return host == eventHost || host.endsWith(".$eventHost") || eventHost.endsWith(".$host")
    }

    private fun sourceShort(event: JSONObject?): String {
        if (event == null) return "источник неизвестен"
        val source = when (event.optString("origin", "")) {
            "HTTP_RESPONSE" -> "HTTP Set-Cookie"
            "JAVASCRIPT" -> event.optString("mechanism", "JavaScript")
            "LIKELY_HTTP_RESPONSE" -> "вероятно HTTP"
            else -> event.optString("mechanism", "неизвестно")
        }
        return "$source · ${confidenceRu(event.optString("confidence", "UNKNOWN"))}"
    }

    private fun confidenceRu(value: String) = when (value) {
        "EXACT" -> "точно"
        "MEDIUM" -> "вероятно"
        "LOW" -> "предположение"
        else -> "неизвестно"
    }

    private fun currentOrigin(history: List<JSONObject>, currentValue: String?): JSONObject? {
        if (currentValue != null) {
            history.asReversed().firstOrNull { it.optString("action") != "DELETE" && it.optString("value") == currentValue && it.optString("confidence") != "UNKNOWN" }?.let { return it }
        }
        return history.asReversed().firstOrNull { it.optString("action") != "DELETE" }
    }

    private fun birthEvent(history: List<JSONObject>): JSONObject? {
        history.firstOrNull { it.optString("action") in setOf("CREATE", "SET") && it.optString("confidence") == "EXACT" }?.let { return it }
        history.firstOrNull { it.optString("action") in setOf("CREATE", "SET", "OBSERVED") }?.let { return it }
        return history.firstOrNull()
    }

    private fun regenerationEvent(history: List<JSONObject>, currentValue: String?): JSONObject? {
        val current = currentOrigin(history, currentValue)
        if (current != null && current.optString("origin") in setOf("HTTP_RESPONSE", "LIKELY_HTTP_RESPONSE", "JAVASCRIPT")) return current
        return birthEvent(history)
    }

    private fun showCookieList(activity: NetworkDebuggerActivity) {
        val browser = browserRef.get()
        val web = browser?.let { webOf(it) }
        val page = web?.url.orEmpty()
        val active = if (page.isBlank()) emptyMap() else parseCookieHeader(CookieManager.getInstance().getCookie(page).orEmpty())
        val host = scopeHost(page, "")
        val relevant = events.filter { relevantToHost(it, host) }
        val inactive = relevant.map { it.optString("name", "") }.filter { it.isNotBlank() && it !in active }.distinct().sorted()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 10))
            setBackgroundColor(ink)
        }
        root.addView(TextView(activity).apply {
            text = "Страница: ${page.ifBlank { "—" }}\nАктивных куки: ${active.size}"
            setTextColor(muted)
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 8))
        })

        var dialog: AlertDialog? = null
        if (active.isEmpty()) root.addView(sectionText(activity, "Активных куки нет"))
        active.toSortedMap().forEach { (name, value) ->
            val history = relevant.filter { it.optString("name") == name }.sortedBy { it.optLong("time", 0L) }
            val origin = currentOrigin(history, value)
            root.addView(cookieRow(activity, name, value, sourceShort(origin), true) {
                dialog?.dismiss()
                showCookieDetails(activity, name, value, page, host, true)
            })
        }

        if (inactive.isNotEmpty()) {
            root.addView(sectionText(activity, "ИСТОРИЯ / НЕАКТИВНЫЕ"))
            inactive.forEach { name ->
                val history = relevant.filter { it.optString("name") == name }.sortedBy { it.optLong("time", 0L) }
                val latest = history.lastOrNull()
                root.addView(cookieRow(activity, name, latest?.optString("value", "").orEmpty(), sourceShort(latest), false) {
                    dialog?.dismiss()
                    showCookieDetails(activity, name, null, page, host, false)
                })
            }
        }

        val scroll = ScrollView(activity).apply { setBackgroundColor(ink); addView(root) }
        dialog = AlertDialog.Builder(activity).setTitle("Куки").setView(scroll).setNegativeButton("Закрыть", null).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(round(activity, ink, 16, line))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(cyan)
        }
        dialog.show()
    }

    private fun cookieRow(activity: Activity, name: String, value: String, source: String, active: Boolean, click: () -> Unit): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = round(activity, if (active) surface else surface2, 10, line)
            setPadding(dp(activity, 11), dp(activity, 9), dp(activity, 11), dp(activity, 9))
            addView(TextView(activity).apply {
                text = name
                setTextColor(if (active) white else muted)
                textSize = 11f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                val preview = if (value.length > 80) value.take(80) + "…" else value
                text = "${if (active) preview else "неактивна"}\n$source"
                setTextColor(if (active) accent else muted)
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(activity, 4), 0, 0)
            })
            setOnClickListener { click() }
        }.also { (it.layoutParams as? LinearLayout.LayoutParams) }
    }

    private fun showCookieDetails(activity: NetworkDebuggerActivity, name: String, currentValue: String?, page: String, host: String, active: Boolean) {
        val history = events.filter { it.optString("name") == name && relevantToHost(it, host) }.sortedBy { it.optLong("time", 0L) }
        val birth = birthEvent(history)
        val origin = currentOrigin(history, currentValue)
        val regen = regenerationEvent(history, currentValue)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 12))
            setBackgroundColor(ink)
        }

        root.addView(block(activity, buildString {
            append(name)
            append(if (active) "\n\nТЕКУЩЕЕ ЗНАЧЕНИЕ\n${currentValue.orEmpty()}" else "\n\nСейчас cookie не активна")
        }, true))

        root.addView(sectionText(activity, "КАК ОНА РОДИЛАСЬ"))
        root.addView(block(activity, if (birth == null) "Рождение не зафиксировано. Cookie могла существовать до запуска трассировки." else formatOrigin(birth), false))

        if (origin != null && origin !== birth) {
            root.addView(sectionText(activity, "ИСТОЧНИК ТЕКУЩЕГО ЗНАЧЕНИЯ"))
            root.addView(block(activity, formatOrigin(origin), false))
        }

        root.addView(sectionText(activity, "КАК ПОЛУЧИТЬ ЕЁ СНОВА"))
        val recipe = regenerationRecipe(regen)
        root.addView(block(activity, recipe, false))

        if (regen != null) {
            when (regen.optString("origin", "")) {
                "HTTP_RESPONSE", "LIKELY_HTTP_RESPONSE" -> {
                    val url = regen.optString("url", "")
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        root.addView(actionButton(activity, if (regen.optString("confidence") == "EXACT") "ПОВТОРИТЬ ИСХОДНЫЙ ЗАПРОС" else "ПОВТОРИТЬ ВЕРОЯТНЫЙ ЗАПРОС") {
                            val headers = headersMap(regen.optJSONObject("requestHeaders"))
                            val original = JSONObject().put("url", url).put("method", regen.optString("method", "GET"))
                            if (regen.has("_storeId")) original.put("_storeId", regen.optLong("_storeId"))
                            val ok = NetworkRequestActions.replay(activity, original, regen.optString("method", "GET"), url, headers, regen.optString("requestBody", ""))
                            Toast.makeText(activity, if (ok) "Запрос отправляется" else "Не удалось повторить запрос", Toast.LENGTH_SHORT).show()
                        })
                        root.addView(actionButton(activity, "КОПИРОВАТЬ cURL") { copyText(activity, "cURL", curlFor(regen)) })
                    }
                }
                "JAVASCRIPT" -> {
                    val raw = regen.optString("raw", "")
                    if (raw.isNotBlank()) root.addView(actionButton(activity, "КОПИРОВАТЬ JS SETTER") { copyText(activity, "JS setter", jsSetter(regen)) })
                }
            }
        }

        root.addView(sectionText(activity, "ИСТОРИЯ"))
        if (history.isEmpty()) root.addView(block(activity, "История пока отсутствует.", false))
        history.asReversed().forEach { root.addView(block(activity, formatHistory(it), false)) }

        val scroll = ScrollView(activity).apply { setBackgroundColor(ink); addView(root) }
        val dialog = AlertDialog.Builder(activity).setTitle("COOKIE · $name").setView(scroll).setNegativeButton("Назад", null).create()
        dialog.setOnShowListener {
            dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.97).toInt(), (activity.resources.displayMetrics.heightPixels * 0.92).toInt())
            dialog.window?.setBackgroundDrawable(round(activity, ink, 16, line))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(cyan)
                setOnClickListener { dialog.dismiss(); showCookieList(activity) }
            }
        }
        dialog.show()
    }

    private fun formatOrigin(event: JSONObject): String = buildString {
        val time = event.optLong("time", 0L)
        if (time > 0L) append("Время: ").append(timeText(time)).append('\n')
        append("Механизм: ").append(event.optString("mechanism", "—")).append('\n')
        append("Точность: ").append(confidenceRu(event.optString("confidence", "UNKNOWN"))).append('\n')
        val url = event.optString("url", "")
        if (url.isNotBlank()) {
            append("Запрос: ").append(event.optString("method", "GET"))
            val status = event.optInt("status", 0)
            if (status > 0) append("  ").append(status)
            append('\n').append(url).append('\n')
        } else event.optString("page", "").takeIf { it.isNotBlank() }?.let { append("Страница: ").append(it).append('\n') }
        event.optString("raw", "").takeIf { it.isNotBlank() }?.let { append("Set/Raw: ").append(it).append('\n') }
        event.optString("stack", "").takeIf { it.isNotBlank() }?.let { append("JS stack:\n").append(it.take(3000)).append('\n') }
        if (event.has("deltaMs")) append("Cookie изменилась через ").append(event.optLong("deltaMs")).append(" мс после этого запроса\n")
    }.trimEnd()

    private fun regenerationRecipe(event: JSONObject?): String {
        if (event == null) return "Недостаточно данных, чтобы предложить способ воспроизведения. Нужно поймать момент создания cookie после запуска приложения."
        return when (event.optString("origin", "")) {
            "HTTP_RESPONSE" -> buildString {
                append("Cookie пришла в ответе Set-Cookie. Чтобы сервер сгенерировал её снова, повторите исходный HTTP-запрос.\n\n")
                append(requestRecipe(event))
                append("\n\nНиже есть кнопка повторения запроса. Новый ответ может выдать новое значение cookie, если серверная логика допускает повторную генерацию.")
            }
            "LIKELY_HTTP_RESPONSE" -> buildString {
                append("Cookie появилась сразу после этого запроса, но Set-Cookie в исходном ответе перехватить не удалось. Поэтому источник вероятный, а не доказанный.\n\n")
                append(requestRecipe(event))
                append("\n\nМожно повторить этот запрос и проверить, создастся ли cookie снова.")
            }
            "JAVASCRIPT" -> buildString {
                append("Cookie записана JavaScript через ").append(event.optString("mechanism", "JavaScript")).append(".\n")
                val stack = event.optString("stack", "")
                if (stack.isNotBlank()) append("Главный ориентир — JS stack ниже: он показывает код, который выполнил запись.\n")
                append("\nДля точного воспроизведения нового значения нужно повторить действие сайта, которое приводит к этому вызову. Простое повторение setter-а обычно лишь запишет старое значение.\n\n")
                append("Setter для проверки:\n").append(jsSetter(event))
            }
            else -> "Источник рождения пока не доказан. Трассировка видит факт появления/изменения cookie, но не может гарантированно назвать код или HTTP-ответ, создавший её."
        }
    }

    private fun requestRecipe(event: JSONObject): String = buildString {
        append(event.optString("method", "GET")).append(' ').append(event.optString("url", "—"))
        val headers = event.optJSONObject("requestHeaders")
        if (headers != null && headers.length() > 0) {
            append("\n\nHEADERS\n")
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                append(key).append(": ").append(headers.opt(key)).append('\n')
            }
        }
        val body = event.optString("requestBody", "")
        if (body.isNotBlank()) append("\nBODY\n").append(body)
    }.trimEnd()

    private fun formatHistory(event: JSONObject): String = buildString {
        append(timeText(event.optLong("time", 0L))).append("  ").append(event.optString("action", "EVENT")).append('\n')
        append(sourceShort(event)).append('\n')
        val url = event.optString("url", "")
        if (url.isNotBlank()) append(event.optString("method", "GET")).append(' ').append(url).append('\n')
        if (event.has("deltaMs")) append("Δ ").append(event.optLong("deltaMs")).append(" ms\n")
        val raw = event.optString("raw", "")
        if (raw.isNotBlank()) append(raw)
    }.trimEnd()

    private fun jsSetter(event: JSONObject): String {
        val raw = event.optString("raw", "")
        return if (event.optString("mechanism", "").startsWith("CookieStore")) {
            "// исходная запись была через ${event.optString("mechanism")}\n// raw: $raw"
        } else "document.cookie = ${jsQuote(raw)};"
    }

    private fun jsQuote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun headersMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.opt(key)?.toString().orEmpty()
        }
        return out
    }

    private fun curlFor(event: JSONObject): String = buildString {
        val method = event.optString("method", "GET")
        val url = event.optString("url", "")
        append("curl -X ").append(shellQuote(method)).append(' ').append(shellQuote(url))
        headersMap(event.optJSONObject("requestHeaders")).forEach { (name, value) -> append(" \\\n  -H ").append(shellQuote("$name: $value")) }
        val body = event.optString("requestBody", "")
        if (body.isNotBlank()) append(" \\\n  --data-raw ").append(shellQuote(body))
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun copyText(activity: Activity, label: String, value: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(activity, "$label скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun block(activity: Activity, textValue: String, strong: Boolean): TextView = TextView(activity).apply {
        text = textValue
        setTextColor(if (strong) white else white)
        textSize = if (strong) 11f else 10f
        typeface = if (strong) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        setTextIsSelectable(true)
        isFocusable = true
        isFocusableInTouchMode = true
        setPadding(dp(activity, 10), dp(activity, 9), dp(activity, 10), dp(activity, 9))
        background = round(activity, surface2, 9, line)
    }

    private fun sectionText(activity: Activity, value: String): TextView = TextView(activity).apply {
        text = value
        setTextColor(cyan)
        textSize = 9f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = .08f
        setPadding(dp(activity, 4), dp(activity, 10), dp(activity, 4), dp(activity, 5))
    }

    private fun actionButton(activity: Activity, label: String, click: () -> Unit): Button = Button(activity).apply {
        text = label
        isAllCaps = false
        setTextColor(cyan)
        textSize = 9.5f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(activity, 9), 0, dp(activity, 9), 0)
        background = round(activity, surface, 9, line)
        setOnClickListener { click() }
    }

    private fun timeText(ms: Long): String = if (ms > 0L) SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms)) else "—"

    private fun exportJson(page: String): JSONObject {
        val arr = JSONArray()
        events.forEach { arr.put(JSONObject(it.toString())) }
        return JSONObject().put("format", "evrasia-cookie-trace-v2").put("generatedAt", System.currentTimeMillis()).put("page", page).put("events", arr)
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
        button.setOnClickListener { showCookieList(activity) }
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
                handler.postDelayed({ rewireCookieButton(activity) }, 100)
                handler.postDelayed({ rewireCookieButton(activity) }, 500)
                handler.postDelayed({ rewireCookieButton(activity) }, 1200)
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
