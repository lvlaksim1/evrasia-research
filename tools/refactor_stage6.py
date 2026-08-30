from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f'Expected block not found in {path}: {old[:120]!r}')
    p.write_text(s.replace(old, new, 1))

archive = 'app/src/main/java/ru/evrasia/research/ResearchArchive.kt'
replace_once(
    archive,
    '''    @Synchronized fun addRecord(record: JSONObject) {
        records.put(record)
        NetworkDebugStore.add(record)
    }

    @Synchronized fun clear() {
''',
    '''    @Synchronized fun addRecord(record: JSONObject) {
        records.put(record)
        NetworkDebugStore.add(record)
    }

    fun putScript(url: String, bytes: ByteArray) {
        val previous = scripts.put(url, bytes)
        if (previous == null && isInlineScript(url)) {
            NetworkDebugStore.add(JSONObject()
                .put("source", "js-file")
                .put("time", System.currentTimeMillis())
                .put("method", "JS")
                .put("url", url)
                .put("mimeType", "application/javascript")
                .put("responseSize", bytes.size)
                .put("responseBody", try { bytes.toString(Charsets.UTF_8) } catch (_: Exception) { "[binary]" }))
        }
    }

    fun putScriptError(url: String, error: String) {
        scriptErrors[url] = error
    }

    fun putResource(url: String, bytes: ByteArray, meta: JSONObject) {
        resources[url] = bytes
        resourceMeta[url] = meta
    }

    fun putResourceMeta(url: String, meta: JSONObject) {
        resourceMeta[url] = meta
    }

    fun putArtifact(key: String, bytes: ByteArray) {
        extraArtifacts[key] = bytes
    }

    fun updateSnapshot(value: JSONObject) {
        snapshot = value
    }

    private fun isInlineScript(url: String): Boolean =
        (!url.startsWith("http://") && !url.startsWith("https://")) || url.contains("#inline-")

    @Synchronized fun clear() {
'''
)
replace_once(
    archive,
    '''        snapshot = JSONObject()
    }
''',
    '''        snapshot = JSONObject()
        NetworkDebugStore.clear()
    }
'''
)

activity = 'app/src/main/java/ru/evrasia/research/WebResearchV10Activity.kt'
replace_once(
    activity,
    '''                archive.resources[url] = bytes
                archive.resourceMeta[url] = JSONObject().put("status", status).put("contentType", contentType).put("finalUrl", finalUrl).put("responseHeaders", responseHeaders).put("copyMode", copyMode)
                if (looksLikeJs(url) || contentType.contains("javascript", true)) archive.scripts[url] = bytes
''',
    '''                val resourceMeta = JSONObject().put("status", status).put("contentType", contentType).put("finalUrl", finalUrl).put("responseHeaders", responseHeaders).put("copyMode", copyMode)
                archive.putResource(url, bytes, resourceMeta)
                if (looksLikeJs(url) || contentType.contains("javascript", true)) archive.putScript(url, bytes)
'''
)
replace_once(
    activity,
    '''                archive.resourceMeta[url] = JSONObject().put("error", e.toString()).put("copyMode", copyMode)
''',
    '''                archive.putResourceMeta(url, JSONObject().put("error", e.toString()).put("copyMode", copyMode))
'''
)
replace_once(
    activity,
    '''                if (bytes != null) archive.scripts[url] = bytes else archive.scriptErrors[url] = "HTTP $status: empty body"
''',
    '''                if (bytes != null) archive.putScript(url, bytes) else archive.putScriptError(url, "HTTP $status: empty body")
'''
)
replace_once(
    activity,
    '''            } catch (e: Exception) { archive.scriptErrors[url] = e.toString() }
''',
    '''            } catch (e: Exception) { archive.putScriptError(url, e.toString()) }
'''
)
replace_once(
    activity,
    '''        @JavascriptInterface fun snapshot(json: String) { try { archive.snapshot = JSONObject(json); runOnUiThread { updateStats() } } catch (_: Exception) {} }
''',
    '''        @JavascriptInterface fun snapshot(json: String) { try { archive.updateSnapshot(JSONObject(json)); runOnUiThread { updateStats() } } catch (_: Exception) {} }
'''
)
replace_once(
    activity,
    '''                if (script) archive.scripts[key] = out.toString().toByteArray(Charsets.UTF_8) else archive.extraArtifacts[key] = out.toString().toByteArray(Charsets.UTF_8)
''',
    '''                if (script) archive.putScript(key, out.toString().toByteArray(Charsets.UTF_8)) else archive.putArtifact(key, out.toString().toByteArray(Charsets.UTF_8))
'''
)
replace_once(
    activity,
    '''        } catch (e: Exception) { if (script) archive.scriptErrors[key] = e.toString() }
''',
    '''        } catch (e: Exception) { if (script) archive.putScriptError(key, e.toString()) }
'''
)

app = 'app/src/main/java/ru/evrasia/research/WebResearchApp.kt'
replace_once(app, '    private var mirroredCount=0\n', '')
replace_once(app, '    private val mirroredScripts=mutableSetOf<String>()\n', '')
replace_once(
    app,
    '    private val ticker=object:Runnable{override fun run(){syncNetworkStore();advancedTick++;browserRef.get()?.let{if(advancedTick%5==0){it.ensureInstrumentation();installAdvancedCapture(it)}};handler.postDelayed(this,1000)}}\n',
    '    private val ticker=object:Runnable{override fun run(){advancedTick++;browserRef.get()?.let{if(advancedTick%5==0){it.ensureInstrumentation();installAdvancedCapture(it)}};handler.postDelayed(this,1000)}}\n'
)
s = Path(app).read_text()
start = s.index('    fun syncNetworkStore(){\n')
end = s.index('    fun currentCookiesText():String{\n', start)
Path(app).write_text(s[:start] + s[end:])
replace_once(
    app,
    'val net=iconButton(a,TechIconDrawable.Kind.NETWORK,true).apply{setOnClickListener{installAdvancedCapture(a);syncNetworkStore();a.startActivity(Intent(a,NetworkDebuggerActivity::class.java))}}',
    'val net=iconButton(a,TechIconDrawable.Kind.NETWORK,true).apply{setOnClickListener{installAdvancedCapture(a);a.startActivity(Intent(a,NetworkDebuggerActivity::class.java))}}'
)

debugger = 'app/src/main/java/ru/evrasia/research/NetworkDebuggerActivity.kt'
replace_once(debugger, '        (application as? WebResearchApp)?.syncNetworkStore()\n', '')
