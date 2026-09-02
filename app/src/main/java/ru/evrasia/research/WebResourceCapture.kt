package ru.evrasia.research

import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal class WebResourceCapture(
    private val archive: ResearchArchive,
    userAgent: String,
    private val record: (JSONObject) -> Unit,
    private val onChanged: () -> Unit
) {
    private val downloadingScripts = ConcurrentHashMap.newKeySet<String>()
    private val downloadingResources = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newFixedThreadPool(2)
    @Volatile private var currentUserAgent = userAgent

    fun clearPending() {
        downloadingScripts.clear()
        downloadingResources.clear()
    }

    fun updateUserAgent(userAgent: String) {
        currentUserAgent = userAgent
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun looksLikeJs(url: String): Boolean {
        val clean = url.substringBefore('#').substringBefore('?').lowercase(Locale.US)
        return clean.endsWith(".js") || clean.endsWith(".mjs")
    }

    private fun headerValue(headers: Map<String, String>, name: String): String =
        headers.entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty()

    fun shouldAutoCopyResource(url: String, headers: Map<String, String>): Boolean {
        val clean = url.substringBefore('#').substringBefore('?').lowercase(Locale.US)
        val staticExt = listOf(".js", ".mjs", ".css", ".map", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".otf")
        if (staticExt.any { clean.endsWith(it) }) return true
        val destination = headerValue(headers, "Sec-Fetch-Dest").lowercase(Locale.US)
        if (destination in setOf("script", "style", "image", "font")) return true
        val accept = headerValue(headers, "Accept").lowercase(Locale.US)
        return accept.contains("image/") || accept.contains("font/") || accept.contains("text/css") || accept.contains("javascript")
    }

    fun requestResourceCopy(url: String, headersJson: JSONObject?): Boolean {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false
        val headers = linkedMapOf<String, String>()
        if (headersJson != null) {
            val keys = headersJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                headers[key] = headersJson.optString(key, "")
            }
        }
        captureResource(url, headers, "manual-fallback")
        return true
    }

    private fun openConnection(url: String, headers: Map<String, String>): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15000
        connection.readTimeout = 45000
        connection.requestMethod = "GET"
        headers.forEach { (key, value) ->
            if (!key.equals("Host", true) && !key.equals("Content-Length", true)) {
                try { connection.setRequestProperty(key, value) } catch (_: Exception) {}
            }
        }
        CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
        connection.setRequestProperty("User-Agent", currentUserAgent)
        return connection
    }

    fun captureResource(url: String, headers: Map<String, String>, copyMode: String) {
        if (archive.resources.containsKey(url) || !downloadingResources.add(url)) return
        executor.execute {
            try {
                val connection = openConnection(url, headers)
                val started = System.currentTimeMillis()
                val status = connection.responseCode
                val bytes = (if (status in 200..399) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                val responseHeaders = JSONObject()
                connection.headerFields.filterKeys { it != null }.forEach { (key, values) -> responseHeaders.put(key, values.joinToString(", ")) }
                val finalUrl = connection.url.toString()
                val contentType = connection.contentType ?: ""
                val resourceMeta = JSONObject()
                    .put("status", status)
                    .put("contentType", contentType)
                    .put("finalUrl", finalUrl)
                    .put("responseHeaders", responseHeaders)
                    .put("copyMode", copyMode)
                archive.putResource(url, bytes, resourceMeta)
                if (looksLikeJs(url) || contentType.contains("javascript", true)) archive.putScript(url, bytes)
                record(JSONObject()
                    .put("source", "resource-copy")
                    .put("copyMode", copyMode)
                    .put("time", started)
                    .put("duration", System.currentTimeMillis() - started)
                    .put("method", "GET")
                    .put("url", url)
                    .put("status", status)
                    .put("responseHeaders", responseHeaders)
                    .put("mimeType", contentType)
                    .put("responseSize", bytes.size)
                    .put("redirectURL", if (finalUrl != url) finalUrl else ""))
                connection.disconnect()
            } catch (e: Exception) {
                archive.putResourceMeta(url, JSONObject().put("error", e.toString()).put("copyMode", copyMode))
                record(JSONObject()
                    .put("source", "resource-copy")
                    .put("copyMode", copyMode)
                    .put("time", System.currentTimeMillis())
                    .put("method", "GET")
                    .put("url", url)
                    .put("error", e.toString()))
            } finally {
                downloadingResources.remove(url)
                onChanged()
            }
        }
    }

    fun captureExternalScript(url: String, headers: Map<String, String>) {
        if (url.startsWith("blob:") || url.startsWith("data:") || archive.scripts.containsKey(url) || !downloadingScripts.add(url)) return
        executor.execute {
            try {
                val connection = openConnection(url, headers)
                val status = connection.responseCode
                val bytes = (if (status in 200..399) connection.inputStream else connection.errorStream)?.use { it.readBytes() }
                if (bytes != null) archive.putScript(url, bytes) else archive.putScriptError(url, "HTTP $status: empty body")
                connection.disconnect()
            } catch (e: Exception) {
                archive.putScriptError(url, e.toString())
            } finally {
                downloadingScripts.remove(url)
                onChanged()
            }
        }
    }
}
