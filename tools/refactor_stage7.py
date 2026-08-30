from pathlib import Path

activity_path = Path('app/src/main/java/ru/evrasia/research/WebResearchV10Activity.kt')
activity = activity_path.read_text()

for old in [
    'import android.webkit.JavascriptInterface\n',
    'import java.util.concurrent.ConcurrentHashMap\n',
]:
    if old not in activity:
        raise SystemExit(f'missing import: {old!r}')
    activity = activity.replace(old, '', 1)

fields_old = '''    private val archive = ResearchArchive()\n    private lateinit var resourceCapture: WebResourceCapture\n    private val scriptChunks = ConcurrentHashMap<String, MutableMap<Int, String>>()\n    private val artifactChunks = ConcurrentHashMap<String, MutableMap<Int, String>>()\n'''
fields_new = '''    private val archive = ResearchArchive()\n    private lateinit var captureController: WebCaptureController\n'''
if fields_old not in activity:
    raise SystemExit('capture fields not found')
activity = activity.replace(fields_old, fields_new, 1)

snapshot_api_old = '    internal fun captureResearchSnapshot() = capturePageSnapshot()\n'
snapshot_api_new = '    internal fun captureResearchSnapshot() { if (::captureController.isInitialized) captureController.capturePageSnapshot() }\n'
if snapshot_api_old not in activity:
    raise SystemExit('snapshot api not found')
activity = activity.replace(snapshot_api_old, snapshot_api_new, 1)

clear_old = '            archive.clear(); if (::resourceCapture.isInitialized) resourceCapture.clearPending(); scriptChunks.clear(); artifactChunks.clear(); updateBadge(); updateStats()\n'
clear_new = '            archive.clear(); if (::captureController.isInitialized) captureController.clearPending(); updateBadge(); updateStats()\n'
if clear_old not in activity:
    raise SystemExit('clear action not found')
activity = activity.replace(clear_old, clear_new, 1)

init_old = '''        resourceCapture = WebResourceCapture(\n            archive = archive,\n            userAgent = userAgent,\n            record = { addRecord(it) },\n            onChanged = { scheduleBadgeUpdate() }\n        )\n        WebView.setWebContentsDebuggingEnabled(true)\n        web.addJavascriptInterface(Bridge(this), "EvrasiaResearch")\n'''
init_new = '''        captureController = WebCaptureController(\n            activity = this,\n            web = web,\n            archive = archive,\n            userAgent = userAgent,\n            record = { addRecord(it) },\n            onChanged = { scheduleBadgeUpdate() },\n            onSnapshot = { runOnUiThread { updateStats() } }\n        )\n        WebView.setWebContentsDebuggingEnabled(true)\n        web.addJavascriptInterface(captureController.bridge, "EvrasiaResearch")\n'''
if init_old not in activity:
    raise SystemExit('capture init block not found')
activity = activity.replace(init_old, init_new, 1)

activity = activity.replace('statsHandler.postDelayed({ ensureInstrumentation() }, 100)', 'statsHandler.postDelayed({ captureController.ensureInstrumentation() }, 100)')
activity = activity.replace('statsHandler.postDelayed({ ensureInstrumentation() }, 350)', 'statsHandler.postDelayed({ captureController.ensureInstrumentation() }, 350)')
activity = activity.replace('ensureInstrumentation(); captureLightPageSnapshot(); updateStats()', 'captureController.ensureInstrumentation(); captureController.captureLightPageSnapshot(); updateStats()')
activity = activity.replace('resourceCapture.shouldAutoCopyResource(url, headers)) resourceCapture.captureResource(url, headers, "auto-static")', 'captureController.shouldAutoCopyResource(url, headers)) captureController.captureResource(url, headers, "auto-static")')

methods_start = activity.find('    fun requestResourceCopy(url: String, headersJson: JSONObject?): Boolean =')
methods_end = activity.find('    private fun addRecord(record: JSONObject)', methods_start)
if methods_start < 0 or methods_end < 0:
    raise SystemExit('capture method block not found')
methods_new = '''    fun requestResourceCopy(url: String, headersJson: JSONObject?): Boolean =\n        if (::captureController.isInitialized) captureController.requestResourceCopy(url, headersJson) else false\n\n    fun ensureInstrumentation() {\n        if (::captureController.isInitialized) captureController.ensureInstrumentation()\n    }\n\n    private fun capturePageSnapshot() {\n        if (::captureController.isInitialized) captureController.capturePageSnapshot()\n    }\n\n'''
activity = activity[:methods_start] + methods_new + activity[methods_end:]

activity = activity.replace('        if (::resourceCapture.isInitialized) resourceCapture.shutdown()\n', '        if (::captureController.isInitialized) captureController.shutdown()\n')

bridge_start = activity.find('    inner class Bridge(')
if bridge_start < 0:
    raise SystemExit('bridge block not found')
activity = activity[:bridge_start].rstrip() + '\n}\n'
activity_path.write_text(activity)

controller_path = Path('app/src/main/java/ru/evrasia/research/WebCaptureController.kt')
if controller_path.exists():
    raise SystemExit('WebCaptureController.kt already exists')
controller_path.write_text('''package ru.evrasia.research\n\nimport android.webkit.CookieManager\nimport android.webkit.JavascriptInterface\nimport android.webkit.WebView\nimport androidx.appcompat.app.AppCompatActivity\nimport org.json.JSONObject\nimport java.util.concurrent.ConcurrentHashMap\n\ninternal class WebCaptureController(\n    private val activity: AppCompatActivity,\n    private val web: WebView,\n    private val archive: ResearchArchive,\n    userAgent: String,\n    private val record: (JSONObject) -> Unit,\n    private val onChanged: () -> Unit,\n    private val onSnapshot: () -> Unit\n) {\n    private val scriptChunks = ConcurrentHashMap<String, MutableMap<Int, String>>()\n    private val artifactChunks = ConcurrentHashMap<String, MutableMap<Int, String>>()\n    private val resourceCapture = WebResourceCapture(\n        archive = archive,\n        userAgent = userAgent,\n        record = record,\n        onChanged = onChanged\n    )\n\n    val bridge = Bridge()\n\n    fun clearPending() {\n        resourceCapture.clearPending()\n        scriptChunks.clear()\n        artifactChunks.clear()\n    }\n\n    fun shutdown() {\n        resourceCapture.shutdown()\n    }\n\n    fun shouldAutoCopyResource(url: String, headers: Map<String, String>): Boolean =\n        resourceCapture.shouldAutoCopyResource(url, headers)\n\n    fun captureResource(url: String, headers: Map<String, String>, copyMode: String) {\n        resourceCapture.captureResource(url, headers, copyMode)\n    }\n\n    fun requestResourceCopy(url: String, headersJson: JSONObject?): Boolean =\n        resourceCapture.requestResourceCopy(url, headersJson)\n\n    fun ensureInstrumentation() {\n        web.evaluateJavascript(WebResearchScripts.instrumentation(), null)\n    }\n\n    fun captureLightPageSnapshot() {\n        val nativeCookies = CookieManager.getInstance().getCookie(web.url ?: "") ?: ""\n        web.evaluateJavascript(WebResearchScripts.lightSnapshot(nativeCookies), null)\n    }\n\n    fun capturePageSnapshot() {\n        val nativeCookies = CookieManager.getInstance().getCookie(web.url ?: "") ?: ""\n        web.evaluateJavascript(WebResearchScripts.fullSnapshot(nativeCookies), null)\n    }\n\n    inner class Bridge {\n        @JavascriptInterface fun record(json: String) {\n            try { record(JSONObject(json)) } catch (_: Exception) {}\n        }\n\n        @JavascriptInterface fun snapshot(json: String) {\n            try {\n                archive.updateSnapshot(JSONObject(json))\n                onSnapshot()\n            } catch (_: Exception) {}\n        }\n\n        @JavascriptInterface fun externalScript(url: String) {\n            if (url.isNotBlank()) resourceCapture.captureExternalScript(url, emptyMap())\n        }\n\n        @JavascriptInterface fun requestSnapshot() {\n            activity.runOnUiThread { capturePageSnapshot() }\n        }\n\n        @JavascriptInterface fun scriptChunk(url: String, index: Int, total: Int, chunk: String) {\n            collectChunk(url, index, total, chunk, true)\n        }\n\n        @JavascriptInterface fun artifactChunk(key: String, index: Int, total: Int, chunk: String) {\n            collectChunk(key, index, total, chunk, false)\n        }\n    }\n\n    private fun collectChunk(key: String, index: Int, total: Int, chunk: String, script: Boolean) {\n        try {\n            val all = if (script) scriptChunks else artifactChunks\n            val map = all.getOrPut(key) { ConcurrentHashMap() }\n            map[index] = chunk\n            if (map.size == total) {\n                val out = StringBuilder()\n                for (i in 0 until total) out.append(map[i] ?: "")\n                if (script) {\n                    archive.putScript(key, out.toString().toByteArray(Charsets.UTF_8))\n                } else {\n                    archive.putArtifact(key, out.toString().toByteArray(Charsets.UTF_8))\n                }\n                all.remove(key)\n                onChanged()\n            }\n        } catch (e: Exception) {\n            if (script) archive.putScriptError(key, e.toString())\n        }\n    }\n}\n''')

print('stage7 refactor applied')
