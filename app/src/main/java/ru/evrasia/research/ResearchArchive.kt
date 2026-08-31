package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ResearchArchive {
    val records = JSONArray()
    val scripts = ConcurrentHashMap<String, ByteArray>()
    val scriptErrors = ConcurrentHashMap<String, String>()
    val resources = ConcurrentHashMap<String, ByteArray>()
    val resourceMeta = ConcurrentHashMap<String, JSONObject>()
    val extraArtifacts = ConcurrentHashMap<String, ByteArray>()
    @Volatile var snapshot = JSONObject()

    @Synchronized fun addRecord(record: JSONObject) {
        NetworkRecordPipeline.appendRawAndDebug(records, record)
    }

    fun putScript(url: String, bytes: ByteArray) {
        val previous = scripts.put(url, bytes)
        if (previous == null && isInlineScript(url)) {
            NetworkRecordPipeline.addDebuggerOnly(JSONObject()
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
        while (records.length() > 0) records.remove(records.length() - 1)
        scripts.clear()
        scriptErrors.clear()
        resources.clear()
        resourceMeta.clear()
        extraArtifacts.clear()
        snapshot = JSONObject()
        NetworkRecordPipeline.clearDebugger()
    }

    fun writeZip(output: OutputStream, pageUrl: String) {
        ZipOutputStream(output).use { zip ->
            fun add(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }

            add("network.har", buildHar().toString(2).toByteArray(Charsets.UTF_8))
            add("api-summary.json", buildApiSummary().toString(2).toByteArray(Charsets.UTF_8))
            add("actions.json", buildSourceLog(setOf("user-action", "navigation", "form-submit", "history")).toString(2).toByteArray(Charsets.UTF_8))
            add("dom-mutations.json", buildSourceLog(setOf("dom-mutation", "shadow-root", "custom-element")).toString(2).toByteArray(Charsets.UTF_8))
            add("realtime.json", buildSourceLog(setOf("websocket-open", "websocket-state", "websocket-send", "websocket-receive", "sse-open", "sse-state", "sse-message")).toString(2).toByteArray(Charsets.UTF_8))
            add("performance.json", buildSourceLog(setOf("performance", "long-task", "resource-timing", "navigation-timing")).toString(2).toByteArray(Charsets.UTF_8))
            add("raw-events.json", JSONObject()
                .put("format", "evrasia-research-v4")
                .put("exportedAt", System.currentTimeMillis())
                .put("page", pageUrl)
                .put("records", records)
                .toString(2).toByteArray(Charsets.UTF_8))
            add("page-snapshot.json", snapshot.toString(2).toByteArray(Charsets.UTF_8))
            snapshot.optString("html", "").takeIf { it.isNotEmpty() }?.let { add("page.html", it.toByteArray(Charsets.UTF_8)) }

            val jsManifest = JSONArray()
            scripts.entries.sortedBy { it.key }.forEachIndexed { index, item ->
                val file = "js/${safeName(item.key, index + 1, "script.js")}" 
                add(file, item.value)
                jsManifest.put(JSONObject().put("url", item.key).put("file", file).put("bytes", item.value.size))
            }
            scriptErrors.entries.sortedBy { it.key }.forEach { jsManifest.put(JSONObject().put("url", it.key).put("error", it.value)) }
            add("js/manifest.json", jsManifest.toString(2).toByteArray(Charsets.UTF_8))

            val resourceManifest = JSONArray()
            resources.entries.sortedBy { it.key }.forEachIndexed { index, item ->
                val meta = resourceMeta[item.key] ?: JSONObject()
                val fallback = guessFileName(item.key, meta.optString("contentType", ""))
                val file = "resources/${safeName(item.key, index + 1, fallback)}"
                add(file, item.value)
                resourceManifest.put(JSONObject(meta.toString()).put("url", item.key).put("file", file).put("bytes", item.value.size))
            }
            add("resources/manifest.json", resourceManifest.toString(2).toByteArray(Charsets.UTF_8))

            extraArtifacts.entries.sortedBy { it.key }.forEach { add("browser/${safePath(it.key)}", it.value) }
        }
    }

    private fun buildSourceLog(sources: Set<String>): JSONArray {
        val out = JSONArray()
        synchronized(this) {
            for (i in 0 until records.length()) {
                val r = records.optJSONObject(i) ?: continue
                if (sources.contains(r.optString("source", ""))) out.put(JSONObject(r.toString()))
            }
        }
        return out
    }

    private fun buildApiSummary(): JSONObject {
        val endpoints = linkedMapOf<String, JSONObject>()
        val routes = linkedSetOf<String>()
        synchronized(this) {
            for (i in 0 until records.length()) {
                val r = records.optJSONObject(i) ?: continue
                val source = r.optString("source", "")
                if (source == "navigation" || source == "history" || source == "user-action") {
                    r.optString("page", r.optString("url", "")).takeIf { it.startsWith("http") }?.let { routes.add(it) }
                }
                if (!r.has("url")) continue
                val url = r.optString("url", "")
                val method = r.optString("method", "GET").ifBlank { "GET" }.uppercase(Locale.US)
                if (!(url.startsWith("http://") || url.startsWith("https://") || url.startsWith("ws://") || url.startsWith("wss://"))) continue
                if (source in setOf("webview", "resource-copy", "script-archive", "console", "performance", "resource-timing")) continue
                val key = "$method ${normalizeEndpoint(url)}"
                val e = endpoints.getOrPut(key) {
                    JSONObject()
                        .put("method", method)
                        .put("endpoint", normalizeEndpoint(url))
                        .put("examples", JSONArray())
                        .put("statuses", JSONObject())
                        .put("sources", JSONObject())
                        .put("count", 0)
                }
                e.put("count", e.optInt("count") + 1)
                incrementObject(e.getJSONObject("sources"), source.ifBlank { "unknown" })
                if (r.has("status")) incrementObject(e.getJSONObject("statuses"), r.optInt("status").toString())
                val examples = e.getJSONArray("examples")
                if (examples.length() < 5) examples.put(url)
                val body = r.optString("requestBody", "")
                val graph = r.optJSONObject("graphql")
                if (graph != null) {
                    val ops = e.optJSONArray("graphql") ?: JSONArray().also { e.put("graphql", it) }
                    if (ops.length() < 20) ops.put(JSONObject(graph.toString()))
                } else if (body.contains("operationName") || body.contains("query")) {
                    try {
                        val parsed = JSONObject(body)
                        if (parsed.has("query") || parsed.has("operationName")) {
                            val ops = e.optJSONArray("graphql") ?: JSONArray().also { e.put("graphql", it) }
                            if (ops.length() < 20) ops.put(JSONObject()
                                .put("operationName", parsed.optString("operationName", ""))
                                .put("variables", parsed.opt("variables") ?: JSONObject.NULL))
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        val arr = JSONArray()
        endpoints.values.sortedByDescending { it.optInt("count") }.forEach { arr.put(it) }
        return JSONObject()
            .put("generatedAt", System.currentTimeMillis())
            .put("endpointCount", arr.length())
            .put("endpoints", arr)
            .put("observedPages", JSONArray(routes.toList()))
    }

    private fun incrementObject(obj: JSONObject, key: String) {
        obj.put(key, obj.optInt(key, 0) + 1)
    }

    private fun normalizeEndpoint(url: String): String {
        return try {
            val u = URL(url.replaceFirst("ws://", "http://").replaceFirst("wss://", "https://"))
            val path = u.path
                .replace(Regex("/[0-9]{2,}"), "/{id}")
                .replace(Regex("/[0-9a-fA-F]{8}-[0-9a-fA-F-]{20,}"), "/{uuid}")
            "${u.protocol}://${u.host}${if (u.port > 0 && u.port != u.defaultPort) ":${u.port}" else ""}$path"
        } catch (_: Exception) { url.substringBefore('?') }
    }

    private fun buildHar(): JSONObject {
        val entries = JSONArray()
        synchronized(this) {
            for (i in 0 until records.length()) {
                val r = records.optJSONObject(i) ?: continue
                if (!r.has("url")) continue
                val url = r.optString("url", "about:blank").ifBlank { "about:blank" }
                val method = r.optString("method", "GET").ifBlank { "GET" }
                if (method == "WS") continue
                val requestHeaders = when {
                    r.has("requestHeaders") -> headersArray(r.optJSONObject("requestHeaders"))
                    r.has("headers") -> headersArray(r.optJSONObject("headers"))
                    else -> JSONArray()
                }
                val requestBody = r.optString("requestBody", "")
                val request = JSONObject()
                    .put("method", method)
                    .put("url", url)
                    .put("httpVersion", r.optString("httpVersion", ""))
                    .put("headers", requestHeaders)
                    .put("queryString", queryArray(url))
                    .put("cookies", JSONArray())
                    .put("headersSize", -1)
                    .put("bodySize", if (r.has("requestBody")) requestBody.toByteArray().size else -1)
                if (r.has("requestBody")) request.put("postData", JSONObject().put("mimeType", r.optString("requestMimeType", "")).put("text", requestBody))

                val responseBody = r.optString("responseBody", "")
                val responseHeaders = if (r.has("responseHeaders")) headersArray(r.optJSONObject("responseHeaders")) else rawHeaders(r.optString("responseHeadersRaw", ""))
                val content = JSONObject()
                    .put("size", if (r.has("responseBody")) responseBody.toByteArray().size else r.optLong("responseSize", -1))
                    .put("mimeType", r.optString("mimeType", ""))
                if (r.has("responseBody")) content.put("text", responseBody)
                val response = JSONObject()
                    .put("status", r.optInt("status", 0))
                    .put("statusText", r.optString("statusText", r.optString("error", "")))
                    .put("httpVersion", r.optString("httpVersion", ""))
                    .put("headers", responseHeaders)
                    .put("cookies", JSONArray())
                    .put("content", content)
                    .put("redirectURL", r.optString("redirectURL", ""))
                    .put("headersSize", -1)
                    .put("bodySize", content.optLong("size", -1))

                entries.put(JSONObject()
                    .put("startedDateTime", isoTime(r.optLong("time", System.currentTimeMillis())))
                    .put("time", r.optLong("duration", 0))
                    .put("request", request)
                    .put("response", response)
                    .put("cache", JSONObject())
                    .put("timings", JSONObject().put("send", 0).put("wait", r.optLong("duration", 0)).put("receive", 0))
                    .put("_evrasiaSource", r.optString("source", "unknown")))
            }
        }
        return JSONObject().put("log", JSONObject()
            .put("version", "1.2")
            .put("creator", JSONObject().put("name", "Evrasia Research").put("version", "4"))
            .put("pages", JSONArray())
            .put("entries", entries))
    }

    private fun headersArray(obj: JSONObject?): JSONArray {
        val arr = JSONArray(); if (obj == null) return arr
        val it = obj.keys(); while (it.hasNext()) { val key = it.next(); arr.put(JSONObject().put("name", key).put("value", obj.optString(key, ""))) }
        return arr
    }

    private fun rawHeaders(raw: String): JSONArray {
        val arr = JSONArray(); raw.lines().forEach { line -> val p = line.indexOf(':'); if (p > 0) arr.put(JSONObject().put("name", line.substring(0, p).trim()).put("value", line.substring(p + 1).trim())) }; return arr
    }

    private fun queryArray(url: String): JSONArray {
        val arr = JSONArray(); val query = try { URL(url).query } catch (_: Exception) { null } ?: return arr
        query.split('&').filter { it.isNotEmpty() }.forEach { val p = it.indexOf('='); if (p >= 0) arr.put(JSONObject().put("name", it.substring(0, p)).put("value", it.substring(p + 1))) else arr.put(JSONObject().put("name", it).put("value", "")) }; return arr
    }

    private fun isoTime(ms: Long): String { val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US); f.timeZone = TimeZone.getTimeZone("UTC"); return f.format(Date(ms)) }

    private fun guessFileName(url: String, contentType: String): String {
        val fromUrl = url.substringBefore('#').substringBefore('?').substringAfterLast('/').ifBlank { "resource" }
        if (fromUrl.contains('.')) return fromUrl
        return when {
            contentType.contains("javascript", true) -> "$fromUrl.js"
            contentType.contains("json", true) -> "$fromUrl.json"
            contentType.contains("html", true) -> "$fromUrl.html"
            contentType.contains("css", true) -> "$fromUrl.css"
            contentType.contains("svg", true) -> "$fromUrl.svg"
            contentType.contains("png", true) -> "$fromUrl.png"
            contentType.contains("jpeg", true) -> "$fromUrl.jpg"
            else -> "$fromUrl.bin"
        }
    }

    private fun safeName(url: String, index: Int, fallback: String): String {
        val clean = url.substringBefore('#').substringBefore('?')
        val raw = clean.substringAfterLast('/').ifBlank { fallback }
        val base = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { fallback }
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
        return String.format(Locale.US, "%05d_%s_%s", index, hash, base)
    }

    private fun safePath(value: String): String = value.replace(Regex("[^A-Za-z0-9._/-]"), "_").trimStart('/').ifBlank { "artifact.bin" }
}
