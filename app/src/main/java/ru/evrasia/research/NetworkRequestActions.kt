package ru.evrasia.research

import android.app.Activity
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONObject
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale

object NetworkRequestActions {
    private const val MAX_TEXT_BODY = 4 * 1024 * 1024

    fun prepareFullExport(activity: Activity) {
        val browser = activeBrowser(activity) ?: return
        try {
            val method = WebResearchV10Activity::class.java.getDeclaredMethod("capturePageSnapshot")
            method.isAccessible = true
            method.invoke(browser)
        } catch (_: Exception) {}
    }

    fun writeFullExport(activity: Activity, output: OutputStream): Boolean {
        val browser = activeBrowser(activity) ?: return false
        val archive = archiveOf(browser) ?: return false
        return try {
            archive.writeZip(output, currentUrl(browser))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun fetchMissingBody(activity: Activity, event: JSONObject): Boolean {
        val url = event.optString("url", "")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (!event.optString("method", "GET").ifBlank { "GET" }.equals("GET", true)) return false
        execute(
            activity = activity,
            method = "GET",
            url = url,
            headers = headersOf(event),
            body = "",
            source = "resource-copy",
            mergeTargetId = event.optLong("_storeId", -1L),
            copyMode = "manual-fallback"
        )
        return true
    }

    fun replay(
        activity: Activity,
        original: JSONObject,
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String
    ): Boolean {
        val cleanMethod = method.trim().ifBlank { "GET" }.uppercase(Locale.US)
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        execute(
            activity = activity,
            method = cleanMethod,
            url = url,
            headers = headers,
            body = body,
            source = "replay",
            mergeTargetId = -1L,
            copyMode = "replay",
            replayOf = original.optLong("_storeId", -1L)
        )
        return true
    }

    fun responseBytes(activity: Activity, url: String): ByteArray? {
        val browser = activeBrowser(activity) ?: return null
        return archiveOf(browser)?.resources?.get(url)
    }

    private fun execute(
        activity: Activity,
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String,
        source: String,
        mergeTargetId: Long,
        copyMode: String,
        replayOf: Long = -1L
    ) {
        Thread {
            val started = System.currentTimeMillis()
            val browser = activeBrowser(activity)
            val archive = browser?.let { archiveOf(it) }
            val record = JSONObject()
                .put("source", source)
                .put("copyMode", copyMode)
                .put("time", started)
                .put("method", method)
                .put("url", url)
            if (mergeTargetId > 0L) record.put("_mergeTargetId", mergeTargetId)
            if (replayOf > 0L) record.put("_replayOfStoreId", replayOf)
            record.put("requestHeaders", JSONObject(headers))
            if (body.isNotEmpty()) record.put("requestBody", body)
            headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.let { record.put("requestMimeType", it) }

            var connection: HttpURLConnection? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                connection = c
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000
                c.readTimeout = 45000
                c.requestMethod = method
                headers.forEach { (name, value) ->
                    if (!name.equals("Host", true) && !name.equals("Content-Length", true)) {
                        try { c.setRequestProperty(name, value) } catch (_: Exception) {}
                    }
                }
                if (headers.keys.none { it.equals("Cookie", true) }) {
                    CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Cookie", it) }
                }
                if (headers.keys.none { it.equals("User-Agent", true) }) {
                    browserUserAgent(browser).takeIf { it.isNotBlank() }?.let { c.setRequestProperty("User-Agent", it) }
                }
                if (body.isNotEmpty() && method !in setOf("GET", "HEAD")) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }

                val status = c.responseCode
                val stream = if (status in 200..399) c.inputStream else c.errorStream
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                val responseHeaders = JSONObject()
                c.headerFields.filterKeys { it != null }.forEach { (name, values) -> responseHeaders.put(name, values.joinToString(", ")) }
                val contentType = c.contentType.orEmpty()
                val finalUrl = c.url.toString()
                val responseBody = if (isTextual(contentType, bytes)) decodeText(bytes, contentType) else "[binary]"

                record.put("duration", System.currentTimeMillis() - started)
                    .put("status", status)
                    .put("statusText", c.responseMessage.orEmpty())
                    .put("responseHeaders", responseHeaders)
                    .put("mimeType", contentType)
                    .put("responseSize", bytes.size)
                    .put("finalUrl", finalUrl)
                    .put("redirected", finalUrl != url)
                    .put("redirectURL", if (finalUrl != url) finalUrl else "")
                    .put("responseBody", responseBody)

                if (archive != null) {
                    val key = if (source == "replay") "$url#__replay_$started" else url
                    archive.resources[key] = bytes
                    archive.resourceMeta[key] = JSONObject()
                        .put("url", url)
                        .put("status", status)
                        .put("contentType", contentType)
                        .put("finalUrl", finalUrl)
                        .put("responseHeaders", responseHeaders)
                        .put("copyMode", copyMode)
                    archive.addRecord(record)
                } else {
                    NetworkDebugStore.add(record)
                }
            } catch (error: Exception) {
                record.put("duration", System.currentTimeMillis() - started).put("error", error.toString())
                if (archive != null) archive.addRecord(record) else NetworkDebugStore.add(record)
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun headersOf(event: JSONObject): Map<String, String> {
        val source = event.optJSONObject("requestHeaders") ?: event.optJSONObject("headers") ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = source.optString(key, "")
        }
        return out
    }

    private fun isTextual(contentType: String, bytes: ByteArray): Boolean {
        val mime = contentType.lowercase(Locale.US)
        if (mime.contains("json") || mime.startsWith("text/") || mime.contains("javascript") || mime.contains("ecmascript") || mime.contains("css") || mime.contains("html") || mime.contains("xml") || mime.contains("x-www-form-urlencoded") || mime.contains("graphql")) return true
        if (mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/") || mime.contains("octet-stream") || mime.contains("zip") || mime.contains("font")) return false
        if (bytes.isEmpty()) return true
        val sample = bytes.take(1024)
        if (sample.any { it.toInt() == 0 }) return false
        val printable = sample.count { val n = it.toInt() and 255; n == 9 || n == 10 || n == 13 || n in 32..126 || n >= 160 }
        return printable.toDouble() / sample.size >= 0.85
    }

    private fun decodeText(bytes: ByteArray, contentType: String): String {
        val charsetName = Regex("charset\\s*=\\s*([^; ]+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.getOrNull(1)?.trim('"', '\'')
        val charset = try {
            if (charsetName.isNullOrBlank()) Charsets.UTF_8 else Charset.forName(charsetName)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
        if (bytes.size <= MAX_TEXT_BODY) return bytes.toString(charset)
        val prefix = bytes.copyOf(MAX_TEXT_BODY).toString(charset)
        return prefix + "\n\n[truncated in trace: ${bytes.size - MAX_TEXT_BODY} bytes remain; full bytes are kept in ZIP]"
    }

    private fun activeBrowser(activity: Activity): WebResearchV10Activity? {
        val app = activity.application as? WebResearchApp ?: return null
        return try {
            val field = WebResearchApp::class.java.getDeclaredField("browserRef")
            field.isAccessible = true
            val ref = field.get(app) as? WeakReference<*>
            ref?.get() as? WebResearchV10Activity
        } catch (_: Exception) {
            null
        }
    }

    private fun archiveOf(browser: WebResearchV10Activity): ResearchArchive? = try {
        val field = WebResearchV10Activity::class.java.getDeclaredField("archive")
        field.isAccessible = true
        field.get(browser) as? ResearchArchive
    } catch (_: Exception) {
        null
    }

    private fun currentUrl(browser: WebResearchV10Activity): String = try {
        val field = WebResearchV10Activity::class.java.getDeclaredField("web")
        field.isAccessible = true
        (field.get(browser) as? WebView)?.url.orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun browserUserAgent(browser: WebResearchV10Activity?): String = if (browser == null) "" else try {
        val field = WebResearchV10Activity::class.java.getDeclaredField("userAgent")
        field.isAccessible = true
        field.get(browser)?.toString().orEmpty()
    } catch (_: Exception) {
        ""
    }
}
