package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

internal object UniversalAuthAnalyzerV2 {
    private data class Source(
        val itemIndex: Int,
        val key: String,
        val value: String,
        val kind: String
    )

    private data class Candidate(
        val key: String,
        val value: String
    )

    fun analyze(
        events: List<JSONObject>,
        before: AuthFlowAnalyzer.BrowserState,
        after: AuthFlowAnalyzer.BrowserState,
        initialAuthSource: String
    ): AuthFlowAnalyzer.Result {
        val base = UniversalAuthAnalyzer.analyze(events, before, after)
        val collection = JSONObject(base.collectionJson)
        val items = collection.optJSONArray("item") ?: JSONArray()
        val variables = collection.optJSONArray("variable") ?: JSONArray().also { collection.put("variable", it) }
        val baseUrl = collectionVariable(variables, "base_url")
        val usedNames = mutableSetOf<String>()
        for (i in 0 until variables.length()) {
            variables.optJSONObject(i)?.optString("key", "")?.takeIf { it.isNotBlank() }?.let(usedNames::add)
        }

        val sources = mutableListOf<Source>()
        val seedIndex = findItemIndex(items, "GET", before.url, baseUrl).let { if (it >= 0) it else 0 }
        collectTextSources(initialAuthSource, seedIndex, sources)
        collectStorageSources(before, seedIndex, sources)
        collectEventSources(events, items, baseUrl, sources)

        var dynamicCount = 0
        val replacedValues = mutableSetOf<String>()
        for (consumerIndex in 0 until items.length()) {
            val item = items.optJSONObject(consumerIndex) ?: continue
            val request = item.optJSONObject("request") ?: continue
            val candidates = requestCandidates(request, baseUrl)
            for (candidate in candidates) {
                if (!isDynamicCandidate(candidate.key, candidate.value)) continue
                if (!replacedValues.add(candidate.value)) continue
                val source = sources.asSequence()
                    .filter { it.itemIndex >= 0 && it.itemIndex < consumerIndex && it.value == candidate.value }
                    .maxByOrNull { it.itemIndex }
                    ?: continue
                val variableName = uniqueVariable(variableName(candidate.key, source.key), usedNames)
                usedNames.add(variableName)
                replaceStrings(request, candidate.value, "{{$variableName}}")
                val responses = item.optJSONArray("response")
                if (responses != null) {
                    for (r in 0 until responses.length()) {
                        responses.optJSONObject(r)?.optJSONObject("originalRequest")?.let {
                            replaceStrings(it, candidate.value, "{{$variableName}}")
                        }
                    }
                }
                ensureVariable(variables, variableName, source.key)
                items.optJSONObject(source.itemIndex)?.let { producerItem ->
                    appendTestLines(producerItem, extractorLines(source, variableName))
                }
                dynamicCount++
            }
        }

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val name = item.optString("name", "")
            if (name.contains(" Login · ") || name.startsWith("Login ·") || name.contains(" Login ")) {
                appendTestLines(
                    item,
                    listOf(
                        "pm.test(\"AUTH login: HTTP success\", function () {",
                        "  pm.expect(pm.response.code).to.be.within(200, 399);",
                        "});"
                    )
                )
            }
            if (name.contains("Verify authenticated session", true)) {
                appendTestLines(item, verificationLines())
            }
        }

        val info = collection.optJSONObject("info")
        if (info != null) {
            val description = info.optString("description", "")
            val extra = if (dynamicCount > 0) {
                "\n- Added $dynamicCount dynamic page/response → request extractor(s)."
            } else {
                "\n- No additional dynamic HTML/inline-script dependencies were proven."
            }
            info.put("description", description + extra)
        }

        val notes = base.notes.toMutableList()
        if (dynamicCount > 0) notes.add("Added $dynamicCount dynamic HTML/inline-script response → request dependencies")
        notes.add("Postman verification tests were added for login/session-check steps")

        return AuthFlowAnalyzer.Result(
            collectionJson = collection.toString(2),
            confidence = base.confidence,
            requestCount = items.length(),
            loginUrl = base.loginUrl,
            notes = notes
        )
    }

    private fun collectEventSources(
        events: List<JSONObject>,
        items: JSONArray,
        baseUrl: String,
        out: MutableList<Source>
    ) {
        events.forEach { event ->
            val method = NetworkEventClassifier.methodOf(event).ifBlank { event.optString("method", "GET") }
            val url = event.optString("url", "")
            val itemIndex = findItemIndex(items, method, url, baseUrl).let { direct ->
                if (direct >= 0) direct else if (event.optString("source", "") == "auth-page-source") findItemIndex(items, "GET", url, baseUrl) else -1
            }
            if (itemIndex < 0) return@forEach

            if (event.optString("source", "") == "auth-page-source") {
                collectTextSources(event.optString("content", ""), itemIndex, out)
            }

            val body = NetworkEventClassifier.responseBodyText(event)
            if (body.isBlank() || body in setOf("[binary]", "[non-text response]", "[unavailable]")) return@forEach
            collectTextSources(body, itemIndex, out)
        }
    }

    private fun collectStorageSources(
        before: AuthFlowAnalyzer.BrowserState,
        itemIndex: Int,
        out: MutableList<Source>
    ) {
        listOf(before.localStorage, before.sessionStorage).forEach { store ->
            val keys = store.keys()
            while (keys.hasNext()) {
                val storageKey = keys.next()
                val raw = store.optString(storageKey, "")
                if (raw.isBlank()) continue
                collectTextSources(raw, itemIndex, out)
                if (interestingKey(storageKey) && reusable(raw)) {
                    addSource(out, Source(itemIndex, storageKey, raw, "storage"))
                }
            }
        }
    }

    private fun collectTextSources(text: String, itemIndex: Int, out: MutableList<Source>) {
        if (text.isBlank()) return
        val bounded = if (text.length > 1_500_000) text.take(1_500_000) else text

        val jsonStyle = Regex("[\\\"']([A-Za-z0-9_.:-]{2,80})[\\\"']\\s*:\\s*[\\\"']([^\\\"']{4,4096})[\\\"']")
        jsonStyle.findAll(bounded).take(400).forEach { match ->
            addSource(out, Source(itemIndex, match.groupValues[1], htmlDecode(match.groupValues[2]), "text-key"))
        }

        val assignment = Regex("\\b([A-Za-z_\\x24][A-Za-z0-9_\\x24.-]{1,79})\\s*=\\s*[\\\"']([^\\\"']{4,4096})[\\\"']")
        assignment.findAll(bounded).take(250).forEach { match ->
            addSource(out, Source(itemIndex, match.groupValues[1], htmlDecode(match.groupValues[2]), "text-key"))
        }

        Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(bounded).take(250).forEach { match ->
            val attrs = htmlAttributes(match.value)
            val key = attrs["name"].orEmpty().ifBlank { attrs["id"].orEmpty() }
            val value = attrs["value"].orEmpty()
            if (key.isNotBlank() && value.isNotBlank()) addSource(out, Source(itemIndex, key, htmlDecode(value), "input"))
        }

        Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(bounded).take(250).forEach { match ->
            val attrs = htmlAttributes(match.value)
            val key = attrs["name"].orEmpty().ifBlank { attrs["property"].orEmpty() }
            val value = attrs["content"].orEmpty()
            if (key.isNotBlank() && value.isNotBlank()) addSource(out, Source(itemIndex, key, htmlDecode(value), "meta"))
        }
    }

    private fun addSource(out: MutableList<Source>, source: Source) {
        if (!reusable(source.value)) return
        if (!interestingKey(source.key) && !tokenLike(source.value)) return
        if (out.none { it.itemIndex == source.itemIndex && it.key == source.key && it.value == source.value }) out.add(source)
    }

    private fun requestCandidates(request: JSONObject, baseUrl: String): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val headers = request.optJSONArray("header")
        if (headers != null) {
            for (i in 0 until headers.length()) {
                val header = headers.optJSONObject(i) ?: continue
                val key = header.optString("key", "")
                val value = header.optString("value", "")
                if (value.contains("{{")) continue
                if (key.equals("Authorization", true) && value.startsWith("Bearer ", true)) {
                    out.add(Candidate(key, value.substringAfter(' ').trim()))
                } else if (interestingKey(key) || tokenLike(value)) {
                    out.add(Candidate(key, value))
                }
            }
        }

        val rawUrl = request.opt("url")?.toString().orEmpty().replace("{{base_url}}", baseUrl)
        try {
            val url = URL(rawUrl)
            parseUrlEncoded(url.query.orEmpty()).forEach { (key, value) ->
                if (!value.contains("{{") && (interestingKey(key) || tokenLike(value))) out.add(Candidate(key, value))
            }
        } catch (_: Exception) {}

        val body = request.optJSONObject("body")
        if (body != null) {
            listOf("formdata", "urlencoded").forEach { arrayName ->
                val array = body.optJSONArray(arrayName) ?: return@forEach
                for (i in 0 until array.length()) {
                    val field = array.optJSONObject(i) ?: continue
                    val key = field.optString("key", "")
                    val value = field.optString("value", "")
                    if (!value.contains("{{") && (interestingKey(key) || tokenLike(value))) out.add(Candidate(key, value))
                }
            }
            val raw = body.optString("raw", "").trim()
            if (raw.isNotBlank() && !raw.contains("{{")) collectRawCandidates(raw, out)
        }
        return out.distinctBy { it.key.lowercase(Locale.US) + "\u0000" + it.value }
    }

    private fun collectRawCandidates(raw: String, out: MutableList<Candidate>) {
        if (raw.startsWith("{") || raw.startsWith("[")) {
            try {
                val parsed: Any = if (raw.startsWith("{")) JSONObject(raw) else JSONArray(raw)
                collectJsonCandidates(parsed, "", out)
                return
            } catch (_: Exception) {}
        }
        parseUrlEncoded(raw).forEach { (key, value) ->
            if (interestingKey(key) || tokenLike(value)) out.add(Candidate(key, value))
        }
    }

    private fun collectJsonCandidates(value: Any?, key: String, out: MutableList<Candidate>) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val next = keys.next()
                    collectJsonCandidates(value.opt(next), next, out)
                }
            }
            is JSONArray -> for (i in 0 until minOf(value.length(), 100)) collectJsonCandidates(value.opt(i), key, out)
            null, JSONObject.NULL -> Unit
            else -> {
                val text = value.toString()
                if (interestingKey(key) || tokenLike(text)) out.add(Candidate(key, text))
            }
        }
    }

    private fun isDynamicCandidate(key: String, value: String): Boolean {
        if (value.isBlank() || value.contains("{{")) return false
        val lower = normalize(key)
        if (lower in setOf("login", "username", "email", "phone", "password", "user_login", "user_password")) return false
        return interestingKey(key) || tokenLike(value)
    }

    private fun extractorLines(source: Source, variable: String): List<String> {
        val escaped = jsRegexEscape(source.key)
        val variableQuoted = JSONObject.quote(variable)
        val keyQuoted = JSONObject.quote(source.key)
        return listOf(
            "try {",
            "  const text = pm.response.text();",
            "  const targetKey = $keyQuoted;",
            "  let extracted;",
            "  try {",
            "    const root = JSON.parse(text);",
            "    const walk = function (value) {",
            "      if (value == null || extracted !== undefined) return;",
            "      if (Array.isArray(value)) { for (const item of value) walk(item); return; }",
            "      if (typeof value !== 'object') return;",
            "      for (const key of Object.keys(value)) {",
            "        if (key.toLowerCase() === targetKey.toLowerCase() && value[key] != null && typeof value[key] !== 'object') { extracted = String(value[key]); return; }",
            "        walk(value[key]);",
            "      }",
            "    };",
            "    walk(root);",
            "  } catch (e) {}",
            "  if (!extracted) {",
            "    const patterns = [",
            "      /[\\\"']$escaped[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)/i,",
            "      /\\b$escaped\\s*=\\s*[\\\"']([^\\\"']+)/i,",
            "      /name\\s*=\\s*[\\\"']$escaped[\\\"'][^>]*value\\s*=\\s*[\\\"']([^\\\"']+)/i,",
            "      /value\\s*=\\s*[\\\"']([^\\\"']+)[\\\"'][^>]*name\\s*=\\s*[\\\"']$escaped[\\\"']/i,",
            "      /(?:name|property)\\s*=\\s*[\\\"']$escaped[\\\"'][^>]*content\\s*=\\s*[\\\"']([^\\\"']+)/i,",
            "      /content\\s*=\\s*[\\\"']([^\\\"']+)[\\\"'][^>]*(?:name|property)\\s*=\\s*[\\\"']$escaped[\\\"']/i",
            "    ];",
            "    for (const pattern of patterns) { const match = text.match(pattern); if (match && match[1]) { extracted = match[1]; break; } }",
            "  }",
            "  if (extracted) pm.collectionVariables.set($variableQuoted, String(extracted));",
            "} catch (e) {}"
        )
    }

    private fun verificationLines(): List<String> = listOf(
        "pm.test(\"AUTH verify: HTTP success\", function () {",
        "  pm.expect(pm.response.code).to.be.within(200, 399);",
        "});",
        "pm.test(\"AUTH verify: response is not an authentication form\", function () {",
        "  const text = pm.response.text();",
        "  const passwordField = /<input\\b[^>]*type\\s*=\\s*[\\\"']?password\\b/i.test(text);",
        "  const authForm = /<form\\b[^>]*(?:action|id|class|name)\\s*=\\s*[\\\"'][^\\\"']*(?:login|signin|sign-in|auth)/i.test(text);",
        "  const loginField = /name\\s*=\\s*[\\\"'](?:username|login|email|phone|user_login|user_password)[\\\"']/i.test(text);",
        "  const authJson = /[\\\"'](?:error|status|code)[\\\"']\\s*:\\s*[\\\"'](?:unauthorized|forbidden|login_required|invalid_token|not_authenticated)[\\\"']/i.test(text);",
        "  pm.expect(authJson || (passwordField && (authForm || loginField)), \"Response still looks unauthenticated\").to.eql(false);",
        "});"
    )

    private fun appendTestLines(item: JSONObject, lines: List<String>) {
        if (lines.isEmpty()) return
        val events = item.optJSONArray("event") ?: JSONArray().also { item.put("event", it) }
        var foundEvent: JSONObject? = null
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optString("listen", "") == "test") {
                foundEvent = event
                break
            }
        }
        if (foundEvent == null) {
            foundEvent = JSONObject()
                .put("listen", "test")
                .put("script", JSONObject().put("type", "text/javascript").put("exec", JSONArray()))
            events.put(foundEvent)
        }
        val targetEvent = foundEvent ?: return
        val script = targetEvent.optJSONObject("script") ?: JSONObject().also { targetEvent.put("script", it) }
        script.put("type", "text/javascript")
        val exec = script.optJSONArray("exec") ?: JSONArray().also { script.put("exec", it) }
        lines.forEach { exec.put(it) }
    }

    private fun replaceStrings(value: Any?, old: String, replacement: String) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList()
                keys.forEach { key ->
                    val child = value.opt(key)
                    when (child) {
                        is String -> if (child.contains(old)) value.put(key, child.replace(old, replacement))
                        is JSONObject, is JSONArray -> replaceStrings(child, old, replacement)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val child = value.opt(i)
                    when (child) {
                        is String -> if (child.contains(old)) value.put(i, child.replace(old, replacement))
                        is JSONObject, is JSONArray -> replaceStrings(child, old, replacement)
                    }
                }
            }
        }
    }

    private fun findItemIndex(items: JSONArray, method: String, rawUrl: String, baseUrl: String): Int {
        val wantedMethod = method.uppercase(Locale.US)
        val wantedUrl = normalizeUrl(rawUrl)
        for (i in 0 until items.length()) {
            val request = items.optJSONObject(i)?.optJSONObject("request") ?: continue
            if (request.optString("method", "GET").uppercase(Locale.US) != wantedMethod) continue
            val itemUrl = request.opt("url")?.toString().orEmpty().replace("{{base_url}}", baseUrl)
            if (normalizeUrl(itemUrl) == wantedUrl) return i
        }
        return -1
    }

    private fun ensureVariable(variables: JSONArray, key: String, sourceKey: String) {
        for (i in 0 until variables.length()) {
            if (variables.optJSONObject(i)?.optString("key", "") == key) return
        }
        variables.put(
            JSONObject()
                .put("key", key)
                .put("value", "")
                .put("type", "string")
                .put("description", "Dynamically extracted AUTH value from source field '$sourceKey'")
        )
    }

    private fun collectionVariable(variables: JSONArray, key: String): String {
        for (i in 0 until variables.length()) {
            val variable = variables.optJSONObject(i) ?: continue
            if (variable.optString("key", "") == key) return variable.optString("value", "")
        }
        return ""
    }

    private fun variableName(requestKey: String, sourceKey: String): String {
        val request = normalize(requestKey)
        val source = normalize(sourceKey)
        return when {
            request.contains("csrf") || request.contains("xsrf") -> "csrf_token"
            request.contains("refresh") && request.contains("token") -> "refresh_token"
            request.contains("authorization") || request.contains("access_token") || request == "bearer" -> "access_token"
            request.contains("api_key") || request.contains("apikey") -> "api_key"
            request.contains("token") -> "auth_token"
            source.contains("csrf") || source.contains("xsrf") -> "csrf_token"
            source.contains("refresh") && source.contains("token") -> "refresh_token"
            source.contains("access") && source.contains("token") -> "access_token"
            source.contains("token") -> "auth_token"
            source.contains("nonce") -> "nonce"
            source == "state" || source.endsWith("_state") -> "oauth_state"
            source.isNotBlank() -> source.take(48)
            else -> "auth_value"
        }
    }

    private fun uniqueVariable(base: String, used: Set<String>): String {
        if (base !in used) return base
        var index = 2
        while ("${base}_$index" in used) index++
        return "${base}_$index"
    }

    private fun interestingKey(key: String): Boolean {
        val value = normalize(key)
        return value.contains("token") || value.contains("csrf") || value.contains("xsrf") || value.contains("session") || value.contains("sessid") || value.contains("nonce") || value in setOf("state", "code", "authorization", "authenticity_token", "requestverificationtoken")
    }

    private fun tokenLike(value: String): Boolean {
        if (value.length !in 16..4096 || value.contains(' ') || value.contains('\n')) return false
        if (value.count { it == '.' } == 2 && value.all { it.isLetterOrDigit() || it in "-_." }) return true
        return value.length >= 24 && value.toSet().size >= 10 && value.all { it.isLetterOrDigit() || it in "-_=.~" }
    }

    private fun reusable(value: String): Boolean {
        if (value.length !in 4..4096) return false
        return value.lowercase(Locale.US) !in setOf("true", "false", "null", "undefined", "success", "error")
    }

    private fun htmlAttributes(tag: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*([\\\"'])(.*?)\\2", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(tag).forEach {
            out[it.groupValues[1].lowercase(Locale.US)] = it.groupValues[3]
        }
        return out
    }

    private fun parseUrlEncoded(raw: String): List<Pair<String, String>> {
        if (raw.isBlank()) return emptyList()
        return raw.split('&').filter { it.isNotBlank() }.map { part ->
            val split = part.indexOf('=')
            decode(if (split >= 0) part.substring(0, split) else part) to decode(if (split >= 0) part.substring(split + 1) else "")
        }
    }

    private fun decode(value: String): String = try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { value }

    private fun normalize(value: String): String = value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun normalizeUrl(raw: String): String = try {
        val url = URL(raw)
        val port = if (url.port > 0 && url.port != url.defaultPort) ":${url.port}" else ""
        "${url.protocol.lowercase(Locale.US)}://${url.host.lowercase(Locale.US)}$port${url.path.ifBlank { "/" }}" + if (url.query.isNullOrBlank()) "" else "?${url.query}"
    } catch (_: Exception) {
        raw.substringBefore('#')
    }

    private fun htmlDecode(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun jsRegexEscape(value: String): String {
        val specials = charArrayOf('\\', '.', '^', '$', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}')
        return buildString {
            value.forEach { char ->
                if (char in specials) append('\\')
                append(char)
            }
        }
    }
}
