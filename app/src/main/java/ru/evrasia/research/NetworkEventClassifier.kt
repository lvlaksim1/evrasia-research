package ru.evrasia.research

import org.json.JSONObject
import java.net.URL
import java.util.LinkedHashSet
import java.util.Locale

internal object NetworkEventClassifier {
    fun eventSources(event: JSONObject): LinkedHashSet<String> {
        val out = linkedSetOf<String>()
        event.optString("source", "").takeIf { it.isNotBlank() }?.let(out::add)
        val captured = event.optJSONArray("capturedSources")
        if (captured != null) {
            for (index in 0 until captured.length()) {
                captured.optString(index).takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        event.optString("_displaySources", "")
            .split('+')
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach(out::add)
        return out
    }

    fun responseKind(event: JSONObject): String {
        if (isActionEvent(event)) return "ACTION"
        if (isRealtimeSession(event)) return "OTHER"
        if (isEndpointGroup(event)) return event.optString("_groupKind", "OTHER")
        if (event.optString("source", "") in setOf("js-file", "script-archive", "source-map")) return "JS"

        val headers = event.optJSONObject("responseHeaders")
        val mime = event.optString("mimeType", headerValue(headers, "Content-Type"))
            .substringBefore(';')
            .trim()
            .lowercase(Locale.US)
        when {
            mime.contains("json") -> return "JSON"
            mime.contains("html") -> return "HTML"
            mime.contains("javascript") || mime.contains("ecmascript") -> return "JS"
            mime.contains("css") -> return "CSS"
            mime.startsWith("image/") -> return "IMG"
            mime.contains("pdf") -> return "PDF"
            mime.startsWith("text/") || mime.contains("xml") || mime.contains("x-www-form-urlencoded") -> return "TEXT"
            mime.contains("octet-stream") || mime.startsWith("font/") || mime.startsWith("audio/") || mime.startsWith("video/") || mime.contains("zip") || mime.contains("gzip") -> return "BIN"
        }

        val path = event.optString("url", "")
            .substringBefore('?')
            .substringBefore('#')
            .lowercase(Locale.US)
        when {
            path.endsWith(".json") || path.endsWith(".map") -> return "JSON"
            path.endsWith(".html") || path.endsWith(".htm") -> return "HTML"
            path.endsWith(".js") || path.endsWith(".mjs") -> return "JS"
            path.endsWith(".css") -> return "CSS"
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") || path.endsWith(".ico") -> return "IMG"
            path.endsWith(".pdf") -> return "PDF"
            path.endsWith(".txt") || path.endsWith(".xml") || path.endsWith(".csv") -> return "TEXT"
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".otf") || path.endsWith(".zip") || path.endsWith(".gz") || path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mp3") -> return "BIN"
        }

        val body = responseBodyText(event).trim()
        if (body == "[binary]" || body == "[non-text response]") return "BIN"
        if (body.startsWith("{") || body.startsWith("[")) return "JSON"
        if (body.startsWith("<!doctype", true) || body.startsWith("<html", true) || body.startsWith("<body", true)) return "HTML"
        if (body.isNotBlank()) return "TEXT"
        return if (mime.isNotBlank()) "BIN" else "OTHER"
    }

    fun eventLocation(event: JSONObject): String = event.optString("url", "")
        .ifBlank { event.optString("page", event.optString("newURL", "")) }

    fun methodOf(event: JSONObject): String {
        if (isEndpointGroup(event)) return event.optString("_groupMethod", "OTHER")
        if (isRealtimeSession(event)) return event.optString("_realtimeProtocol", "OTHER")
        val source = event.optString("source", "")
        if (source.startsWith("websocket")) return "WS"
        if (source.startsWith("sse")) return "SSE"
        if (isActionEvent(event)) return "ACTION"
        return event.optString("method", "").ifBlank { "OTHER" }.uppercase(Locale.US)
    }

    fun responseBodyText(event: JSONObject): String = event.optString("responseBody", event.optString("data", ""))

    fun hasRequestBody(event: JSONObject): Boolean = event.optString("requestBody", "").isNotBlank()

    fun isRealtimeSession(event: JSONObject): Boolean = event.optBoolean("_realtimeSession", false)

    fun isEndpointGroup(event: JSONObject): Boolean = event.optBoolean("_endpointGroup", false)

    fun isApiRelevant(event: JSONObject): Boolean {
        if (isActionEvent(event) || isRealtimeSession(event)) return true
        val sources = eventSources(event)
        if (event.optString("source", "") == "replay") return true
        if (sources.any { it in setOf("fetch", "fetch-meta", "xhr", "xhr-meta", "websocket-open", "websocket-send", "websocket-receive", "sse-open", "sse-message", "beacon") }) return true
        val method = methodOf(event)
        if (method in setOf("POST", "PUT", "PATCH", "DELETE", "WS", "SSE")) return true
        if (hasRequestBody(event) || responseKind(event) == "JSON") return true
        val url = event.optString("url", "").lowercase(Locale.US)
        return url.contains("/api/") || url.contains("/graphql") || url.contains("/ajax") || url.contains("/rest/")
    }

    fun isPlainRequestEvent(event: JSONObject): Boolean = event.optString("source") in setOf(
        "fetch", "fetch-meta", "xhr", "xhr-meta", "webview", "resource-copy", "resource-timing", "navigation", "navigation-timing", "new-window",
        "websocket-open", "websocket-send", "websocket-receive", "sse-open", "sse-message", "beacon", "js-file", "script-archive", "replay"
    )

    fun isRequestEvent(event: JSONObject): Boolean = isPlainRequestEvent(event) || isRealtimeSession(event) || isEndpointGroup(event)

    fun isActionEvent(event: JSONObject): Boolean = event.optString("source", "") == "user-action"

    fun isJsEvent(event: JSONObject): Boolean {
        val url = event.optString("url", "").substringBefore('?').lowercase(Locale.US)
        return event.optString("source") in setOf("js-file", "script-archive", "source-map") ||
            url.endsWith(".js") || url.endsWith(".mjs") || event.optString("mimeType", "").contains("javascript", true)
    }

    fun hostOf(url: String): String? = try {
        if (url.startsWith("http://") || url.startsWith("https://")) URL(url).host else null
    } catch (_: Exception) {
        null
    }

    private fun headerValue(headers: JSONObject?, name: String): String {
        if (headers == null) return ""
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.equals(name, true)) return headers.optString(key, "")
        }
        return ""
    }
}
