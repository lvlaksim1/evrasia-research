package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

internal object PostmanRequestExporter {
    private const val SCHEMA = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"

    fun build(event: JSONObject, method: String, cookies: String): String {
        val url = event.optString("url", "")
        val request = buildRequest(event, method, cookies)
        val item = JSONObject()
            .put("name", buildItemName(method, url))
            .put("request", request)

        buildResponse(event, request)?.let { response ->
            item.put("response", JSONArray().put(response))
        }

        val collection = JSONObject()
            .put(
                "info",
                JSONObject()
                    .put("_postman_id", UUID.randomUUID().toString())
                    .put("name", "web research · ${buildItemName(method, url)}")
                    .put("schema", SCHEMA)
            )
            .put("item", JSONArray().put(item))

        return collection.toString(2)
    }

    private fun buildRequest(event: JSONObject, method: String, cookies: String): JSONObject {
        val url = event.optString("url", "")
        val headers = requestHeaders(event)
        if (cookies.isNotBlank() && headers.none { it.first.equals("Cookie", true) }) {
            headers.add("Cookie" to cookies)
        }

        val request = JSONObject()
            .put("method", method.ifBlank { "GET" })
            .put("header", headersJson(headers))
            .put("url", url)

        val body = event.optString("requestBody", "")
        if (body.isNotBlank()) {
            val mime = requestMime(event, headers)
            request.put(
                "body",
                JSONObject()
                    .put("mode", "raw")
                    .put("raw", body)
                    .put(
                        "options",
                        JSONObject().put(
                            "raw",
                            JSONObject().put("language", postmanLanguage(mime, body))
                        )
                    )
            )
        }

        val description = buildString {
            append("Captured by web research")
            event.optString("source", "").takeIf { it.isNotBlank() }?.let { append("\nSource: ").append(it) }
            if (event.has("time")) append("\nCaptured at: ").append(event.optLong("time"))
        }
        request.put("description", description)
        return request
    }

    private fun buildResponse(event: JSONObject, originalRequest: JSONObject): JSONObject? {
        val status = event.optInt("status", 0)
        val responseHeaders = responseHeaders(event)
        val body = NetworkEventClassifier.responseBodyText(event)
        val hasBody = body.isNotBlank() && body !in setOf("[binary]", "[non-text response]", "[unavailable]")
        if (status <= 0 && responseHeaders.isEmpty() && !hasBody) return null

        val mime = responseHeaders.firstOrNull { it.first.equals("Content-Type", true) }?.second.orEmpty()
        val response = JSONObject()
            .put("name", event.optString("statusText", "").ifBlank { if (status > 0) "HTTP $status" else "Captured response" })
            .put("originalRequest", JSONObject(originalRequest.toString()))
            .put("status", event.optString("statusText", ""))
            .put("code", status)
            .put("header", headersJson(responseHeaders))
            .put("cookie", JSONArray())

        if (hasBody) {
            response.put("body", body)
            response.put("_postman_previewlanguage", postmanLanguage(mime, body))
        }
        return response
    }

    private fun requestHeaders(event: JSONObject): MutableList<Pair<String, String>> {
        val source = event.optJSONObject("requestHeaders") ?: event.optJSONObject("headers")
        return objectHeaders(source).toMutableList()
    }

    private fun responseHeaders(event: JSONObject): List<Pair<String, String>> {
        val responseObject = event.optJSONObject("responseHeaders")
        if (responseObject != null) return objectHeaders(responseObject)
        val raw = event.optString("responseHeadersRaw", "")
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val split = line.indexOf(':')
            if (split <= 0) null
            else line.substring(0, split).trim().takeIf { it.isNotBlank() }?.let { name ->
                name to line.substring(split + 1).trim()
            }
        }
    }

    private fun objectHeaders(headers: JSONObject?): List<Pair<String, String>> {
        if (headers == null) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = headers.opt(key)
            if (value != null && value != JSONObject.NULL) out.add(key to value.toString())
        }
        return out.sortedBy { it.first.lowercase(Locale.US) }
    }

    private fun headersJson(headers: List<Pair<String, String>>): JSONArray {
        val out = JSONArray()
        headers.forEach { (key, value) ->
            out.put(JSONObject().put("key", key).put("value", value).put("type", "text"))
        }
        return out
    }

    private fun requestMime(event: JSONObject, headers: List<Pair<String, String>>): String {
        val direct = event.optString("requestMimeType", "")
        if (direct.isNotBlank()) return direct
        return headers.firstOrNull { it.first.equals("Content-Type", true) }?.second.orEmpty()
    }

    private fun postmanLanguage(mime: String, body: String): String {
        val lower = mime.lowercase(Locale.US)
        return when {
            lower.contains("json") || body.trimStart().startsWith("{") || body.trimStart().startsWith("[") -> "json"
            lower.contains("xml") -> "xml"
            lower.contains("html") -> "html"
            lower.contains("javascript") -> "javascript"
            else -> "text"
        }
    }

    private fun buildItemName(method: String, url: String): String {
        val compactUrl = if (url.length <= 120) url else url.take(117) + "..."
        return "${method.ifBlank { "GET" }} $compactUrl".trim()
    }
}
