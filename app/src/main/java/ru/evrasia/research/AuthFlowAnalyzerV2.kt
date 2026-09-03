package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

internal object AuthFlowAnalyzerV2 {
    private const val SCHEMA = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"

    private data class RequestNode(
        val event: JSONObject,
        val source: String,
        val method: String,
        val url: String,
        val time: Long
    )

    private data class FormHint(
        val event: JSONObject,
        val page: String,
        val url: String,
        val method: String,
        val time: Long,
        val fields: Map<String, String>
    )

    private data class Producer(
        val requestIndex: Int,
        val key: String,
        val value: String,
        val kind: String,
        val path: List<String>? = null,
        val headerName: String = ""
    )

    private data class Binding(
        val producerIndex: Int,
        val consumerIndex: Int,
        val value: String,
        val variable: String,
        val producer: Producer
    )

    private data class VariableDef(
        val key: String,
        val value: String,
        val description: String
    )

    fun analyze(
        events: List<JSONObject>,
        before: AuthFlowAnalyzer.BrowserState,
        after: AuthFlowAnalyzer.BrowserState
    ): AuthFlowAnalyzer.Result {
        val prepared = prepareRequests(events)
        val requests = prepared.first
        val formHints = prepared.second
        if (requests.isEmpty()) throw IllegalStateException("За время AUTH-анализа HTTP-запросы не обнаружены")

        val scores = requests.map { loginScore(it) }
        val loginIndex = chooseLoginIndex(requests, scores)
        val login = requests[loginIndex]
        val loginOrigin = originOf(login.url)
        val beforeOrigin = originOf(before.url)
        val afterOrigin = originOf(after.url)
        val flowOrigins = linkedSetOf<String>()
        listOf(beforeOrigin, loginOrigin, afterOrigin).filter { it.isNotBlank() }.forEach(flowOrigins::add)

        requests.indices.forEach { index ->
            val node = requests[index]
            if (abs(node.time - login.time) > 120_000L && node.time > 0L && login.time > 0L) return@forEach
            if (isStrongCrossOriginAuthBridge(node)) flowOrigins.add(originOf(node.url))
        }

        val selected = linkedSetOf<Int>()
        selected.add(loginIndex)
        requests.indices.forEach { index ->
            val node = requests[index]
            if (isNoise(node, flowOrigins)) return@forEach
            val close = node.time <= 0L || login.time <= 0L || abs(node.time - login.time) <= 120_000L
            if (!close) return@forEach
            val score = scores[index]
            if (node.event.optBoolean("_authFormCorrelated", false) || score >= 11) selected.add(index)
        }

        val producers = collectProducers(requests, formHints)
        val bindings = mutableListOf<Binding>()
        val usedNames = linkedSetOf<String>()

        fun bind(producer: Producer, consumerIndex: Int) {
            if (producer.value.isBlank() || !isReusableValue(producer.value)) return
            if (bindings.any { it.producerIndex == producer.requestIndex && it.consumerIndex == consumerIndex && it.value == producer.value }) return
            val variable = uniqueVariableName(canonicalVariableName(producer.key), usedNames)
            usedNames.add(variable)
            bindings.add(Binding(producer.requestIndex, consumerIndex, producer.value, variable, producer))
            if (producer.requestIndex >= 0) selected.add(producer.requestIndex)
            selected.add(consumerIndex)
        }

        requests.indices.forEach { consumerIndex ->
            val consumer = requests[consumerIndex]
            if (isNoise(consumer, flowOrigins)) return@forEach
            val material = requestMaterial(consumer.event)
            producers.asSequence()
                .filter { producer -> producer.requestIndex < consumerIndex && isReusableValue(producer.value) && materialContains(material, producer.value) }
                .sortedByDescending { it.requestIndex }
                .take(6)
                .forEach { producer ->
                    val consumerStrong = consumerIndex in selected || loginScore(consumer) >= 7 || consumer.event.optBoolean("_authFormCorrelated", false)
                    if (consumerStrong) bind(producer, consumerIndex)
                }
        }

        addPreparatoryDocument(requests, selected, loginIndex, formHints, flowOrigins)
        val verifyIndex = chooseVerifyIndex(requests, loginIndex, before, after, flowOrigins)
        if (verifyIndex != null) selected.add(verifyIndex)

        requests.indices.forEach { index ->
            if (index <= loginIndex || index > loginIndex + 20) return@forEach
            val node = requests[index]
            if (isNoise(node, flowOrigins)) return@forEach
            if (looksRefresh(node) && (originOf(node.url) in flowOrigins || isStrongCrossOriginAuthBridge(node))) selected.add(index)
        }

        val replacements = linkedMapOf<String, String>()
        val variables = linkedMapOf<String, VariableDef>()

        bindings.forEach { binding ->
            replacements.putIfAbsent(binding.value, binding.variable)
            val dynamic = binding.producer.kind != "captured"
            variables.putIfAbsent(
                binding.variable,
                VariableDef(
                    binding.variable,
                    if (dynamic) "" else binding.value,
                    if (dynamic) "Automatically extracted from an earlier AUTH response" else "Captured AUTH dependency fallback"
                )
            )
        }

        selected.sorted().forEach { index ->
            collectCredentialVariables(requests[index].event).forEach { (value, candidate) ->
                if (value.isBlank() || value == "[password]" || replacements.containsKey(value)) return@forEach
                val variable = uniqueVariableName(candidate, usedNames)
                usedNames.add(variable)
                replacements[value] = variable
                val defaultValue = if (candidate in setOf("login", "password", "otp")) "" else value
                variables.putIfAbsent(variable, VariableDef(variable, defaultValue, credentialDescription(candidate)))
            }
        }

        if (selected.any { index -> requestFields(requests[index].event).keys.any(::isPasswordField) }) {
            variables.putIfAbsent("password", VariableDef("password", "", credentialDescription("password")))
            usedNames.add("password")
        }

        addHeaderVariables(requests, selected, replacements, variables, usedNames)
        addUsedStorageVariables(requests, selected, before, after, replacements, variables, usedNames)

        val baseOrigin = beforeOrigin.ifBlank { loginOrigin.ifBlank { afterOrigin } }
        if (baseOrigin.isNotBlank()) variables["base_url"] = VariableDef("base_url", baseOrigin, "Primary origin detected for this AUTH flow")

        val filteredSelected = dedupeSelected(requests, selected)
        val seedNeeded = shouldAddSeed(before, requests, filteredSelected, formHints)
        val bindingByProducer = bindings.groupBy { it.producerIndex }
        val items = JSONArray()
        var sequence = 1

        if (seedNeeded) {
            val seedRequest = JSONObject()
                .put("method", "GET")
                .put("header", JSONArray())
                .put("url", portableUrl(before.url, baseOrigin, replacements))
                .put("description", "Reopens the authentication page to establish cookies and dynamic page state before the captured AUTH steps.")
            val seed = JSONObject()
                .put("name", "%02d Prepare authentication page".format(Locale.US, sequence++))
                .put("request", seedRequest)
            val seedTests = buildTestLines(bindingByProducer[-1].orEmpty())
            if (seedTests.isNotEmpty()) seed.put("event", testEvent(seedTests))
            items.put(seed)
        }

        filteredSelected.forEach { index ->
            val node = requests[index]
            val role = roleFor(index, loginIndex, verifyIndex, node)
            val request = buildPostmanRequest(node.event, node.method, node.url, baseOrigin, replacements)
            val item = JSONObject()
                .put("name", "%02d %s · %s %s".format(Locale.US, sequence++, role, node.method, compactPath(node.url)))
                .put("request", request)
            buildPostmanResponse(node.event, request)?.let { response -> item.put("response", JSONArray().put(response)) }
            val tests = buildTestLines(bindingByProducer[index].orEmpty())
            if (tests.isNotEmpty()) item.put("event", testEvent(tests))
            items.put(item)
        }

        val changedCookies = changedCookieNames(before.nativeCookies, after.nativeCookies)
        val changedStorage = changedStorageKeys(before, after)
        val loginHasResponse = hasResponseEvidence(login.event)
        val loginIsReal = !login.event.optBoolean("_authSynthetic", false)
        val confidence = when {
            loginIsReal && scores[loginIndex] >= 15 && loginHasResponse && (verifyIndex != null || changedCookies.isNotEmpty() || bindings.isNotEmpty()) -> "HIGH"
            scores[loginIndex] >= 8 -> "MEDIUM"
            else -> "LOW"
        }

        val notes = mutableListOf<String>()
        notes.add("Detected login candidate: ${login.method} ${login.url}")
        if (login.event.optBoolean("_authFormCorrelated", false)) notes.add("DOM form submission was correlated with the real network request")
        if (verifyIndex != null) notes.add("Session verification candidate: ${requests[verifyIndex].method} ${requests[verifyIndex].url}")
        if (bindings.isNotEmpty()) notes.add("Detected ${bindings.size} response → request dependencies")
        if (changedCookies.isNotEmpty()) notes.add("Cookies changed: ${changedCookies.joinToString(", ")}")
        if (changedStorage.isNotEmpty()) notes.add("Browser storage changed: ${changedStorage.joinToString(", ")}")
        if (!loginHasResponse) notes.add("The selected login request has no captured HTTP response; reconstruction is less reliable")
        if (!loginIsReal) notes.add("No matching network request was captured; a DOM form submission is used as fallback")
        if (confidence == "LOW") notes.add("Authentication confidence is low; collection requires manual validation")

        val variableArray = JSONArray()
        variables.values.forEach { def ->
            variableArray.put(JSONObject().put("key", def.key).put("value", def.value).put("type", "string").put("description", def.description))
        }

        val description = buildString {
            append("Generated by web research universal AUTH analyzer.\n")
            append("Confidence: ").append(confidence).append(".\n")
            notes.forEach { append("- ").append(it).append('\n') }
            append("\nCaptured Cookie request headers are intentionally removed. Postman's cookie jar is expected to retain cookies issued by the server.")
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
            .put("item", items)
            .put("variable", variableArray)

        return AuthFlowAnalyzer.Result(
            collectionJson = collection.toString(2),
            confidence = confidence,
            requestCount = items.length(),
            loginUrl = login.url,
            notes = notes
        )
    }

    private fun prepareRequests(events: List<JSONObject>): Pair<List<RequestNode>, List<FormHint>> {
        val hints = events.filter { it.optString("source", "") == "auth-form-submit" }.map { event ->
            FormHint(
                event = JSONObject(event.toString()),
                page = event.optString("page", ""),
                url = event.optString("url", ""),
                method = event.optString("method", "POST").uppercase(Locale.US),
                time = event.optLong("time", 0L),
                fields = formHintFields(event)
            )
        }.sortedBy { it.time }

        val merged = NetworkDisplayMerger.merge(events.map { JSONObject(it.toString()) })
            .filter { event ->
                val source = event.optString("source", "")
                source != "auth-form-submit" && NetworkEventClassifier.isPlainRequestEvent(event)
            }
            .filter { event -> event.optString("url", "").startsWith("http://") || event.optString("url", "").startsWith("https://") }
            .sortedBy { it.optLong("time", 0L) }
            .toMutableList()

        hints.forEach { hint ->
            val candidates = merged.withIndex().mapNotNull { indexed ->
                val event = indexed.value
                val delta = abs(event.optLong("time", 0L) - hint.time)
                if (hint.time > 0L && event.optLong("time", 0L) > 0L && delta > 8000L) return@mapNotNull null
                val evidence = formCorrelationEvidence(hint, event)
                if (!evidence.first) return@mapNotNull null
                indexed.index to evidence.second
            }
            val best = candidates.maxByOrNull { it.second }
            if (best != null && best.second >= 8) {
                val target = merged[best.first]
                target.put("_authFormCorrelated", true)
                target.put("_authFormPage", hint.page)
                target.put("_authFormUrl", hint.url)
                target.put("_authFormFields", hint.event.optJSONArray("formFields") ?: JSONArray())
                if (target.optString("requestBody", "").isBlank()) target.put("requestBody", hint.event.optString("requestBody", ""))
                if (target.optString("requestMimeType", "").isBlank() && hint.event.optString("requestMimeType", "").isNotBlank()) {
                    target.put("requestMimeType", hint.event.optString("requestMimeType", ""))
                }
            } else {
                val synthetic = JSONObject(hint.event.toString()).put("_authSynthetic", true)
                merged.add(synthetic)
            }
        }

        val nodes = merged.sortedBy { it.optLong("time", 0L) }.map { event ->
            RequestNode(
                event = event,
                source = event.optString("source", ""),
                method = NetworkEventClassifier.methodOf(event).ifBlank { event.optString("method", "GET") }.uppercase(Locale.US),
                url = event.optString("url", ""),
                time = event.optLong("time", 0L)
            )
        }
        return nodes to hints
    }

    private fun formCorrelationEvidence(hint: FormHint, event: JSONObject): Pair<Boolean, Int> {
        val targetUrl = event.optString("url", "")
        val source = event.optString("source", "")
        val method = NetworkEventClassifier.methodOf(event).ifBlank { event.optString("method", "GET") }.uppercase(Locale.US)
        val targetFields = requestFields(event)
        val hintNames = hint.fields.keys.map { normalizeFieldName(it) }.toSet()
        val targetNames = targetFields.keys.map { normalizeFieldName(it) }.toSet()
        val sharedNames = hintNames.intersect(targetNames)
        val hintKinds = hint.fields.keys.mapNotNull(::credentialKindForKey).toSet()
        val targetKinds = targetFields.keys.mapNotNull(::credentialKindForKey).toSet()
        val sharedKinds = hintKinds.intersect(targetKinds)
        val sharedValues = hint.fields.values.filter { it.isNotBlank() && it !in setOf("[password]", "[file]") }.any { value -> targetFields.values.any { it == value } }
        val sameUrl = normalizeUrl(targetUrl) == normalizeUrl(hint.url)
        val sameOrigin = originOf(targetUrl).isNotBlank() && originOf(targetUrl) == originOf(hint.page.ifBlank { hint.url })
        val hasEvidence = sameUrl || sharedKinds.isNotEmpty() || sharedNames.size >= 2 || sharedValues
        if (!hasEvidence) return false to 0

        var score = 0
        if (sameUrl) score += 6
        if (sameOrigin) score += 2
        if (method == hint.method) score += 2
        if (source in setOf("fetch", "xhr", "replay")) score += 5
        if (sharedKinds.isNotEmpty()) score += sharedKinds.size * 6
        score += minOf(sharedNames.size, 5) * 2
        if (sharedValues) score += 4
        val delta = abs(event.optLong("time", 0L) - hint.time)
        score += when {
            delta <= 1000L -> 4
            delta <= 3000L -> 3
            delta <= 8000L -> 1
            else -> 0
        }
        if (hasResponseEvidence(event)) score += 2
        return true to score
    }

    private fun chooseLoginIndex(requests: List<RequestNode>, scores: List<Int>): Int {
        val real = scores.indices.filter { !requests[it].event.optBoolean("_authSynthetic", false) }
        val bestReal = real.maxByOrNull { scores[it] }
        if (bestReal != null && scores[bestReal] >= 5) return bestReal
        return scores.indices.maxByOrNull { scores[it] } ?: 0
    }

    private fun loginScore(node: RequestNode): Int {
        val event = node.event
        val fields = requestFields(event)
        var score = 0
        if (hasAuthPathKeyword(node.url)) score += 5
        if (node.method in setOf("POST", "PUT", "PATCH")) score += 3
        if (fields.keys.any(::isLoginField)) score += 7
        if (fields.keys.any(::isPasswordField)) score += 12
        if (fields.keys.any(::isOtpField)) score += 6
        if (fields.keys.any(::isCsrfField)) score += 2
        if (responseTokenFields(event).isNotEmpty()) score += 5
        if (responseHeaderPairs(event).any { it.first.equals("Set-Cookie", true) }) score += 4
        if (hasResponseEvidence(event)) score += 4
        if (node.source in setOf("fetch", "xhr", "replay")) score += 4
        if (event.optBoolean("_authFormCorrelated", false)) score += 6
        if (event.optBoolean("_authSynthetic", false)) score -= 8
        if (looksLogoutOrRegistration(node.url)) score -= 12
        if (isStatic(node)) score -= 30
        if (isLikelyTelemetry(node) && !hasCredentialFields(event)) score -= 18
        return score
    }

    private fun addPreparatoryDocument(
        requests: List<RequestNode>,
        selected: MutableSet<Int>,
        loginIndex: Int,
        hints: List<FormHint>,
        flowOrigins: Set<String>
    ) {
        val firstAuth = selected.minOrNull() ?: loginIndex
        val hintPages = hints.map { normalizeUrl(it.page) }.filter { it.isNotBlank() }.toSet()
        val candidate = (0 until firstAuth).reversed().firstOrNull { index ->
            val node = requests[index]
            if (node.method != "GET" || isNoise(node, flowOrigins) || !isDocumentLike(node)) return@firstOrNull false
            val close = node.time <= 0L || requests[loginIndex].time <= 0L || requests[loginIndex].time - node.time <= 120_000L
            close && (normalizeUrl(node.url) in hintPages || hasAuthPathKeyword(node.url) || node.event.optString("source", "") == "navigation")
        }
        if (candidate != null) selected.add(candidate)
    }

    private fun chooseVerifyIndex(
        requests: List<RequestNode>,
        loginIndex: Int,
        before: AuthFlowAnalyzer.BrowserState,
        after: AuthFlowAnalyzer.BrowserState,
        flowOrigins: Set<String>
    ): Int? {
        val finalUrl = normalizeUrl(after.url)
        var bestIndex: Int? = null
        var bestScore = Int.MIN_VALUE
        val limit = minOf(requests.lastIndex, loginIndex + 30)
        for (index in loginIndex + 1..limit) {
            val node = requests[index]
            if (isNoise(node, flowOrigins) || isStatic(node)) continue
            var score = 0
            val normalized = normalizeUrl(node.url)
            val origin = originOf(node.url)
            if (origin in flowOrigins) score += 5 else score -= 10
            if (finalUrl.isNotBlank() && normalized == finalUrl) score += 12
            if (node.source == "navigation") score += 6
            if (node.method == "GET") score += 2
            val status = node.event.optInt("status", 0)
            if (status in 200..399) score += 4
            if (requestHeaderPairs(node.event).any { it.first.equals("Authorization", true) && it.second.isNotBlank() }) score += 6
            if (responseLooksLikeLogin(node.event, node.url)) score -= 12
            if (hasAuthPathKeyword(node.url) && finalUrl != normalized) score -= 3
            if (normalizeUrl(before.url) == normalized && normalizeUrl(before.url) != finalUrl) score -= 2
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }
        return if (bestScore >= 6) bestIndex else null
    }

    private fun collectProducers(requests: List<RequestNode>, hints: List<FormHint>): List<Producer> {
        val out = mutableListOf<Producer>()
        hints.forEach { hint ->
            hint.fields.forEach { (key, value) ->
                if (value.isBlank() || value in setOf("[password]", "[file]")) return@forEach
                if (isInterestingProducerKey(key) || looksTokenLike(value)) out.add(Producer(-1, key, value, "html_input"))
            }
        }

        requests.forEachIndexed { index, node ->
            val body = responseBody(node.event).trim()
            if (body.startsWith("{") || body.startsWith("[")) {
                try {
                    val parsed: Any = if (body.startsWith("{")) JSONObject(body) else JSONArray(body)
                    collectJsonProducers(parsed, emptyList(), index, out)
                } catch (_: Exception) {}
            }
            collectHtmlProducers(body, index, out)
            responseHeaderPairs(node.event).forEach { (name, value) ->
                val lower = name.lowercase(Locale.US)
                if (lower == "set-cookie") return@forEach
                if (lower == "location") {
                    try {
                        val url = URL(URL(node.url), value)
                        parseUrlEncoded(url.query.orEmpty()).forEach { (key, queryValue) ->
                            if (isInterestingProducerKey(key) && isReusableValue(queryValue)) out.add(Producer(index, key, queryValue, "location_query", headerName = key))
                        }
                    } catch (_: Exception) {}
                } else if (lower.contains("token") || lower.contains("csrf") || lower.contains("xsrf") || lower == "authorization") {
                    if (isReusableValue(value)) out.add(Producer(index, name, value, "header", headerName = name))
                }
            }
        }
        return out.distinctBy { "${it.requestIndex}|${it.key}|${it.value}|${it.kind}" }
    }

    private fun collectJsonProducers(value: Any?, path: List<String>, index: Int, out: MutableList<Producer>) {
        if (out.size > 2000) return
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    collectJsonProducers(value.opt(key), path + key, index, out)
                }
            }
            is JSONArray -> for (i in 0 until minOf(value.length(), 100)) collectJsonProducers(value.opt(i), path + i.toString(), index, out)
            null, JSONObject.NULL -> Unit
            else -> {
                if (path.isEmpty()) return
                val text = value.toString()
                val key = path.last()
                if (isInterestingProducerKey(key) || looksTokenLike(text)) out.add(Producer(index, key, text, "json", path))
            }
        }
    }

    private fun collectHtmlProducers(body: String, index: Int, out: MutableList<Producer>) {
        if (body.isBlank() || body.length > 2_000_000) return
        Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(body).take(100).forEach { match ->
            val attrs = parseHtmlAttributes(match.value)
            val key = attrs["name"].orEmpty()
            val value = attrs["value"].orEmpty()
            if (key.isNotBlank() && value.isNotBlank() && (isInterestingProducerKey(key) || looksTokenLike(value))) out.add(Producer(index, key, value, "html_input"))
        }
        Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(body).take(100).forEach { match ->
            val attrs = parseHtmlAttributes(match.value)
            val key = attrs["name"].orEmpty().ifBlank { attrs["property"].orEmpty() }
            val value = attrs["content"].orEmpty()
            if (key.isNotBlank() && value.isNotBlank() && (isInterestingProducerKey(key) || looksTokenLike(value))) out.add(Producer(index, key, value, "html_meta"))
        }
        val assignment = Regex("(?i)[\\\"']?([a-z0-9_.-]*(?:csrf|xsrf|token|nonce|state|sessid|session)[a-z0-9_.-]*)[\\\"']?\\s*[:=]\\s*[\\\"']([^\\\"']{6,4096})[\\\"']")
        assignment.findAll(body).take(100).forEach { match ->
            out.add(Producer(index, match.groupValues[1], match.groupValues[2], "html_assignment"))
        }
    }

    private fun requestFields(event: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val raw = event.optString("requestBody", "").trim()
        if (raw.startsWith("{")) {
            try { flattenJson(JSONObject(raw), emptyList(), out) } catch (_: Exception) {}
        } else if (raw.startsWith("[")) {
            try {
                val array = JSONArray(raw)
                if (!extractPairArray(array, out)) flattenJson(array, emptyList(), out)
            } catch (_: Exception) {}
        } else if (raw.isNotBlank() && raw !in setOf("[FormData]", "[unavailable]")) {
            parseUrlEncoded(raw).forEach { (key, value) -> out[key] = value }
            extractMultipartFields(raw).forEach { (key, value) -> out.putIfAbsent(key, value) }
        }
        try {
            parseUrlEncoded(URL(event.optString("url", "")).query.orEmpty()).forEach { (key, value) -> out.putIfAbsent(key, value) }
        } catch (_: Exception) {}
        event.optJSONArray("_authFormFields")?.let { fields ->
            for (i in 0 until fields.length()) {
                val field = fields.optJSONObject(i) ?: continue
                val name = field.optString("name", "")
                if (name.isNotBlank()) out.putIfAbsent(name, field.optString("value", ""))
            }
        }
        return out
    }

    private fun extractPairArray(array: JSONArray, out: MutableMap<String, String>): Boolean {
        if (array.length() == 0) return false
        var pairs = 0
        for (i in 0 until minOf(array.length(), 200)) {
            val item = array.opt(i)
            when (item) {
                is JSONArray -> {
                    if (item.length() < 2) return false
                    val key = item.optString(0, "")
                    if (key.isBlank()) return false
                    out[key] = scalarValue(item.opt(1))
                    pairs++
                }
                is JSONObject -> {
                    val key = item.optString("name", item.optString("key", ""))
                    if (key.isBlank() || !item.has("value")) return false
                    out[key] = scalarValue(item.opt("value"))
                    pairs++
                }
                else -> return false
            }
        }
        return pairs > 0
    }

    private fun capturedFormPairs(event: JSONObject): List<Pair<String, String>> {
        val raw = event.optString("requestBody", "").trim()
        if (!raw.startsWith("[")) return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = linkedMapOf<String, String>()
            if (!extractPairArray(array, out)) emptyList() else out.entries.map { it.key to it.value }
        } catch (_: Exception) { emptyList() }
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
            is JSONArray -> for (i in 0 until minOf(value.length(), 100)) flattenJson(value.opt(i), path + i.toString(), out)
            null, JSONObject.NULL -> Unit
            else -> if (path.isNotEmpty()) out[path.last()] = value.toString()
        }
    }

    private fun formHintFields(event: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val fields = event.optJSONArray("formFields") ?: return out
        for (i in 0 until fields.length()) {
            val field = fields.optJSONObject(i) ?: continue
            val name = field.optString("name", "")
            if (name.isNotBlank()) out[name] = field.optString("value", "")
        }
        return out
    }

    private fun collectCredentialVariables(event: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        requestFields(event).forEach { (key, value) ->
            val variable = credentialVariableForKey(key) ?: return@forEach
            if (value.isNotBlank()) out[value] = variable
        }
        return out
    }

    private fun credentialVariableForKey(key: String): String? = when (credentialKindForKey(key)) {
        "password" -> "password"
        "login" -> "login"
        "otp" -> "otp"
        "csrf" -> "csrf_token"
        "code_verifier" -> "code_verifier"
        "code_challenge" -> "code_challenge"
        else -> null
    }

    private fun credentialKindForKey(key: String): String? {
        val lower = normalizeFieldName(key)
        return when {
            isPasswordField(lower) -> "password"
            isLoginField(lower) -> "login"
            isOtpField(lower) -> "otp"
            isCsrfField(lower) -> "csrf"
            lower.contains("code_verifier") -> "code_verifier"
            lower.contains("code_challenge") -> "code_challenge"
            else -> null
        }
    }

    private fun hasCredentialFields(event: JSONObject): Boolean = requestFields(event).keys.any { credentialKindForKey(it) != null }

    private fun isLoginField(key: String): Boolean {
        val lower = normalizeFieldName(key)
        return lower in setOf("login", "username", "user", "email", "phone", "mobile", "msisdn", "identifier", "account", "user_name", "userlogin", "userid", "user_id") || lower.endsWith("_login") || lower.endsWith("_email") || lower.endsWith("_phone") || lower.endsWith("_username")
    }

    private fun isPasswordField(key: String): Boolean {
        val lower = normalizeFieldName(key)
        return lower in setOf("password", "pass", "passwd", "pwd", "passcode") || lower.endsWith("_password") || lower.endsWith("_passwd")
    }

    private fun isOtpField(key: String): Boolean {
        val lower = normalizeFieldName(key)
        return lower in setOf("otp", "totp", "one_time_password", "verification_code", "sms_code", "pin", "mfa_code", "2fa_code") || lower.endsWith("_otp") || (lower.endsWith("_code") && !lower.contains("status") && !lower.contains("error"))
    }

    private fun isCsrfField(key: String): Boolean {
        val lower = normalizeFieldName(key)
        return lower.contains("csrf") || lower.contains("xsrf") || lower in setOf("_token", "authenticity_token", "requestverificationtoken", "sessid", "nonce", "state")
    }

    private fun isInterestingProducerKey(key: String): Boolean {
        val lower = normalizeFieldName(key)
        return lower.contains("token") || lower.contains("csrf") || lower.contains("xsrf") || lower.contains("session") || lower.contains("nonce") || lower in setOf("jwt", "authorization", "code", "state", "sessid")
    }

    private fun responseTokenFields(event: JSONObject): List<String> {
        val body = responseBody(event).trim()
        if (!(body.startsWith("{") || body.startsWith("["))) return emptyList()
        return try {
            val parsed: Any = if (body.startsWith("{")) JSONObject(body) else JSONArray(body)
            val out = mutableListOf<String>()
            collectTokenNames(parsed, out)
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun collectTokenNames(value: Any?, out: MutableList<String>) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (isInterestingProducerKey(key)) out.add(key)
                    collectTokenNames(value.opt(key), out)
                }
            }
            is JSONArray -> for (i in 0 until minOf(value.length(), 50)) collectTokenNames(value.opt(i), out)
        }
    }

    private fun addHeaderVariables(
        requests: List<RequestNode>,
        selected: Set<Int>,
        replacements: MutableMap<String, String>,
        variables: MutableMap<String, VariableDef>,
        used: MutableSet<String>
    ) {
        selected.sorted().forEach { index ->
            requestHeaderPairs(requests[index].event).forEach { (name, value) ->
                val lower = name.lowercase(Locale.US)
                val candidate = when {
                    lower == "authorization" && value.startsWith("Bearer ", true) -> "access_token" to value.substringAfter(' ').trim()
                    lower == "authorization" && value.isNotBlank() -> "authorization" to value
                    lower.contains("api-key") || lower == "x-api-key" -> "api_key" to value
                    lower.contains("auth-token") || lower.contains("access-token") -> "auth_token" to value
                    else -> null
                } ?: return@forEach
                val rawValue = candidate.second
                if (rawValue.isBlank() || replacements.containsKey(rawValue)) return@forEach
                val variable = uniqueVariableName(candidate.first, used)
                used.add(variable)
                replacements[rawValue] = variable
                variables[variable] = VariableDef(variable, rawValue, "Captured authentication header fallback; no producer response was detected")
            }
        }
    }

    private fun addUsedStorageVariables(
        requests: List<RequestNode>,
        selected: Set<Int>,
        before: AuthFlowAnalyzer.BrowserState,
        after: AuthFlowAnalyzer.BrowserState,
        replacements: MutableMap<String, String>,
        variables: MutableMap<String, VariableDef>,
        used: MutableSet<String>
    ) {
        val material = selected.joinToString("\n") { requestMaterial(requests[it].event) }
        listOf(after.localStorage, after.sessionStorage).forEach { store ->
            val keys = store.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = store.optString(key, "")
                if (!isReusableValue(value) || replacements.containsKey(value)) continue
                if (!materialContains(material, value) && !isInterestingProducerKey(key)) continue
                val beforeValue = before.localStorage.optString(key, before.sessionStorage.optString(key, ""))
                if (beforeValue == value && !materialContains(material, value)) continue
                val variable = uniqueVariableName(canonicalVariableName(key), used)
                used.add(variable)
                replacements[value] = variable
                variables[variable] = VariableDef(variable, value, "Captured browser-storage authentication value fallback")
            }
        }
    }

    private fun buildPostmanRequest(event: JSONObject, method: String, rawUrl: String, baseOrigin: String, replacements: Map<String, String>): JSONObject {
        val body = buildPostmanBody(event, replacements)
        val formData = body?.optString("mode", "") == "formdata"
        val headers = JSONArray()
        requestHeaderPairs(event).forEach { (name, value) ->
            val lower = name.lowercase(Locale.US)
            if (lower in setOf("cookie", "host", "content-length", "accept-encoding", "connection", "priority")) return@forEach
            if (lower.startsWith("sec-fetch-") || lower.startsWith("sec-ch-ua")) return@forEach
            if (formData && lower == "content-type" && value.contains("multipart/form-data", true)) return@forEach
            val prepared = if (lower == "authorization" && value.startsWith("Bearer ", true)) {
                val token = value.substringAfter(' ').trim()
                val variable = replacements[token]
                if (variable != null) "Bearer {{$variable}}" else applyReplacements(value, replacements)
            } else applyReplacements(value, replacements)
            headers.put(JSONObject().put("key", name).put("value", prepared).put("type", "text"))
        }
        val request = JSONObject()
            .put("method", method.ifBlank { "GET" })
            .put("header", headers)
            .put("url", portableUrl(rawUrl, baseOrigin, replacements))
            .put("description", "Reconstructed from captured AUTH traffic by web research")
        if (body != null) request.put("body", body)
        return request
    }

    private fun buildPostmanBody(event: JSONObject, replacements: Map<String, String>): JSONObject? {
        val raw = event.optString("requestBody", "").trim()
        val mime = event.optString("requestMimeType", "").lowercase(Locale.US)
        val capturedPairs = capturedFormPairs(event)
        val hintFields = event.optJSONArray("_authFormFields")
        val correlated = event.optBoolean("_authFormCorrelated", false)

        if (capturedPairs.isNotEmpty() && (correlated || mime.contains("multipart") || mime.isBlank())) {
            val data = JSONArray()
            capturedPairs.forEach { (key, value) -> data.put(JSONObject().put("key", key).put("value", applyReplacements(value, replacements)).put("type", "text")) }
            return JSONObject().put("mode", "formdata").put("formdata", data)
        }

        if (hintFields != null && hintFields.length() > 0 && mime.contains("multipart")) {
            val data = JSONArray()
            for (i in 0 until hintFields.length()) {
                val field = hintFields.optJSONObject(i) ?: continue
                val key = field.optString("name", "")
                if (key.isBlank()) continue
                data.put(JSONObject().put("key", key).put("value", applyReplacements(field.optString("value", ""), replacements)).put("type", "text"))
            }
            return JSONObject().put("mode", "formdata").put("formdata", data)
        }

        if (mime.contains("x-www-form-urlencoded") || (raw.contains('=') && !raw.startsWith("{") && !raw.startsWith("["))) {
            val data = JSONArray()
            parseUrlEncoded(raw).forEach { (key, value) -> data.put(JSONObject().put("key", key).put("value", applyReplacements(value, replacements)).put("type", "text")) }
            if (data.length() > 0) return JSONObject().put("mode", "urlencoded").put("urlencoded", data)
        }

        if (raw.isBlank() || raw in setOf("[FormData]", "[unavailable]")) return null
        val language = if (mime.contains("json") || raw.startsWith("{") || raw.startsWith("[")) "json" else "text"
        return JSONObject()
            .put("mode", "raw")
            .put("raw", applyReplacements(raw, replacements))
            .put("options", JSONObject().put("raw", JSONObject().put("language", language)))
    }

    private fun buildPostmanResponse(event: JSONObject, originalRequest: JSONObject): JSONObject? {
        val status = event.optInt("status", 0)
        val headers = responseHeaderPairs(event)
        val body = responseBody(event)
        val hasBody = body.isNotBlank() && body !in setOf("[binary]", "[non-text response]", "[unavailable]")
        if (status <= 0 && headers.isEmpty() && !hasBody) return null
        val headerArray = JSONArray()
        headers.forEach { (key, value) -> headerArray.put(JSONObject().put("key", key).put("value", value).put("type", "text")) }
        val response = JSONObject()
            .put("name", event.optString("statusText", "").ifBlank { if (status > 0) "Observed HTTP $status" else "Observed response" })
            .put("originalRequest", JSONObject(originalRequest.toString()))
            .put("status", event.optString("statusText", ""))
            .put("code", status)
            .put("header", headerArray)
            .put("cookie", JSONArray())
        if (hasBody) response.put("body", body)
        return response
    }

    private fun buildTestLines(bindings: List<Binding>): List<String> {
        val lines = mutableListOf<String>()
        bindings.distinctBy { it.variable }.forEach { binding ->
            val variable = JSONObject.quote(binding.variable)
            val producer = binding.producer
            when (producer.kind) {
                "json" -> {
                    val path = producer.path ?: return@forEach
                    lines.add("try {")
                    lines.add("  let value = pm.response.json();")
                    lines.add("  for (const key of ${JSONArray(path)}) value = value == null ? undefined : value[key];")
                    lines.add("  if (value !== undefined && value !== null) pm.collectionVariables.set($variable, String(value));")
                    lines.add("} catch (e) {}")
                }
                "header" -> {
                    val header = JSONObject.quote(producer.headerName)
                    lines.add("try { const value = pm.response.headers.get($header); if (value) pm.collectionVariables.set($variable, String(value)); } catch (e) {}")
                }
                "location_query" -> {
                    val key = JSONObject.quote(producer.headerName)
                    lines.add("try { const value = new URL(pm.response.headers.get('Location'), pm.request.url.toString()).searchParams.get($key); if (value) pm.collectionVariables.set($variable, value); } catch (e) {}")
                }
                "html_input", "html_meta", "html_assignment" -> {
                    val key = JSONObject.quote(producer.key)
                    lines.add("try {")
                    lines.add("  const text = pm.response.text();")
                    lines.add("  const name = $key.replace(/[.*+?^\${}()|[\\]\\\\]/g, '\\\\$&');")
                    lines.add("  const patterns = [")
                    lines.add("    new RegExp('<input\\\\b[^>]*\\\\bname=[\\\"\\\']' + name + '[\\\"\\\'][^>]*\\\\bvalue=[\\\"\\\']([^\\\"\\\']+)', 'i'),")
                    lines.add("    new RegExp('<input\\\\b[^>]*\\\\bvalue=[\\\"\\\']([^\\\"\\\']+)[\\\"\\\'][^>]*\\\\bname=[\\\"\\\']' + name + '[\\\"\\\']', 'i'),")
                    lines.add("    new RegExp('[\\\"\\\']?' + name + '[\\\"\\\']?\\\\s*[:=]\\\\s*[\\\"\\\']([^\\\"\\\']+)', 'i')")
                    lines.add("  ];")
                    lines.add("  for (const pattern of patterns) { const match = text.match(pattern); if (match && match[1]) { pm.collectionVariables.set($variable, match[1]); break; } }")
                    lines.add("} catch (e) {}")
                }
            }
        }
        return lines
    }

    private fun testEvent(lines: List<String>): JSONArray = JSONArray().put(
        JSONObject().put("listen", "test").put("script", JSONObject().put("type", "text/javascript").put("exec", JSONArray(lines)))
    )

    private fun roleFor(index: Int, loginIndex: Int, verifyIndex: Int?, node: RequestNode): String = when {
        index == loginIndex -> "Login"
        index == verifyIndex -> "Verify authenticated session"
        looksRefresh(node) -> "Refresh / token renewal"
        index < loginIndex -> "Prepare / auth dependency"
        hasCredentialFields(node.event) -> "Authentication step"
        else -> "Auth dependency"
    }

    private fun dedupeSelected(requests: List<RequestNode>, selected: Set<Int>): List<Int> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<Int>()
        selected.sorted().forEach { index ->
            val node = requests[index]
            val signature = listOf(node.method, normalizeUrl(node.url), eventBodyFingerprint(node.event)).joinToString("|")
            if (seen.add(signature)) out.add(index)
        }
        return out
    }

    private fun eventBodyFingerprint(event: JSONObject): String {
        val body = event.optString("requestBody", "")
        if (body.length <= 200) return body
        return body.take(100) + "#" + body.length + "#" + body.takeLast(80)
    }

    private fun shouldAddSeed(before: AuthFlowAnalyzer.BrowserState, requests: List<RequestNode>, selected: List<Int>, hints: List<FormHint>): Boolean {
        if (!before.url.startsWith("http")) return false
        if (selected.any { requests[it].method == "GET" && normalizeUrl(requests[it].url) == normalizeUrl(before.url) }) return false
        if (hasAuthPathKeyword(before.url)) return true
        return hints.any { normalizeUrl(it.page) == normalizeUrl(before.url) }
    }

    private fun isNoise(node: RequestNode, flowOrigins: Set<String>): Boolean {
        if (isStatic(node)) return true
        val origin = originOf(node.url)
        if (origin.isNotBlank() && origin !in flowOrigins && !isStrongCrossOriginAuthBridge(node)) return true
        if (isLikelyTelemetry(node) && !hasCredentialFields(node.event) && responseTokenFields(node.event).isEmpty()) return true
        return false
    }

    private fun isStatic(node: RequestNode): Boolean {
        if (node.source in setOf("resource-copy", "resource-timing", "script-archive", "source-map", "js-file")) return true
        val kind = NetworkEventClassifier.responseKind(node.event)
        if (kind in setOf("CSS", "JS", "IMG", "PDF", "BIN")) return true
        val path = try { URL(node.url).path.lowercase(Locale.US) } catch (_: Exception) { node.url.lowercase(Locale.US).substringBefore('?') }
        return listOf(".css", ".js", ".mjs", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".otf", ".map", ".mp4", ".webm", ".mp3").any(path::endsWith)
    }

    private fun isDocumentLike(node: RequestNode): Boolean {
        if (node.source == "navigation") return true
        val kind = NetworkEventClassifier.responseKind(node.event)
        return kind in setOf("HTML", "TEXT", "OTHER") && node.method == "GET"
    }

    private fun isLikelyTelemetry(node: RequestNode): Boolean {
        val lower = node.url.lowercase(Locale.US)
        val path = try { URL(lower).path } catch (_: Exception) { lower }
        val markers = listOf("/collect", "/analytics", "/telemetry", "/metrics", "/metric/", "/pixel", "/watch/", "/track", "/counter", "/beacon")
        return markers.any { path.contains(it) } || node.source == "beacon"
    }

    private fun isStrongCrossOriginAuthBridge(node: RequestNode): Boolean {
        if (isStatic(node)) return false
        val fields = requestFields(node.event)
        if (hasAuthPathKeyword(node.url) && (node.method in setOf("POST", "PUT", "PATCH") || fields.keys.any { credentialKindForKey(it) != null })) return true
        val lowerUrl = node.url.lowercase(Locale.US)
        if (listOf("client_id=", "redirect_uri=", "response_type=", "code_challenge=", "samlrequest=", "samlresponse=").any { lowerUrl.contains(it) }) return true
        if (responseTokenFields(node.event).isNotEmpty()) return true
        return requestHeaderPairs(node.event).any { (name, value) -> name.equals("Authorization", true) && value.isNotBlank() }
    }

    private fun responseLooksLikeLogin(event: JSONObject, url: String): Boolean {
        val body = responseBody(event).lowercase(Locale.US)
        if (body.isBlank()) return false
        val hasPassword = body.contains("type=\"password\"") || body.contains("type='password'") || body.contains("name=\"password\"") || body.contains("name='password'")
        val hasLoginMarker = body.contains("signin") || body.contains("login") || body.contains("username") || body.contains("authenticate")
        return hasPassword && (hasLoginMarker || hasAuthPathKeyword(url))
    }

    private fun looksRefresh(node: RequestNode): Boolean {
        val lower = node.url.lowercase(Locale.US)
        val fields = requestFields(node.event).keys.map { normalizeFieldName(it) }
        return lower.contains("refresh") || fields.any { it.contains("refresh_token") || it == "refresh" }
    }

    private fun looksLogoutOrRegistration(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return listOf("logout", "signout", "sign-out", "register", "signup", "sign-up", "create-account", "reset-password", "forgot-password").any(lower::contains)
    }

    private fun hasAuthPathKeyword(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return listOf("/auth", "/login", "/signin", "/sign-in", "/token", "/session", "/oauth", "/sso", "/verify", "/otp", "/mfa", "/2fa", "/refresh", "/callback").any(lower::contains)
    }

    private fun hasResponseEvidence(event: JSONObject): Boolean {
        if (event.optInt("status", 0) > 0) return true
        if (responseHeaderPairs(event).isNotEmpty()) return true
        val body = responseBody(event)
        return body.isNotBlank() && body !in setOf("[binary]", "[non-text response]", "[unavailable]")
    }

    private fun responseBody(event: JSONObject): String = NetworkEventClassifier.responseBodyText(event)

    private fun requestMaterial(event: JSONObject): String = buildString {
        append(event.optString("url", "")).append('\n')
        requestHeaderPairs(event).forEach { (key, value) -> append(key).append(':').append(value).append('\n') }
        append(event.optString("requestBody", ""))
    }

    private fun materialContains(material: String, value: String): Boolean {
        if (value.isBlank()) return false
        if (material.contains(value)) return true
        val encoded = try { URLEncoder.encode(value, "UTF-8").replace("+", "%20") } catch (_: Exception) { "" }
        if (encoded.isNotBlank() && material.contains(encoded, true)) return true
        val formEncoded = try { URLEncoder.encode(value, "UTF-8") } catch (_: Exception) { "" }
        return formEncoded.isNotBlank() && material.contains(formEncoded, true)
    }

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
            val split = line.indexOf(':')
            if (split <= 0) null else line.substring(0, split).trim() to line.substring(split + 1).trim()
        }
    }

    private fun parseUrlEncoded(raw: String): List<Pair<String, String>> {
        if (raw.isBlank()) return emptyList()
        return raw.split('&').filter { it.isNotBlank() }.map { part ->
            val split = part.indexOf('=')
            val key = if (split >= 0) part.substring(0, split) else part
            val value = if (split >= 0) part.substring(split + 1) else ""
            decode(key) to decode(value)
        }
    }

    private fun extractMultipartFields(raw: String): List<Pair<String, String>> {
        if (!raw.contains("Content-Disposition", true) || !raw.contains("name=", true)) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        val regex = Regex("(?is)Content-Disposition:[^\\r\\n]*name=\\\"([^\\\"]+)\\\"[^\\r\\n]*\\r?\\n(?:Content-Type:[^\\r\\n]*\\r?\\n)?\\r?\\n(.*?)(?=\\r?\\n--|$)")
        regex.findAll(raw).take(100).forEach { match -> out.add(match.groupValues[1] to match.groupValues[2].trimEnd('\r', '\n')) }
        return out
    }

    private fun parseHtmlAttributes(tag: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val regex = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*([\\\"'])(.*?)\\2", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        regex.findAll(tag).forEach { match -> out[match.groupValues[1].lowercase(Locale.US)] = match.groupValues[3] }
        return out
    }

    private fun scalarValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONObject, is JSONArray -> value.toString()
        else -> value.toString()
    }

    private fun canonicalVariableName(key: String): String {
        val lower = normalizeFieldName(key)
        return when {
            lower.contains("refresh") && lower.contains("token") -> "refresh_token"
            lower.contains("access") && lower.contains("token") -> "access_token"
            lower.contains("id_token") || lower == "idtoken" -> "id_token"
            lower.contains("csrf") || lower.contains("xsrf") || lower == "sessid" -> "csrf_token"
            lower.contains("session") && lower.contains("token") -> "session_token"
            lower == "jwt" || lower == "authorization" || lower == "token" -> "access_token"
            lower == "state" -> "oauth_state"
            lower == "code" -> "authorization_code"
            lower.contains("nonce") -> "nonce"
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
        "csrf_token" -> "Anti-CSRF value detected in the authentication flow"
        "code_verifier" -> "OAuth PKCE code_verifier"
        "code_challenge" -> "OAuth PKCE code_challenge"
        else -> "AUTH variable"
    }

    private fun looksTokenLike(value: String): Boolean {
        if (value.length < 16 || value.length > 4096 || value.contains(' ')) return false
        if (value.count { it == '.' } == 2 && value.all { it.isLetterOrDigit() || it in "-_." }) return true
        val distinct = value.toSet().size
        return value.length >= 24 && distinct >= 10 && value.all { it.isLetterOrDigit() || it in "-_=.~" }
    }

    private fun isReusableValue(value: String): Boolean {
        if (value.length < 4 || value.length > 4096) return false
        if (value.lowercase(Locale.US) in setOf("true", "false", "null", "undefined", "success", "error")) return false
        return true
    }

    private fun portableUrl(rawUrl: String, baseOrigin: String, replacements: Map<String, String>): String {
        var value = applyReplacements(rawUrl, replacements)
        if (baseOrigin.isNotBlank() && rawUrl.startsWith(baseOrigin)) value = "{{base_url}}" + value.substring(baseOrigin.length)
        return value
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

    private fun changedCookieNames(before: String, after: String): List<String> {
        val a = parseCookies(before)
        val b = parseCookies(after)
        return (a.keys + b.keys).distinct().filter { a[it] != b[it] }.sorted()
    }

    private fun changedStorageKeys(before: AuthFlowAnalyzer.BrowserState, after: AuthFlowAnalyzer.BrowserState): List<String> {
        val out = mutableListOf<String>()
        listOf("local" to (before.localStorage to after.localStorage), "session" to (before.sessionStorage to after.sessionStorage)).forEach { (prefix, stores) ->
            val keys = linkedSetOf<String>()
            stores.first.keys().forEachRemaining { keys.add(it) }
            stores.second.keys().forEachRemaining { keys.add(it) }
            keys.filter { stores.first.opt(it)?.toString() != stores.second.opt(it)?.toString() }.forEach { out.add("$prefix:$it") }
        }
        return out.sorted()
    }

    private fun parseCookies(raw: String): Map<String, String> = raw.split(';').mapNotNull { part ->
        val trimmed = part.trim()
        val split = trimmed.indexOf('=')
        if (split <= 0) null else trimmed.substring(0, split).trim() to trimmed.substring(split + 1)
    }.toMap()

    private fun normalizeFieldName(value: String): String = value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun normalizeUrl(raw: String): String = try {
        val url = URL(raw)
        val port = if (url.port > 0 && url.port != url.defaultPort) ":${url.port}" else ""
        "${url.protocol.lowercase(Locale.US)}://${url.host.lowercase(Locale.US)}$port${url.path.ifBlank { "/" }}" + if (url.query.isNullOrBlank()) "" else "?${url.query}"
    } catch (_: Exception) { raw.substringBefore('#') }

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
        val query = if (url.query.isNullOrBlank()) "" else "?" + url.query.take(80)
        val value = path + query
        if (value.length <= 90) value else "…" + value.takeLast(89)
    } catch (_: Exception) {
        if (raw.length <= 90) raw else raw.take(87) + "…"
    }
}
