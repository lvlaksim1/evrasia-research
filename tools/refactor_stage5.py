from pathlib import Path

activity_path = Path('app/src/main/java/ru/evrasia/research/WebResearchV10Activity.kt')
activity = activity_path.read_text()

for old in [
    'import java.net.HttpURLConnection\n',
    'import java.net.URL\n',
    'import java.util.concurrent.Executors\n',
]:
    if old not in activity:
        raise SystemExit(f'missing import: {old!r}')
    activity = activity.replace(old, '', 1)

field_old = '''    private val archive = ResearchArchive()\n    private val downloadingScripts = ConcurrentHashMap.newKeySet<String>()\n    private val downloadingResources = ConcurrentHashMap.newKeySet<String>()\n    private val scriptChunks = ConcurrentHashMap<String, MutableMap<Int, String>>()\n'''
field_new = '''    private val archive = ResearchArchive()\n    private lateinit var resourceCapture: WebResourceCapture\n    private val scriptChunks = ConcurrentHashMap<String, MutableMap<Int, String>>()\n'''
if field_old not in activity:
    raise SystemExit('capture state field block not found')
activity = activity.replace(field_old, field_new, 1)

executor_old = '    private val captureExecutor = Executors.newFixedThreadPool(2)\n'
if executor_old not in activity:
    raise SystemExit('capture executor field not found')
activity = activity.replace(executor_old, '', 1)

clear_old = '            archive.clear(); downloadingScripts.clear(); downloadingResources.clear(); scriptChunks.clear(); artifactChunks.clear(); updateBadge(); updateStats()\n'
clear_new = '            archive.clear(); if (::resourceCapture.isInitialized) resourceCapture.clearPending(); scriptChunks.clear(); artifactChunks.clear(); updateBadge(); updateStats()\n'
if clear_old not in activity:
    raise SystemExit('clear action not found')
activity = activity.replace(clear_old, clear_new, 1)

ua_old = '''        userAgent = web.settings.userAgentString + " WebResearch/10"\n        web.settings.userAgentString = userAgent\n        WebView.setWebContentsDebuggingEnabled(true)\n'''
ua_new = '''        userAgent = web.settings.userAgentString + " WebResearch/10"\n        web.settings.userAgentString = userAgent\n        resourceCapture = WebResourceCapture(\n            archive = archive,\n            userAgent = userAgent,\n            record = { addRecord(it) },\n            onChanged = { scheduleBadgeUpdate() }\n        )\n        WebView.setWebContentsDebuggingEnabled(true)\n'''
if ua_old not in activity:
    raise SystemExit('user agent initialization block not found')
activity = activity.replace(ua_old, ua_new, 1)

intercept_old = '                    if (it.method.equals("GET", true) && (url.startsWith("http://") || url.startsWith("https://")) && shouldAutoCopyResource(url, headers)) captureResource(url, headers, "auto-static")\n'
intercept_new = '                    if (it.method.equals("GET", true) && (url.startsWith("http://") || url.startsWith("https://")) && resourceCapture.shouldAutoCopyResource(url, headers)) resourceCapture.captureResource(url, headers, "auto-static")\n'
if intercept_old not in activity:
    raise SystemExit('resource interception line not found')
activity = activity.replace(intercept_old, intercept_new, 1)

start = activity.find('    private fun looksLikeJs(')
end = activity.find('    fun ensureInstrumentation()', start)
if start < 0 or end < 0 or end <= start:
    raise SystemExit('resource capture method segment not found')
activity = activity[:start] + '''    fun requestResourceCopy(url: String, headersJson: JSONObject?): Boolean =\n        if (::resourceCapture.isInitialized) resourceCapture.requestResourceCopy(url, headersJson) else false\n\n''' + activity[end:]

bridge_old = '        @JavascriptInterface fun externalScript(url: String) { if (url.isNotBlank()) captureExternalScript(url, emptyMap()) }\n'
bridge_new = '        @JavascriptInterface fun externalScript(url: String) { if (url.isNotBlank() && ::resourceCapture.isInitialized) resourceCapture.captureExternalScript(url, emptyMap()) }\n'
if bridge_old not in activity:
    raise SystemExit('bridge externalScript method not found')
activity = activity.replace(bridge_old, bridge_new, 1)

destroy_old = '        captureExecutor.shutdownNow()\n'
destroy_new = '        if (::resourceCapture.isInitialized) resourceCapture.shutdown()\n'
if destroy_old not in activity:
    raise SystemExit('capture executor shutdown not found')
activity = activity.replace(destroy_old, destroy_new, 1)

activity_path.write_text(activity)

capture_path = Path('app/src/main/java/ru/evrasia/research/WebResourceCapture.kt')
if capture_path.exists():
    raise SystemExit('WebResourceCapture.kt already exists')
capture_path.write_text('''package ru.evrasia.research\n\nimport android.webkit.CookieManager\nimport org.json.JSONObject\nimport java.net.HttpURLConnection\nimport java.net.URL\nimport java.util.Locale\nimport java.util.concurrent.ConcurrentHashMap\nimport java.util.concurrent.Executors\n\ninternal class WebResourceCapture(\n    private val archive: ResearchArchive,\n    private val userAgent: String,\n    private val record: (JSONObject) -> Unit,\n    private val onChanged: () -> Unit\n) {\n    private val downloadingScripts = ConcurrentHashMap.newKeySet<String>()\n    private val downloadingResources = ConcurrentHashMap.newKeySet<String>()\n    private val executor = Executors.newFixedThreadPool(2)\n\n    fun clearPending() {\n        downloadingScripts.clear()\n        downloadingResources.clear()\n    }\n\n    fun shutdown() {\n        executor.shutdownNow()\n    }\n\n    private fun looksLikeJs(url: String): Boolean {\n        val clean = url.substringBefore('#').substringBefore('?').lowercase(Locale.US)\n        return clean.endsWith(".js") || clean.endsWith(".mjs")\n    }\n\n    private fun headerValue(headers: Map<String, String>, name: String): String =\n        headers.entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty()\n\n    fun shouldAutoCopyResource(url: String, headers: Map<String, String>): Boolean {\n        val clean = url.substringBefore('#').substringBefore('?').lowercase(Locale.US)\n        val staticExt = listOf(".js", ".mjs", ".css", ".map", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".otf")\n        if (staticExt.any { clean.endsWith(it) }) return true\n        val destination = headerValue(headers, "Sec-Fetch-Dest").lowercase(Locale.US)\n        if (destination in setOf("script", "style", "image", "font")) return true\n        val accept = headerValue(headers, "Accept").lowercase(Locale.US)\n        return accept.contains("image/") || accept.contains("font/") || accept.contains("text/css") || accept.contains("javascript")\n    }\n\n    fun requestResourceCopy(url: String, headersJson: JSONObject?): Boolean {\n        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false\n        val headers = linkedMapOf<String, String>()\n        if (headersJson != null) {\n            val keys = headersJson.keys()\n            while (keys.hasNext()) {\n                val key = keys.next()\n                headers[key] = headersJson.optString(key, "")\n            }\n        }\n        captureResource(url, headers, "manual-fallback")\n        return true\n    }\n\n    private fun openConnection(url: String, headers: Map<String, String>): HttpURLConnection {\n        val connection = URL(url).openConnection() as HttpURLConnection\n        connection.instanceFollowRedirects = true\n        connection.connectTimeout = 15000\n        connection.readTimeout = 45000\n        connection.requestMethod = "GET"\n        headers.forEach { (key, value) ->\n            if (!key.equals("Host", true) && !key.equals("Content-Length", true)) {\n                try { connection.setRequestProperty(key, value) } catch (_: Exception) {}\n            }\n        }\n        CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }\n        connection.setRequestProperty("User-Agent", userAgent)\n        return connection\n    }\n\n    fun captureResource(url: String, headers: Map<String, String>, copyMode: String) {\n        if (archive.resources.containsKey(url) || !downloadingResources.add(url)) return\n        executor.execute {\n            try {\n                val connection = openConnection(url, headers)\n                val started = System.currentTimeMillis()\n                val status = connection.responseCode\n                val bytes = (if (status in 200..399) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)\n                val responseHeaders = JSONObject()\n                connection.headerFields.filterKeys { it != null }.forEach { (key, values) -> responseHeaders.put(key, values.joinToString(", ")) }\n                val finalUrl = connection.url.toString()\n                val contentType = connection.contentType ?: ""\n                val resourceMeta = JSONObject()\n                    .put("status", status)\n                    .put("contentType", contentType)\n                    .put("finalUrl", finalUrl)\n                    .put("responseHeaders", responseHeaders)\n                    .put("copyMode", copyMode)\n                archive.putResource(url, bytes, resourceMeta)\n                if (looksLikeJs(url) || contentType.contains("javascript", true)) archive.putScript(url, bytes)\n                record(JSONObject()\n                    .put("source", "resource-copy")\n                    .put("copyMode", copyMode)\n                    .put("time", started)\n                    .put("duration", System.currentTimeMillis() - started)\n                    .put("method", "GET")\n                    .put("url", url)\n                    .put("status", status)\n                    .put("responseHeaders", responseHeaders)\n                    .put("mimeType", contentType)\n                    .put("responseSize", bytes.size)\n                    .put("redirectURL", if (finalUrl != url) finalUrl else ""))\n                connection.disconnect()\n            } catch (e: Exception) {\n                archive.putResourceMeta(url, JSONObject().put("error", e.toString()).put("copyMode", copyMode))\n                record(JSONObject()\n                    .put("source", "resource-copy")\n                    .put("copyMode", copyMode)\n                    .put("time", System.currentTimeMillis())\n                    .put("method", "GET")\n                    .put("url", url)\n                    .put("error", e.toString()))\n            } finally {\n                downloadingResources.remove(url)\n                onChanged()\n            }\n        }\n    }\n\n    fun captureExternalScript(url: String, headers: Map<String, String>) {\n        if (url.startsWith("blob:") || url.startsWith("data:") || archive.scripts.containsKey(url) || !downloadingScripts.add(url)) return\n        executor.execute {\n            try {\n                val connection = openConnection(url, headers)\n                val status = connection.responseCode\n                val bytes = (if (status in 200..399) connection.inputStream else connection.errorStream)?.use { it.readBytes() }\n                if (bytes != null) archive.putScript(url, bytes) else archive.putScriptError(url, "HTTP $status: empty body")\n                connection.disconnect()\n            } catch (e: Exception) {\n                archive.putScriptError(url, e.toString())\n            } finally {\n                downloadingScripts.remove(url)\n                onChanged()\n            }\n        }\n    }\n}\n''')

print('stage5 refactor applied')
