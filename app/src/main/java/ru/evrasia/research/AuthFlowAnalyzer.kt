package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

internal object AuthFlowAnalyzer {
    private const val SCHEMA = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"

    data class BrowserState(
        val time: Long,
        val url: String,
        val nativeCookies: String,
        val documentCookies: String,
        val localStorage: JSONObject,
        val sessionStorage: JSONObject
    )

    data class Result(
        val collectionJson: String,
        val confidence: String,
        val requestCount: Int,
        val loginUrl: String,
        val notes: List<String>
    )

    private data class RequestNode(
        val event: JSONObject,
        val source: String,
        val method: String,
        val url: String,
        val time: Long
    )

    private data class Producer(
        val requestIndex: Int,
        val key: String,
        val value: String,
        val jsonPath: List<String>?,
        val dynamic: Boolean
    )

    private data class Binding(
        val producerIndex: Int,
        val consumerIndex: Int,
        val value: String,
        val variable: String,
        val jsonPath: List<String>?,
        val dynamic: Boolean
    )

    private data class VariableDef(
        val key: String,
        var value: String,
        val description: String
    )

    fun analyze(events: List<JSONObject>, before: BrowserState, after: BrowserState): Result {
        val requests = prepareRequests(events)
        if (requests.isEmpty()) throw IllegalStateException("За время AUTH-анализа HTTP-запросы не обнаружены")

        val scores = requests.map { authScore(it.event) }
        var loginIndex = scores.indices.maxByOrNull { scores[it] } ?: 0
        if (scores[loginIndex] <= 0) {
            loginIndex = requests.indices.firstOrNull { requests[it].method in setOf("POST", "PUT", "PATCH") } ?: loginIndex
        }
        val login = requests[loginIndex]
        val loginHost = hostOf(login.url)
        val selected = linkedSetOf<Int>()
        selected.add(loginIndex)

        val loginTime = login.time
        requests.indices.forEach { index ->
            val node = requests[index]
            val closeInTime = node.time <= 0L || loginTime <= 0L || node.time in (loginTime - 120_000L)..(loginTime + 120_000L)
            val sameHost = loginHost.isBlank() || hostOf(node.url) == loginHost
            val pathAuth = hasAuthPathKeyword(node.url)
            if (closeInTime && sameHost && (scores[index] >= 4 || pathAuth || hasCredentialFields(node.event))) selected.add(index)
        }

        val producers = collectProducers(requests)
        val bindings = mutableListOf<Binding>()
        val usedVariableNames = linkedSetOf<String>()

        fun addBinding(producer: Producer, consumerIndex: Int) {
            if (bindings.any { it.producerIndex == producer.requestIndex && it.consumerIndex == consumerIndex && it.value == producer.value }) return
            val baseName = canonicalVariableName(producer.key)
            val variable = uniqueVariableName(baseName, usedVariableNames)
            usedVariableNames.add(variable)
            bindings.add(
                Binding(
                    producerIndex = producer.requestIndex,
                    consumerIndex = consumerIndex,
                    value = producer.value,
                    variable = variable,
                    jsonPath = producer.jsonPath,
                    dynamic = producer.dynamic
                )
            )
        }

        var changed = true
        var passes = 0
        while (changed && passes < 4) {
            changed = false
            passes++
            val consumers = selected.toList().sorted()
            consumers.forEach { consumerIndex ->
                val material = requestMaterial(requests[consumerIndex].event)
                producers.asSequence()
                    .filter { it.requestIndex < consumerIndex && isReusableValue(it.value) && materialContains(material, it.value) }
                    .sortedByDescending { it.requestIndex }
                    .take(4)
                    .forEach { producer ->
                        if (selected.add(producer.requestIndex)) changed = true
                        addBinding(producer, consumerIndex)
                    }
            }
        }

        val selectedProducerIndexes = selected.toSet()
        producers.filter { it.requestIndex in selectedProducerIndexes && isReusableValue(it.value) }.forEach { producer ->
            val consumer = requests.indices.firstOrNull { index ->
                index > producer.requestIndex && index <= minOf(requests.lastIndex, loginIndex + 8) && materialContains(requestMaterial(requests[index].event), producer.value)
            }
            if (consumer != null) {
                selected.add(consumer)
                addBinding(producer, consumer)
            }
        }

        val verifyIndex = requests.indices.firstOrNull { index ->
            index > loginIndex && index <= minOf(requests.lastIndex, loginIndex + 8) && isLikelyAuthenticatedRequest(requests[index].event)
        }
        if (verifyIndex != null) selected.add(verifyIndex)

        requests.indices.forEach { index ->
            if (index > loginIndex && index <= minOf(requests.lastIndex, loginIndex + 12) && requests[index].url.lowercase(Locale.US).contains("refresh")) selected.add(index)
        }

        val variableDefs = linkedMapOf<String, VariableDef>()
        val exactReplacements = linkedMapOf<String, String>()
        val bindingByProducer = bindings.groupBy { it.producerIndex }

        bindings.forEach { binding ->
            exactReplacements.putIfAbsent(binding.value, binding.variable)
            val fallback = if (binding.dynamic) "" else binding.value
            variableDefs.putIfAbsent(
                binding.variable,
                VariableDef(
                    key = binding.variable,
                    value = fallback,
                    description = if (binding.dynamic) "Automatically extracted from an earlier auth response" else "Captured fallback value; dynamic extractor was not available"
                )
            )
        }

        selected.sorted().forEach { index ->
            collectCredentialVariables(requests[index].event).forEach { (rawValue, candidate) ->
                if (rawValue.isBlank()) return@forEach
                if (exactReplacements.containsKey(rawValue)) return@forEach
                val variable = if (candidate in usedVariableNames) uniqueVariableName(candidate, usedVariableNames) else candidate
                usedVariableNames.add(variable)
                exactReplacements[rawValue] = variable
                val defaultValue = when (candidate) {
                    "login", "password", "otp" -> ""
                    else -> if (rawValue == "[password]") "" else rawValue
                }
                variableDefs.putIfAbsent(
                    variable,
                    VariableDef(variable, defaultValue, credentialDescription(candidate))
                )
            }
        }

        addAuthHeaderFallbacks(requests, selected, exactReplacements, variableDefs, usedVariableNames)
        addStorageFallbacks(before, after, exactReplacements, variableDefs, usedVariableNames)

        val origin = originOf(login.url).ifBlank { originOf(before.url) }
        if (origin.isNotBlank()) {
            variableDefs["base_url"] = VariableDef("base_url", origin, "Primary origin detected for the authentication flow")
        }

        val itemArray = JSONArray()
        var sequence = 1
        val shouldSeed = shouldAddSeedRequest(before, after, login, selected, requests)
        if (shouldSeed && before.url.startsWith("http")) {
            itemArray.put(
                JSONObject()
                    .put("name", "%02d Prepare browser session".format(Locale.US, sequence++))
                    .put(
                        "request",
                        JSONObject()
                            .put("method", "GET")
                            .put("header", JSONArray())
                            .put("url", portableUrl(before.url, origin, exactReplacements))
                            .put("description", "Opens the page that was active when AUTH analysis started. This can establish cookies and anti-CSRF state before login.")
                    )
            )
        }

        val sortedSelected = selected.sorted()
        sortedSelected.forEach { index ->
            val node = requests[index]
            val role = when {
                index == loginIndex -> "Login"
                index < loginIndex -> "Prepare / auth dependency"
                node.url.lowercase(Locale.US).contains("refresh") -> "Refresh token"
                index == verifyIndex -> "Verify authenticated session"
                else -> "Auth step"
            }
            val requestJson = buildPostmanRequest(node.event, node.method, node.url, origin, exactReplacements)
            val item = JSONObject()
                .put("name", "%02d %s · %s %s".format(Locale.US, sequence++, role, node.method, compactPath(node.url)))
                .put("request", requestJson)

            val testLines = buildTestLines(bindingByProducer[index].orEmpty())
            if (testLines.isNotEmpty()) {
                item.put(
                    "event",
                    JSONArray().put(
                        JSONObject()
                            .put("listen", "test")
                            .put("script", JSONObject().put("type", "text/javascript").put("exec", JSONArray(testLines)))
                    )
                )
            }
            itemArray.put(item)
        }

        val changedCookies = changedCookieNames(before.nativeCookies, after.nativeCookies)
        val changedStorage = changedStorageKeys(before, after)
        val topScore = scores.getOrElse(loginIndex) { 0 }
        val confidence = when {
            topScore >= 12 && (bindings.isNotEmpty() || changedCookies.isNotEmpty() || changedStorage.isNotEmpty()) -> "HIGH"
            topScore >= 7 -> "MEDIUM"
            else -> "LOW"
        }

        val notes = mutableListOf<String>()
        notes.add("Detected login candidate: ${login.method} ${login.url}")
        if (changedCookies.isNotEmpty()) notes.add("Cookies changed: ${changedCookies.joinToString(", ")}")
        if (changedStorage.isNotEmpty()) notes.add("Browser storage changed: ${changedStorage.joinToString(", ")}")
        if (bindings.isNotEmpty()) notes.add("Detected ${bindings.size} response → request value dependencies")
        if (variableDefs.keys.any { it in setOf("login", "password", "otp") }) notes.add("Set login/password/otp collection variables before running when they are empty")
        if (confidence == "LOW") notes.add("Authentication confidence is low; the collection is a best-effort reconstruction")

        val variables = JSONArray()
        variableDefs.values.forEach { def ->
            variables.put(
                JSONObject()
                    .put("key", def.key)
                    .put("value", def.value)
                    .put("type", "string")
                    .put("description", def.description)
            )
        }

        val description = buildString {
            append("Generated by web research AUTH analyzer.\n")
            append("Confidence: ").append(confidence).append(".\n")
            notes.forEach { append("- ").append(it).append('\n') }
            append("\nThe collection intentionally removes captured Cookie headers and relies on Postman's cookie jar when servers issue Set-Cookie responses.")
        }.trim()

        val collection = JSONObject()
            .put(
                "info",
                JSONObject()
                    .put("_postman_id", UUID.randomUUID().toString())
                    .put("name", "web research · AUTH · ${hostOf(login.url).ifBlank { "site" }}")
                    .put("description", description)
                    .put("schema", SCHEMA)
            )
            .put("item", itemArray)
            .put("variable", variables)

        return Result(
            collectionJson = collection.toString(2),
            confidence = confidence,
            requestCount = itemArray.length(),
            loginUrl = login.url,
            notes = notes
        )
    }

    private fun prepareRequests(events: List<JSONObject>): List<RequestNode> {
        val merged = NetworkDisplayMerger.merge(events.map { JSONObject(it.toString()) }).sortedBy { it.optLong("time", 0L) }.toMutableList()
        val formEvents = events.filter { it.optString("source", "") == "auth-form-submit" }.sortedBy { it.optLong("time", 0L) }

        formEvents.forEach { form ->
            val method = form.optString("method", "POST").uppercase(Locale.US)
            val url = form.optString("url", "")
            val time = form.optLong("time", 0L)
            val candidate = merged.withIndex().filter { (_, event) ->
                event.optString("url", "") == url && NetworkEventClassifier.methodOf(event) == method && kotlin.math.abs(event.optLong("time", 0L) - time) <= 5000L
            }.minByOrNull { (_, event) -> kotlin.math.abs(event.optLong("time", 0L) - time) }
            if (candidate != null) {
                val target = candidate.value
                if (target.optString("requestBody", "").isBlank()) target.put("requestBody", form.optString("requestBody", ""))
                if (target.optString("requestMimeType", "").isBlank()) target.put("requestMimeType", form.optString("requestMimeType", "application/x-www-form-urlencoded"))
                form.optJSONArray("formFields")?.let { target.put("_authFormFields", JSONArray(it.toString())) }
                target.put("_authFormCaptured", true)
            } else {
                merged.add(JSONObject(form.toString()))
            }
        }

        return merged.asSequence()
            .filter { event ->
                val source = event.optString("source", "")
                source == "auth-form-submit" || NetworkEventClassifier.isPlainRequestEvent(event)
            }
            .filter { event ->
                val url = event.optString("url", "")
                url.startsWith("http://") || url.startsWith("https://")
            }
            .map { event ->
                RequestNode(
                    event = event,
                    source = event.optString("source", ""),
                    method = NetworkEventClassifier.methodOf(event).ifBlank { event.optString("method", "GET") }.uppercase(Locale.US),
                    url = event.optString("url", ""),
                    time = event.optLong("time", 0L)
                )
            }
            .sortedBy { it.time }
            .toList()
    }

    private fun authScore(event: JSONObject): Int {
        val url = event.optString("url", "").lowercase(Locale.US)
        val method = NetworkEventClassifier.methodOf(event)
        val fields = requestFields(event)
        var score = 0
        if (hasAuthPathKeyword(url)) score += 5
        if (url.contains("logout") || url.contains("signout") || url.contains("register") || url.contains("signup") || url.contains("create-account")) score -= 8
        if (method in setOf("POST", "PUT", "PATCH")) score += 2
        if (fields.keys.any { isLoginField(it) }) score += 5
        if (fields.keys.any { isPasswordField(it) }) score += 8
        if (fields.keys.any { isOtpField(it) }) score += 4
        if (fields.keys.any { isCsrfField(it) }) score += 2
        if (responseTokenFields(event).isNotEmpty()) score += 6
        if (responseHeaderPairs(event).any { it.first.equals("Set-Cookie", true) }) score += 4
        if (event.optInt("status", 0) in 200..399) score += 1
        if (event.optString("source", "") == "auth-form-submit" || event.optBoolean("_authFormCaptured", false)) score += 3
        return score
    }

    private fun hasAuthPathKeyword(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return listOf("/auth", "/login", "/signin", "/sign-in", "/token", "/session", "/oauth", "/sso", "/verify", "/otp", "/refresh").any { lower.contains(it) }
    }

    private fun hasCredentialFields(event: JSONObject): Boolean {
        val keys = requestFields(event).keys
        return keys.any { isLoginField(it) || isPasswordField(it) || isOtpField(it) || isCsrfField(it) }
    }

    private fun requestFields(event: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val body = event.optString("requestBody", "").trim()
        if (body.startsWith("{") || body.startsWith("[")) {
            try {
                val parsed: Any = if (body.startsWith("{")) JSONObject(body) else JSONArray(body)
                flattenJson(parsed, emptyList(), out)
            } catch (_: Exception) {}
        } else if (body.isNotBlank() && body != "[FormData]") {
            parseUrlEncoded(body).forEach { (key, value) -> out[key] = value }
        }
        try {
            val query = URL(event.optString("url", "")).query.orEmpty()
            parseUrlEncoded(query).forEach { (key, value) -> out.putIfAbsent(key, value) }
        } catch (_: Exception) {}
        event.optJSONArray("_authFormFields")?.let { fields ->
            for (i in 0 until fields.length()) {
                val field = fields.optJSONObject(i) ?: continue
                val name = field.optString("name", "")
                if (name.isNotBlank()) out[name] = field.optString("value", "")
            }
        }
        return out
    }

    private fun flattenJson(value: Any?, path: List<String>, out: MutableMap<String, String>) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    flattenJson(value.opt(key), path + key, out)
                }
            }
            is JSONArray -> for (i in 0 until minOf(value.length(), 50)) flattenJson(value.opt(i), path + i.toString(), out)
            null, JSONObject.NULL -> Unit
            else -> if (path.isNotEmpty()) out[path.last()] = value.toString()
        }
    }

    private fun collectProducers(requests: List<RequestNode>): List<Producer> {
        val out = mutableListOf<Producer>()
        requests.forEachIndexed { index, node ->
            val body = NetworkEventClassifier.responseBodyText(node.event).trim()
            if (body.startsWith("{") || body.startsWith("[")) {
                try {
                    val parsed: Any = if (body.startsWith("{")) JSONObject(body) else JSONArray(body)
                    collectJsonProducers(parsed, emptyList(), index, out)
                } catch (_: Exception) {}
            }
            collectHtmlInputProducers(body, index, out)
        }
        return out.distinctBy { "${it.requestIndex}|${it.key}|${it.value}" }
    }

    private fun collectJsonProducers(value: Any?, path: List<String>, requestIndex: Int, out: MutableList<Producer>) {
        if (out.size > 1500) return
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    collectJsonProducers(value.opt(key), path + key, requestIndex, out)
                }
            }
            is JSONArray -> for (i in 0 until minOf(value.length(), 100)) collectJsonProducers(value.opt(i), path + i.toString(), requestIndex, out)
            null, JSONObject.NULL -> Unit
            else -> {
                if (path.isEmpty()) return
                val text = value.toString()
                val key = path.last()
                if (isInterestingProducerKey(key) || looksTokenLike(text)) out.add(Producer(requestIndex, key, text, path, true))
            }
        }
    }

    private fun collectHtmlInputProducers(body: String, requestIndex: Int, out: MutableList<Producer>) {
        if (body.isBlank() || body.length > 2_000_000 || !body.contains("input", true)) return
        val regex = Regex("<input[^>]*name=[\\\"']([^\\\"']+)[\\\"'][^>]*value=[\\\"']([^\\\"']+)[\\\"'][^>]*>", setOf(RegexOption.IGNORE_CASE))
        regex.findAll(body).take(50).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            if (isCsrfField(key) || isInterestingProducerKey(key)) out.add(Producer(requestIndex, key, value, null, false))
        }
    }

    private fun responseTokenFields(event: JSONObject): List<String> {
        val body = NetworkEventClassifier.responseBodyText(event).trim()
        if (!(body.startsWith("{") || body.startsWith("["))) return emptyList()
        return try {
            val parsed: Any = if (body.startsWith("{")) JSONObject(body) else JSONArray(body)
            val out = mutableListOf<String>()
            collectTokenFieldNames(parsed, out)
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun collectTokenFieldNames(value: Any?, out: MutableList<String>) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (isInterestingProducerKey(key)) out.add(key)
                    collectTokenFieldNames(value.opt(key), out)
                }
            }
            is JSONArray -> for (i in 0 until minOf(value.length(), 30)) collectTokenFieldNames(value.opt(i), out)
        }
    }

    private fun isInterestingProducerKey(key: String): Boolean {
        val lower = key.lowercase(Locale.US).replace("-", "_")
        return lower.contains("token") || lower.contains("csrf") || lower.contains("xsrf") || lower.contains("session") || lower in setOf("jwt", "authorization", "code", "state")
    }

    private fun looksTokenLike(value: String): Boolean {
        if (value.length < 20 || value.length > 4096 || value.contains(' ')) return false
        if (value.count { it == '.' } == 2 && value.all { it.isLetterOrDigit() || it in "-_." }) return true
        val distinct = value.toSet().size
        return value.length >= 32 && distinct >= 12 && value.all { it.isLetterOrDigit() || it in "-_=." }
    }

    private fun isReusableValue(value: String): Boolean {
        if (value.length < 6 || value.length > 4096) return false
        if (value in setOf("true", "false", "null", "undefined")) return false
        return true
    }

    private fun requestMaterial(event: JSONObject): String = buildString {
        append(event.optString("url", "")).append('\n')
        requestHeaderPairs(event).forEach { (k, v) -> append(k).append(':').append(v).append('\n') }
        append(event.optString("requestBody", ""))
    }

    private fun materialContains(material: String, value: String): Boolean {
        if (material.contains(value)) return true
        val encoded = try { URLEncoder.encode(value, "UTF-8").replace("+", "%20") } catch (_: Exception) { "" }
        return encoded.isNotBlank() && material.contains(encoded, true)
    }

    private fun collectCredentialVariables(event: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        requestFields(event).forEach { (key, value) ->
            val variable = credentialVariableForKey(key) ?: return@forEach
            if (value.isNotBlank()) out[value] = variable
        }
        event.optJSONArray("_authFormFields")?.let { fields ->
            for (i in 0 until fields.length()) {
                val field = fields.optJSONObject(i) ?: continue
                val variable = credentialVariableForKey(field.optString("name", "")) ?: continue
                val value = field.optString("value", "")
                if (value.isNotBlank()) out[value] = variable
            }
        }
        return out
    }

    private fun credentialVariableForKey(key: String): String? {
        val lower = key.lowercase(Locale.US).replace("-", "_").replace(".", "_")
        return when {
            isPasswordField(lower) -> "password"
            isLoginField(lower) -> "login"
            isOtpField(lower) -> "otp"
            isCsrfField(lower) -> "csrf_token"
            lower.contains("code_verifier") -> "code_verifier"
            lower.contains("code_challenge") -> "code_challenge"
            else -> null
        }
    }

    private fun isLoginField(key: String): Boolean {
        val lower = key.lowercase(Locale.US)
        return lower in setOf("login", "username", "user", "email", "phone", "mobile", "msisdn", "identifier", "account", "user_name", "userlogin") || lower.endsWith("_login") || lower.endsWith("_email") || lower.endsWith("_phone")
    }

    private fun isPasswordField(key: String): Boolean {
        val lower = key.lowercase(Locale.US)
        return lower == "password" || lower == "pass" || lower == "passwd" || lower == "pwd" || lower.endsWith("_password")
    }

    private fun isOtpField(key: String): Boolean {
        val lower = key.lowercase(Locale.US)
        return lower in setOf("otp", "totp", "one_time_password", "verification_code", "sms_code", "pin") || lower.endsWith("_otp") || lower.endsWith("_code") && !lower.contains("status")
    }

    private fun isCsrfField(key: String): Boolean {
        val lower = key.lowercase(Locale.US)
        return lower.contains("csrf") || lower.contains("xsrf") || lower in setOf("_token", "authenticity_token", "requestverificationtoken")
    }

    private fun canonicalVariableName(key: String): String {
        val lower = key.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return when {
            lower.contains("refresh") && lower.contains("token") -> "refresh_token"
            lower.contains("access") && lower.contains("token") -> "access_token"
            lower.contains("id_token") || lower == "idtoken" -> "id_token"
            lower.contains("csrf") || lower.contains("xsrf") -> "csrf_token"
            lower.contains("session") && lower.contains("token") -> "session_token"
            lower == "jwt" -> "access_token"
            lower == "authorization" -> "access_token"
            lower == "token" -> "access_token"
            lower.isNotBlank() -> lower.take(48)
            else -> "auth_value"
        }
    }

    private fun uniqueVariableName(base: String, used: Set<String>): String {
        if (base !in used) return base
        var index = 2
        while ("${base}_$index" in used) index++
        return "${base}_$index"
    }

    private fun credentialDescription(name: String): String = when (name) {
        "login" -> "Login / username / email / phone detected in the authentication request"
        "password" -> "Password detected in the authentication request; intentionally left empty"
        "otp" -> "One-time code detected in the authentication flow; fill it when required"
        "csrf_token" -> "Captured anti-CSRF value. It is dynamically extracted when a producer response was detected."
        "code_verifier" -> "Captured OAuth PKCE code_verifier fallback"
        "code_challenge" -> "Captured OAuth PKCE code_challenge fallback"
        else -> "AUTH variable"
    }

    private fun addAuthHeaderFallbacks(
        requests: List<RequestNode>,
        selected: Set<Int>,
        replacements: MutableMap<String, String>,
        variables: MutableMap<String, VariableDef>,
        used: MutableSet<String>
    ) {
        selected.sorted().forEach { index ->
            requestHeaderPairs(requests[index].event).forEach { (name, value) ->
                val lower = name.lowercase(Locale.US)
                if (lower == "authorization" && value.startsWith("Bearer ", true)) {
                    val token = value.substringAfter(' ').trim()
                    if (token.isNotBlank() && replacements.keys.none { it == token }) {
                        val variable = uniqueVariableName("access_token", used)
                        used.add(variable)
                        replacements[token] = variable
                        variables[variable] = VariableDef(variable, token, "Captured Bearer token fallback; no response extractor was detected")
                    }
                } else if ((lower.contains("api-key") || lower == "x-api-key" || lower.contains("auth-token")) && value.isNotBlank()) {
                    if (replacements.keys.none { it == value }) {
                        val variable = uniqueVariableName(if (lower.contains("api")) "api_key" else "auth_token", used)
                        used.add(variable)
                        replacements[value] = variable
                        variables[variable] = VariableDef(variable, value, "Captured authentication header fallback")
                    }
                }
            }
        }
    }

    private fun addStorageFallbacks(
        before: BrowserState,
        after: BrowserState,
        replacements: MutableMap<String, String>,
        variables: MutableMap<String, VariableDef>,
        used: MutableSet<String>
    ) {
        val pairs = mutableListOf<Pair<String, String>>()
        listOf(after.localStorage, after.sessionStorage).forEach { store ->
            val keys = store.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!isInterestingProducerKey(key)) continue
                val value = store.optString(key, "")
                if (isReusableValue(value)) pairs.add(key to value)
            }
        }
        pairs.forEach { (key, value) ->
            if (replacements.containsKey(value)) return@forEach
            val variable = uniqueVariableName(canonicalVariableName(key), used)
            used.add(variable)
            replacements[value] = variable
            variables[variable] = VariableDef(variable, value, "Captured browser-storage authentication value fallback")
        }
    }

    private fun buildPostmanRequest(
        event: JSONObject,
        method: String,
        rawUrl: String,
        origin: String,
        replacements: Map<String, String>
    ): JSONObject {
        val headers = JSONArray()
        requestHeaderPairs(event).forEach { (name, value) ->
            val lower = name.lowercase(Locale.US)
            if (lower in setOf("cookie", "host", "content-length", "accept-encoding", "connection")) return@forEach
            if (lower.startsWith("sec-fetch-") || lower.startsWith("sec-ch-ua")) return@forEach
            headers.put(JSONObject().put("key", name).put("value", applyReplacements(value, replacements)).put("type", "text"))
        }

        val request = JSONObject()
            .put("method", method.ifBlank { "GET" })
            .put("header", headers)
            .put("url", portableUrl(rawUrl, origin, replacements))
            .put("description", "Reconstructed from captured AUTH traffic by web research")

        buildPostmanBody(event, replacements)?.let { request.put("body", it) }
        return request
    }

    private fun buildPostmanBody(event: JSONObject, replacements: Map<String, String>): JSONObject? {
        val raw = event.optString("requestBody", "")
        val mime = event.optString("requestMimeType", "").lowercase(Locale.US)
        val formFields = event.optJSONArray("_authFormFields")

        if (formFields != null && formFields.length() > 0 && mime.contains("multipart")) {
            val data = JSONArray()
            for (i in 0 until formFields.length()) {
                val field = formFields.optJSONObject(i) ?: continue
                val name = field.optString("name", "")
                if (name.isBlank()) continue
                data.put(JSONObject().put("key", name).put("value", applyReplacements(field.optString("value", ""), replacements)).put("type", "text"))
            }
            return JSONObject().put("mode", "formdata").put("formdata", data)
        }

        if (mime.contains("x-www-form-urlencoded") || (raw.contains('=') && !raw.trimStart().startsWith("{") && !raw.trimStart().startsWith("["))) {
            val data = JSONArray()
            parseUrlEncoded(raw).forEach { (key, value) ->
                data.put(JSONObject().put("key", key).put("value", applyReplacements(value, replacements)).put("type", "text"))
            }
            if (data.length() > 0) return JSONObject().put("mode", "urlencoded").put("urlencoded", data)
        }

        if (raw.isBlank()) return null
        val prepared = applyReplacements(raw, replacements)
        val language = if (mime.contains("json") || raw.trimStart().startsWith("{") || raw.trimStart().startsWith("[")) "json" else "text"
        return JSONObject()
            .put("mode", "raw")
            .put("raw", prepared)
            .put("options", JSONObject().put("raw", JSONObject().put("language", language)))
    }

    private fun buildTestLines(bindings: List<Binding>): List<String> {
        val lines = mutableListOf<String>()
        bindings.distinctBy { it.variable }.forEach { binding ->
            val path = binding.jsonPath
            if (binding.dynamic && path != null && path.isNotEmpty()) {
                lines.add("try {")
                lines.add("  let value = pm.response.json();")
                lines.add("  for (const key of ${JSONArray(path).toString()}) value = value == null ? undefined : value[key];")
                lines.add("  if (value !== undefined && value !== null) pm.collectionVariables.set(${JSONObject.quote(binding.variable)}, String(value));")
                lines.add("} catch (e) {}")
            }
        }
        return lines
    }

    private fun portableUrl(rawUrl: String, origin: String, replacements: Map<String, String>): String {
        var url = applyReplacements(rawUrl, replacements)
        if (origin.isNotBlank() && rawUrl.startsWith(origin)) url = "{{base_url}}" + url.substring(origin.length)
        return url
    }

    private fun applyReplacements(raw: String, replacements: Map<String, String>): String {
        var out = raw
        replacements.entries.sortedByDescending { it.key.length }.forEach { (value, variable) ->
            if (value.isBlank()) return@forEach
            val marker = "{{$variable}}"
            out = out.replace(value, marker)
            try {
                val encoded = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
                if (encoded != value) out = out.replace(encoded, marker, ignoreCase = true)
            } catch (_: Exception) {}
        }
        out = out.replace("[password]", "{{password}}")
        return out
    }

    private fun isLikelyAuthenticatedRequest(event: JSONObject): Boolean {
        if (requestHeaderPairs(event).any { (name, value) -> name.equals("Authorization", true) && value.isNotBlank() }) return true
        if (event.optString("source", "") in setOf("fetch", "xhr") && NetworkEventClassifier.responseKind(event) == "JSON" && event.optInt("status", 0) in 200..299) return true
        return NetworkEventClassifier.isApiRelevant(event) && event.optInt("status", 0) in 200..299
    }

    private fun shouldAddSeedRequest(before: BrowserState, after: BrowserState, login: RequestNode, selected: Set<Int>, requests: List<RequestNode>): Boolean {
        if (!before.url.startsWith("http")) return false
        if (originOf(before.url) != originOf(login.url)) return false
        if (changedCookieNames(before.nativeCookies, after.nativeCookies).isNotEmpty()) return true
        if (hasAuthPathKeyword(before.url)) return true
        return selected.any { index -> requestFields(requests[index].event).keys.any { isCsrfField(it) } }
    }

    private fun changedCookieNames(before: String, after: String): List<String> {
        val a = parseCookies(before)
        val b = parseCookies(after)
        return (a.keys + b.keys).distinct().filter { a[it] != b[it] }.sorted()
    }

    private fun changedStorageKeys(before: BrowserState, after: BrowserState): List<String> {
        val out = mutableListOf<String>()
        listOf("local" to (before.localStorage to after.localStorage), "session" to (before.sessionStorage to after.sessionStorage)).forEach { (name, stores) ->
            val keys = linkedSetOf<String>()
            stores.first.keys().forEachRemaining { keys.add(it) }
            stores.second.keys().forEachRemaining { keys.add(it) }
            keys.filter { stores.first.opt(it)?.toString() != stores.second.opt(it)?.toString() }.forEach { out.add("$name:$it") }
        }
        return out.sorted()
    }

    private fun parseCookies(raw: String): Map<String, String> = raw.split(';').mapNotNull { part ->
        val p = part.trim().indexOf('=')
        if (p <= 0) null else part.trim().substring(0, p).trim() to part.trim().substring(p + 1)
    }.toMap()

    private fun requestHeaderPairs(event: JSONObject): List<Pair<String, String>> {
        val headers = event.optJSONObject("requestHeaders") ?: event.optJSONObject("headers") ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = headers.opt(key)
            if (value != null && value != JSONObject.NULL) out.add(key to value.toString())
        }
        return out
    }

    private fun responseHeaderPairs(event: JSONObject): List<Pair<String, String>> {
        event.optJSONObject("responseHeaders")?.let { headers ->
            val out = mutableListOf<Pair<String, String>>()
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = headers.opt(key)
                if (value != null && value != JSONObject.NULL) out.add(key to value.toString())
            }
            return out
        }
        val raw = event.optString("responseHeadersRaw", "")
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val p = line.indexOf(':')
            if (p <= 0) null else line.substring(0, p).trim() to line.substring(p + 1).trim()
        }
    }

    private fun parseUrlEncoded(raw: String): List<Pair<String, String>> {
        if (raw.isBlank()) return emptyList()
        return raw.split('&').filter { it.isNotBlank() }.map { part ->
            val p = part.indexOf('=')
            val key = if (p >= 0) part.substring(0, p) else part
            val value = if (p >= 0) part.substring(p + 1) else ""
            decode(key) to decode(value)
        }
    }

    private fun decode(value: String): String = try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { value }

    private fun hostOf(raw: String): String = try { URL(raw).host } catch (_: Exception) { "" }

    private fun originOf(raw: String): String = try {
        val url = URL(raw)
        buildString {
            append(url.protocol).append("://").append(url.host)
            if (url.port > 0 && url.port != url.defaultPort) append(':').append(url.port)
        }
    } catch (_: Exception) { "" }

    private fun compactPath(raw: String): String = try {
        val url = URL(raw)
        val path = url.path.ifBlank { "/" }
        if (path.length <= 56) path else "…" + path.takeLast(55)
    } catch (_: Exception) {
        if (raw.length <= 56) raw else raw.take(53) + "…"
    }
}
