package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale

/**
 * Produces a share-safe copy of research records while keeping the live debugger
 * copy untouched so AUTH dependency reconstruction can still use exact values.
 */
internal object ResearchSecretRedactor {
    private const val REDACTED = "[redacted]"

    fun copyForArchive(record: JSONObject): JSONObject {
        val out = JSONObject(record.toString())
        redactHeaders(out.optJSONObject("headers"))
        redactHeaders(out.optJSONObject("requestHeaders"))
        redactHeaders(out.optJSONObject("responseHeaders"))
        redactNamedArray(out.optJSONArray("formFields"))
        redactNamedArray(out.optJSONArray("_authFormFields"))
        listOf("requestBody", "responseBody", "data").forEach { key ->
            if (!out.has(key)) return@forEach
            val raw = out.optString(key, "")
            if (raw.isNotBlank()) out.put(key, redactPayload(raw))
        }
        return out
    }

    private fun redactHeaders(headers: JSONObject?) {
        if (headers == null) return
        val keys = mutableListOf<String>()
        val iterator = headers.keys()
        while (iterator.hasNext()) keys.add(iterator.next())
        keys.forEach { key ->
            if (sensitiveHeader(key)) headers.put(key, REDACTED)
        }
    }

    private fun redactNamedArray(array: JSONArray?) {
        if (array == null) return
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name", item.optString("key", ""))
            if (sensitiveField(name) && item.has("value")) item.put("value", REDACTED)
        }
    }

    private fun redactPayload(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                val value: Any = if (trimmed.startsWith("{")) JSONObject(raw) else JSONArray(raw)
                redactJson(value)
                return when (value) {
                    is JSONObject -> value.toString()
                    is JSONArray -> value.toString()
                    else -> raw
                }
            } catch (_: Exception) {}
        }
        if (raw.contains('=') && raw.contains('&')) return redactUrlEncoded(raw)
        if (raw.contains('=') && !raw.contains('\n')) return redactUrlEncoded(raw)
        return raw
    }

    private fun redactJson(value: Any?) {
        when (value) {
            is JSONObject -> {
                val pairName = value.optString("name", value.optString("key", ""))
                if (pairName.isNotBlank() && sensitiveField(pairName) && value.has("value")) value.put("value", REDACTED)

                val keys = mutableListOf<String>()
                val iterator = value.keys()
                while (iterator.hasNext()) keys.add(iterator.next())
                keys.forEach { key ->
                    if (key == "value" && pairName.isNotBlank()) return@forEach
                    val child = value.opt(key)
                    if (sensitiveField(key)) value.put(key, REDACTED)
                    else redactJson(child)
                }
            }
            is JSONArray -> for (index in 0 until value.length()) redactJson(value.opt(index))
        }
    }

    private fun redactUrlEncoded(raw: String): String {
        return raw.split('&').joinToString("&") { part ->
            val split = part.indexOf('=')
            if (split < 0) return@joinToString part
            val encodedName = part.substring(0, split)
            val name = try { URLDecoder.decode(encodedName, "UTF-8") } catch (_: Exception) { encodedName }
            if (sensitiveField(name)) "$encodedName=$REDACTED" else part
        }
    }

    private fun sensitiveHeader(name: String): Boolean {
        val value = normalize(name)
        return value in setOf(
            "authorization",
            "proxy_authorization",
            "cookie",
            "set_cookie",
            "x_api_key",
            "api_key",
            "apikey",
            "x_auth_token",
            "x_access_token"
        ) || value.endsWith("_api_key") || value.endsWith("_auth_token") || value.endsWith("_access_token")
    }

    private fun sensitiveField(name: String): Boolean {
        val value = normalize(name)
        if (value.isBlank()) return false
        if (value in setOf("password", "pass", "passwd", "pwd", "passcode", "otp", "totp", "pin")) return true
        if (value.endsWith("_password") || value.endsWith("_passwd") || value.endsWith("_passcode")) return true
        if (value.endsWith("_otp") || value.endsWith("_totp") || value in setOf("verification_code", "sms_code", "mfa_code", "2fa_code")) return true
        return value in setOf(
            "authorization",
            "client_secret",
            "access_token",
            "refresh_token",
            "id_token",
            "auth_token",
            "session_token",
            "remember_token",
            "api_key",
            "apikey"
        ) || value.endsWith("_client_secret") || value.endsWith("_access_token") || value.endsWith("_refresh_token") || value.endsWith("_id_token") || value.endsWith("_auth_token") || value.endsWith("_api_key")
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
}
