package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

internal object UniversalAuthAnalyzer {
    private const val SCHEMA = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"

    private data class Node(val event: JSONObject, val source: String, val method: String, val url: String, val time: Long)
    private data class Hint(val event: JSONObject, val page: String, val url: String, val method: String, val time: Long, val fields: Map<String, String>)
    private data class Producer(val index: Int, val key: String, val value: String, val kind: String, val path: List<String>? = null, val header: String = "")
    private data class Binding(val producer: Producer, val consumer: Int, val variable: String)
    private data class VariableDef(val key: String, val value: String, val description: String)

    fun analyze(
        events: List<JSONObject>,
        before: AuthFlowAnalyzer.BrowserState,
        after: AuthFlowAnalyzer.BrowserState
    ): AuthFlowAnalyzer.Result {
        val prepared = prepare(events)
        val nodes = prepared.first
        val hints = prepared.second
        if (nodes.isEmpty()) throw IllegalStateException("За время AUTH-анализа HTTP-запросы не обнаружены")

        val scores = nodes.map(::score)
        val loginIndex = chooseLogin(nodes, scores)
        val login = nodes[loginIndex]
        val origins = linkedSetOf<String>()
        listOf(origin(before.url), origin(login.url), origin(after.url)).filter { it.isNotBlank() }.forEach(origins::add)
        nodes.forEach { node -> if (isCrossOriginAuthBridge(node)) origin(node.url).takeIf { it.isNotBlank() }?.let(origins::add) }

        val selected = linkedSetOf(loginIndex)
        addPrepareRequest(nodes, hints, loginIndex, selected, origins)

        val producers = collectProducers(nodes, hints)
        val causal = expandCausalSelection(nodes, producers, selected, origins)
        val bindings = causal.first
        val usedVariables = causal.second

        val verifyIndex: Int? = null

        val replacements = linkedMapOf<String, String>()
        val variables = linkedMapOf<String, VariableDef>()
        bindings.forEach { binding ->
            replacements.putIfAbsent(binding.producer.value, binding.variable)
            variables.putIfAbsent(binding.variable, VariableDef(binding.variable, "", "Automatically extracted from an earlier AUTH response"))
        }

        selected.sorted().forEach { index ->
            credentialValues(nodes[index].event).forEach { (raw, kind) ->
                if (raw.isBlank() || raw == "[password]" || replacements.containsKey(raw)) return@forEach
                val variable = uniqueVariable(kind, usedVariables)
                usedVariables.add(variable)
                replacements[raw] = variable
                variables.putIfAbsent(variable, VariableDef(variable, if (kind in setOf("login", "password", "otp")) "" else raw, credentialDescription(kind)))
            }
        }
        if (selected.any { index -> fields(nodes[index].event).keys.any(::isPasswordField) }) variables.putIfAbsent("password", VariableDef("password", "", credentialDescription("password")))
        addAuthHeaderVariables(nodes, selected, replacements, variables, usedVariables)
        addUsedStorageVariables(nodes, selected, before, after, replacements, variables, usedVariables)

        val chosen = dedupe(nodes, selected)
        val selectedOrigins = chosen.map { origin(nodes[it].url) }.filter { it.isNotBlank() }.toSet()
        val baseOrigin = if (selectedOrigins.size == 1) selectedOrigins.first() else ""
        if (baseOrigin.isNotBlank()) variables["base_url"] = VariableDef("base_url", baseOrigin, "Primary origin detected for this AUTH flow")
        val bindingByProducer = bindings.groupBy { it.producer.index }
        val items = JSONArray()
        var sequence = 1
        val seed = needsSeed(before, nodes, chosen, hints)
        if (seed) {
            val request = JSONObject().put("method", "GET").put("header", JSONArray()).put("url", portableUrl(before.url, baseOrigin, replacements)).put("description", "Open the captured authentication page and establish browser cookies / page state.")
            val item = JSONObject().put("name", "%02d Prepare authentication page".format(Locale.US, sequence++)).put("request", request)
            val tests = testsFor(bindingByProducer[-1].orEmpty())
            if (tests.isNotEmpty()) item.put("event", testEvent(tests))
            items.put(item)
        }

        chosen.forEach { index ->
            val node = nodes[index]
            val request = buildRequest(node.event, node.method, node.url, baseOrigin, replacements)
            val item = JSONObject()
                .put("name", "%02d %s · %s %s".format(Locale.US, sequence++, role(index, loginIndex, verifyIndex, node), node.method, compact(node.url)))
                .put("request", request)
            buildResponse(node.event, request)?.let { item.put("response", JSONArray().put(it)) }
            val tests = testsFor(bindingByProducer[index].orEmpty())
            if (tests.isNotEmpty()) item.put("event", testEvent(tests))
            items.put(item)
        }

        val changedCookies = changedCookies(before.nativeCookies, after.nativeCookies)
        val changedStorage = changedStorage(before, after)
        val realLogin = !login.event.optBoolean("_authSynthetic", false)
        val responseEvidence = hasResponse(login.event)
        val confidence = when {
            realLogin && scores[loginIndex] >= 15 && responseEvidence && (verifyIndex != null || changedCookies.isNotEmpty() || bindings.isNotEmpty()) -> "HIGH"
            scores[loginIndex] >= 8 -> "MEDIUM"
            else -> "LOW"
        }

        val notes = mutableListOf<String>()
        notes.add("Detected login candidate: ${login.method} ${login.url}")
        if (login.event.optBoolean("_authFormCorrelated", false)) notes.add("DOM form submit was correlated with the real network request")
        if (verifyIndex != null) notes.add("Session verification candidate: ${nodes[verifyIndex].method} ${nodes[verifyIndex].url}")
        if (bindings.isNotEmpty()) notes.add("Detected ${bindings.size} response → request dependencies")
        if (changedCookies.isNotEmpty()) notes.add("Cookies changed: ${changedCookies.joinToString(", ")}")
        if (changedStorage.isNotEmpty()) notes.add("Browser storage changed: ${changedStorage.joinToString(", ")}")
        if (!responseEvidence) notes.add("The selected login request has no captured HTTP response")
        if (!realLogin) notes.add("No matching network request was captured; DOM form submit is used as fallback")

        val variableArray = JSONArray()
        variables.values.forEach { variableArray.put(JSONObject().put("key", it.key).put("value", it.value).put("type", "string").put("description", it.description)) }
        val description = buildString {
            append("Generated by web research universal AUTH analyzer.\nConfidence: ").append(confidence).append(".\n")
            notes.forEach { append("- ").append(it).append('\n') }
            append("\nCaptured Cookie request headers are removed. Postman's cookie jar is expected to retain cookies issued by the server.")
        }.trim()
        val collection = JSONObject()
            .put("info", JSONObject().put("_postman_id", UUID.randomUUID().toString()).put("name", "web research · AUTH · ${host(login.url).ifBlank { "site" }}").put("description", description).put("schema", SCHEMA))
            .put("item", items)
            .put("variable", variableArray)

        return AuthFlowAnalyzer.Result(collection.toString(2), confidence, items.length(), login.url, notes)
    }

    private fun prepare(events: List<JSONObject>): Pair<List<Node>, List<Hint>> {
        val hints = events.filter { it.optString("source", "") == "auth-form-submit" }.map { event ->
            Hint(JSONObject(event.toString()), event.optString("page", ""), event.optString("url", ""), event.optString("method", "POST").uppercase(Locale.US), event.optLong("time", 0L), hintFields(event))
        }.sortedBy { it.time }

        val real = NetworkDisplayMerger.merge(events.map { JSONObject(it.toString()) })
            .filter { it.optString("source", "") != "auth-form-submit" && NetworkEventClassifier.isPlainRequestEvent(it) }
            .filter { it.optString("url", "").startsWith("http://") || it.optString("url", "").startsWith("https://") }
            .sortedBy { it.optLong("time", 0L) }
            .toMutableList()

        events.filter { it.optString("source", "") == "auth-page-source" }.forEach { page ->
            val pageUrl = page.optString("url", "")
            val pageTime = page.optLong("time", 0L)
            val content = page.optString("content", "")
            if (!pageUrl.startsWith("http") || content.isBlank()) return@forEach

            val candidate = real.withIndex()
                .filter { entry ->
                    val event = entry.value
                    val method = NetworkEventClassifier.methodOf(event).ifBlank { event.optString("method", "GET") }
                    val eventTime = event.optLong("time", 0L)
                    method.equals("GET", true) &&
                        normalizeUrl(event.optString("url", "")) == normalizeUrl(pageUrl) &&
                        (pageTime <= 0L || eventTime <= 0L || abs(pageTime - eventTime) <= 8000L) &&
                        !event.optBoolean("_authPageSourceAttached", false)
                }
                .minByOrNull { entry ->
                    val eventTime = entry.value.optLong("time", 0L)
                    if (pageTime > 0L && eventTime > 0L) abs(pageTime - eventTime) else Long.MAX_VALUE
                }

            if (candidate != null) {
                val target = candidate.value
                target.put("_authPageSourceAttached", true)
                target.put("_authPageSourceTime", pageTime)
                if (target.optString("responseBody", "").isBlank()) target.put("responseBody", content)
                if (target.optString("mimeType", "").isBlank()) target.put("mimeType", "text/html; charset=utf-8")
            } else {
                real.add(
                    JSONObject()
                        .put("source", "auth-page-source")
                        .put("time", pageTime)
                        .put("method", "GET")
                        .put("url", pageUrl)
                        .put("status", 200)
                        .put("mimeType", "text/html; charset=utf-8")
                        .put("responseBody", content)
                        .put("_authPageSourceSynthetic", true)
                )
            }
        }

        hints.forEach { hint ->
            val candidate = real.withIndex().mapNotNull { entry ->
                val event = entry.value
                val delta = abs(event.optLong("time", 0L) - hint.time)
                if (hint.time > 0L && event.optLong("time", 0L) > 0L && delta > 8000L) return@mapNotNull null
                val matchScore = correlationScore(hint, event)
                if (matchScore <= 0) null else entry.index to matchScore
            }.maxByOrNull { it.second }
            if (candidate != null && candidate.second >= 8) {
                val target = real[candidate.first]
                target.put("_authFormCorrelated", true)
                target.put("_authFormPage", hint.page)
                target.put("_authFormFields", hint.event.optJSONArray("formFields") ?: JSONArray())
                if (target.optString("requestBody", "").isBlank()) target.put("requestBody", hint.event.optString("requestBody", ""))
                if (target.optString("requestMimeType", "").isBlank()) target.put("requestMimeType", hint.event.optString("requestMimeType", ""))
            } else real.add(JSONObject(hint.event.toString()).put("_authSynthetic", true))
        }

        return real.sortedBy { it.optLong("time", 0L) }.map { event ->
            Node(event, event.optString("source", ""), NetworkEventClassifier.methodOf(event).ifBlank { event.optString("method", "GET") }.uppercase(Locale.US), event.optString("url", ""), event.optLong("time", 0L))
        } to hints
    }

    private fun correlationScore(hint: Hint, event: JSONObject): Int {
        val targetUrl = event.optString("url", "")
        val targetFields = fields(event)
        val hintNames = hint.fields.keys.map(::normalizeField).toSet()
        val targetNames = targetFields.keys.map(::normalizeField).toSet()
        val sharedNames = hintNames.intersect(targetNames)
        val hintKinds = hint.fields.keys.mapNotNull(::credentialKind).toSet()
        val targetKinds = targetFields.keys.mapNotNull(::credentialKind).toSet()
        val sharedKinds = hintKinds.intersect(targetKinds)
        val sharedValues = hint.fields.values.filter { it.isNotBlank() && it !in setOf("[password]", "[file]") }.any { value -> targetFields.values.any { it == value } }
        val sameUrl = normalizeUrl(targetUrl) == normalizeUrl(hint.url)
        if (!(sameUrl || sharedKinds.isNotEmpty() || sharedNames.size >= 2 || sharedValues)) return 0
        var score = 0
        if (sameUrl) score += 6
        if (origin(targetUrl).isNotBlank() && origin(targetUrl) == origin(hint.page.ifBlank { hint.url })) score += 2
        val method = NetworkEventClassifier.methodOf(event).ifBlank { event.optString("method", "GET") }.uppercase(Locale.US)
        if (method == hint.method) score += 2
        if (event.optString("source", "") in setOf("fetch", "xhr", "replay")) score += 5
        score += sharedKinds.size * 6 + minOf(sharedNames.size, 5) * 2
        if (sharedValues) score += 4
        score += when (abs(event.optLong("time", 0L) - hint.time)) { in 0L..1000L -> 4; in 1001L..3000L -> 3; in 3001L..8000L -> 1; else -> 0 }
        if (hasResponse(event)) score += 2
        return score
    }

    private fun chooseLogin(nodes: List<Node>, scores: List<Int>): Int {
        val real = scores.indices.filter { !nodes[it].event.optBoolean("_authSynthetic", false) && !isTelemetry(nodes[it]) && !isStatic(nodes[it]) }
        val password = real.filter { index -> fields(nodes[index].event).keys.any(::isPasswordField) }
        if (password.isNotEmpty()) return password.maxByOrNull { scores[it] + if (nodes[it].event.optBoolean("_authFormCorrelated", false)) 8 else 0 } ?: password.first()

        val otp = real.filter { index -> fields(nodes[index].event).keys.any(::isOtpField) }
        if (otp.isNotEmpty()) return otp.maxByOrNull { scores[it] } ?: otp.first()

        val identity = real.filter { index ->
            val keys = fields(nodes[index].event).keys
            nodes[index].method in setOf("POST", "PUT", "PATCH") && keys.any(::isLoginField)
        }
        if (identity.isNotEmpty()) return identity.maxByOrNull { scores[it] } ?: identity.first()

        val bestReal = real.maxByOrNull { scores[it] }
        if (bestReal != null && scores[bestReal] >= 5) return bestReal
        return scores.indices.maxByOrNull { scores[it] } ?: 0
    }

    private fun score(node: Node): Int {
        val keys = fields(node.event).keys
        var value = 0
        if (authPath(node.url)) value += 5
        if (node.method in setOf("POST", "PUT", "PATCH")) value += 3
        if (keys.any(::isLoginField)) value += 7
        if (keys.any(::isPasswordField)) value += 12
        if (keys.any(::isOtpField)) value += 6
        if (keys.any(::isCsrfField)) value += 2
        if (responseTokenKeys(node.event).isNotEmpty()) value += 5
        if (responseHeaders(node.event).any { it.first.equals("Set-Cookie", true) }) value += 4
        if (hasResponse(node.event)) value += 4
        if (node.source in setOf("fetch", "xhr", "replay")) value += 4
        if (node.event.optBoolean("_authFormCorrelated", false)) value += 6
        if (node.event.optBoolean("_authSynthetic", false)) value -= 8
        if (logoutOrRegistration(node.url)) value -= 12
        if (isStatic(node)) value -= 30
        if (isTelemetry(node) && !hasCredentials(node.event)) value -= 18
        return value
    }

    private fun isExplicitAuthStep(node: Node): Boolean {
        if (node.event.optBoolean("_authFormCorrelated", false)) return true
        if (logoutOrRegistration(node.url) || isStatic(node) || isTelemetry(node)) return false
        if (authPath(node.url) || authOperation(node)) return true
        val kinds = fields(node.event).keys.mapNotNull(::credentialKind).toSet()
        if (kinds.any { it in setOf("login", "password", "otp", "code_verifier", "code_challenge") }) return true
        if (looksRefresh(node)) return true
        return false
    }

    private fun authOperation(node: Node): Boolean {
        val query = try { URL(node.url).query.orEmpty() } catch (_: Exception) { "" }
        if (query.isBlank()) return false
        val operationKeys = setOf("act", "action", "method", "operation", "op", "flow", "step")
        return parseUrlEncoded(query).any { (key, value) ->
            normalizeField(key) in operationKeys && authWord(value)
        }
    }

    private fun authWord(value: String): Boolean {
        val lower = normalizeField(value)
        return listOf("auth", "login", "signin", "sign_in", "authorize", "token", "session", "sso", "verify", "password", "otp", "mfa", "2fa", "refresh").any(lower::contains)
    }

    private fun expandCausalSelection(
        nodes: List<Node>,
        producers: List<Producer>,
        selected: MutableSet<Int>,
        origins: Set<String>
    ): Pair<MutableList<Binding>, LinkedHashSet<String>> {
        val bindings = mutableListOf<Binding>()
        val usedVariables = linkedSetOf<String>()
        val variableByValue = linkedMapOf<String, String>()
        var changed = true
        var pass = 0

        while (changed && pass++ < 40) {
            changed = false

            selected.toList().sorted().forEach { consumer ->
                val material = requestMaterial(nodes[consumer].event)
                val candidates = producers.asSequence()
                    .filter { it.index < consumer && reusable(it.value) && containsMaterial(material, it.value) }
                    .filter { it.index < 0 || !isNoise(nodes[it.index], origins) }
                    .groupBy { it.value }

                candidates.values.forEach { options ->
                    val producer = options.maxByOrNull { it.index } ?: return@forEach
                    if (bindings.none { it.consumer == consumer && it.producer.value == producer.value }) {
                        val variable = variableByValue[producer.value] ?: run {
                            val created = uniqueVariable(canonicalVariable(producer.key), usedVariables)
                            usedVariables.add(created)
                            variableByValue[producer.value] = created
                            created
                        }
                        bindings.add(Binding(producer, consumer, variable))
                    }
                    if (producer.index >= 0 && selected.add(producer.index)) changed = true
                }
            }

            selected.toList().sorted().forEach { sourceIndex ->
                val source = nodes[sourceIndex]
                val limitTime = if (source.time > 0L) source.time + 120_000L else Long.MAX_VALUE

                producers.asSequence()
                    .filter { it.index == sourceIndex && reusable(it.value) }
                    .forEach { producer ->
                        for (index in sourceIndex + 1..nodes.lastIndex) {
                            val candidate = nodes[index]
                            if (candidate.time > 0L && candidate.time > limitTime) break
                            if (isNoise(candidate, origins) || !isExplicitAuthStep(candidate)) continue
                            if (containsMaterial(requestMaterial(candidate.event), producer.value) || stepTransitionMatches(producer, candidate)) {
                                if (selected.add(index)) changed = true
                            }
                        }
                    }

                val navigationIndex = findNextNavigationTarget(nodes, sourceIndex, navigationTargets(source), origins)
                if (navigationIndex != null && selected.add(navigationIndex)) changed = true
            }
        }

        return bindings to usedVariables
    }

    private fun stepTransitionMatches(producer: Producer, node: Node): Boolean {
        val key = normalizeField(producer.key)
        if (!(key.contains("next") || key.contains("step") || key.contains("flow") || key.contains("action"))) return false
        val value = normalizeField(producer.value)
        if (value.length < 6 || value.length > 100) return false
        val route = normalizeField(try { URL(node.url).path } catch (_: Exception) { node.url.substringBefore('?') })
        return route.contains(value) || value.contains(route.takeIf { it.length >= 6 } ?: return false)
    }

    private fun findNextNavigationTarget(nodes: List<Node>, sourceIndex: Int, targets: List<String>, origins: Set<String>): Int? {
        if (targets.isEmpty() || sourceIndex >= nodes.lastIndex) return null
        val sourceTime = nodes[sourceIndex].time
        val limitTime = if (sourceTime > 0L) sourceTime + 120_000L else Long.MAX_VALUE
        var best: Int? = null
        for (index in sourceIndex + 1..nodes.lastIndex) {
            val candidate = nodes[index]
            if (candidate.time > 0L && candidate.time > limitTime) break
            if (isNoise(candidate, origins)) continue
            if (targets.none { target -> navigationTargetMatches(target, candidate.url) }) continue
            if (best == null || candidate.time <= 0L || nodes[best!!].time <= 0L || candidate.time < nodes[best!!].time) best = index
        }
        return best
    }

    private fun navigationTargetMatches(target: String, actual: String): Boolean {
        if (target.isBlank() || actual.isBlank()) return false
        if (normalizeUrl(target) == normalizeUrl(actual)) return true
        return routeKey(target).isNotBlank() && routeKey(target) == routeKey(actual)
    }

    private fun routeKey(raw: String): String = try {
        val url = URL(raw)
        val port = if (url.port > 0 && url.port != url.defaultPort) ":${url.port}" else ""
        "${url.protocol.lowercase(Locale.US)}://${url.host.lowercase(Locale.US)}$port${url.path.ifBlank { "/" }}"
    } catch (_: Exception) {
        raw.substringBefore('?').substringBefore('#')
    }

    private fun addPrepareRequest(nodes: List<Node>, hints: List<Hint>, loginIndex: Int, selected: MutableSet<Int>, origins: Set<String>) {
        val first = selected.minOrNull() ?: loginIndex
        val hintPages = hints.map { normalizeUrl(it.page) }.filter { it.isNotBlank() }.toSet()
        val candidate = (0 until first).reversed().firstOrNull { index ->
            val node = nodes[index]
            if (node.method != "GET" || isNoise(node, origins) || !documentLike(node)) return@firstOrNull false
            val close = node.time <= 0L || nodes[loginIndex].time <= 0L || nodes[loginIndex].time - node.time <= 120_000L
            close && (normalizeUrl(node.url) in hintPages || authPath(node.url) || node.source == "navigation")
        }
        if (candidate != null) selected.add(candidate)
    }

    private fun chooseVerify(nodes: List<Node>, loginIndex: Int, before: AuthFlowAnalyzer.BrowserState, after: AuthFlowAnalyzer.BrowserState, origins: Set<String>): Int? {
        if (loginIndex >= nodes.lastIndex) return null
        val finalUrl = normalizeUrl(after.url)
        val limit = minOf(nodes.lastIndex, loginIndex + 30)

        for (index in loginIndex + 1..limit) {
            val node = nodes[index]
            if (isNoise(node, origins) || isStatic(node)) continue
            val status = node.event.optInt("status", 0)
            val body = responseBody(node.event)
            val sources = NetworkEventClassifier.eventSources(node.event)
            val apiLike = sources.any { it in setOf("fetch", "xhr", "replay") }
            val nonGet = node.method in setOf("POST", "PUT", "PATCH", "DELETE")
            val usableBody = body.isNotBlank() && body !in setOf("[binary]", "[non-text response]", "[unavailable]")
            if (
                origin(node.url) in origins &&
                apiLike &&
                nonGet &&
                status in 200..399 &&
                usableBody &&
                body.length >= 80 &&
                !looksLikeLoginResponse(node.event, node.url) &&
                !authPath(node.url)
            ) {
                return index
            }
        }

        var best: Int? = null
        var bestScore = Int.MIN_VALUE
        for (index in loginIndex + 1..limit) {
            val node = nodes[index]
            if (isNoise(node, origins) || isStatic(node)) continue
            var value = if (origin(node.url) in origins) 5 else -10
            val status = node.event.optInt("status", 0)
            val body = responseBody(node.event)
            val requestBody = node.event.optString("requestBody", "")
            val sources = NetworkEventClassifier.eventSources(node.event)
            val apiLike = sources.any { it in setOf("fetch", "xhr", "replay") }
            val nonGet = node.method in setOf("POST", "PUT", "PATCH", "DELETE")

            if (status in 200..399) value += 5
            if (apiLike) value += 7
            if (nonGet) value += 8
            if (requestBody.isNotBlank() && requestBody !in setOf("[FormData]", "[unavailable]")) value += 3
            if (body.isNotBlank() && body !in setOf("[binary]", "[non-text response]", "[unavailable]")) {
                value += when {
                    body.length >= 500 -> 5
                    body.length >= 100 -> 4
                    body.length >= 20 -> 3
                    else -> 1
                }
            }
            if (finalUrl.isNotBlank() && normalizeUrl(node.url) == finalUrl) value += 7
            if (node.source == "navigation") value += 3
            if (node.method == "GET") value += 1
            if (requestHeaders(node.event).any { it.first.equals("Authorization", true) && it.second.isNotBlank() }) value += 6
            if (looksLikeLoginResponse(node.event, node.url)) value -= 20
            if (authPath(node.url) && normalizeUrl(node.url) != finalUrl) value -= 5
            if (normalizeUrl(before.url) == normalizeUrl(node.url) && normalizeUrl(before.url) != finalUrl) value -= 3

            if (value > bestScore || (value == bestScore && best != null && index < best)) {
                bestScore = value
                best = index
            }
        }
        return if (bestScore >= 8) best else null
    }

    private fun navigationTargets(node: Node): List<String> {
        val out = linkedSetOf<String>()

        fun resolve(raw: String): String? {
            var value = raw.trim().trim('"', '\'', ' ')
            value = value.replace("&amp;", "&").replace("&#38;", "&")
            if (value.isBlank()) return null
            repeat(2) { value = decode(value) }
            return try {
                val resolved = URL(URL(node.url), value).toString()
                if (resolved.startsWith("http://") || resolved.startsWith("https://")) resolved else null
            } catch (_: Exception) {
                null
            }
        }

        fun add(raw: String) {
            resolve(raw)?.let(out::add)
        }

        fun scanDeclared(rawUrl: String, depth: Int) {
            if (depth > 2) return
            val url = try { URL(rawUrl) } catch (_: Exception) { return }
            val allowed = setOf("redirect_uri", "redirect_url", "return_url", "return", "continue", "continue_url", "next", "next_url", "callback", "callback_url", "from", "to")
            parseUrlEncoded(url.query.orEmpty()).forEach { (key, rawValue) ->
                if (normalizeField(key) !in allowed) return@forEach
                var value = rawValue
                repeat(2) { value = decode(value) }
                val resolved = resolve(value) ?: return@forEach
                out.add(resolved)
                scanDeclared(resolved, depth + 1)
            }
        }

        node.event.optString("redirectURL", "").takeIf { it.isNotBlank() }?.let(::add)
        responseHeaders(node.event).firstOrNull { it.first.equals("Location", true) }?.second?.let(::add)

        val body = responseBody(node.event)
        if (body.isNotBlank() && body.length <= 2_000_000) {
            if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) {
                try {
                    val parsed: Any = if (body.trimStart().startsWith("{")) JSONObject(body) else JSONArray(body)
                    collectNavigationJson(parsed, node.url, out)
                } catch (_: Exception) {}
            }

            Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(body).take(100).forEach { match ->
                val attrs = htmlAttributes(match.value)
                if (!attrs["http-equiv"].orEmpty().equals("refresh", true)) return@forEach
                val content = attrs["content"].orEmpty()
                val target = Regex("(?i)url\\s*=\\s*['\"]?([^'\";]+)").find(content)?.groupValues?.getOrNull(1).orEmpty()
                if (target.isNotBlank()) add(target)
            }

            Regex("(?i)(?:window\\.)?location(?:\\.href)?\\s*=\\s*['\"]([^'\"]+)['\"]").findAll(body).take(50).forEach { add(it.groupValues[1]) }
            Regex("(?i)\\b(?:redirect|next|return|continue)[A-Za-z0-9_]*URL\\s*=\\s*['\"]([^'\"]+)['\"]").findAll(body).take(50).forEach { add(it.groupValues[1]) }
        }

        scanDeclared(node.url, 0)
        return out.toList()
    }

    private fun collectNavigationJson(value: Any?, baseUrl: String, out: MutableSet<String>) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (child != null && child != JSONObject.NULL && navigationKey(key)) {
                        resolveNavigationUrl(baseUrl, child.toString())?.let(out::add)
                    }
                    collectNavigationJson(child, baseUrl, out)
                }
            }
            is JSONArray -> for (index in 0 until minOf(value.length(), 100)) collectNavigationJson(value.opt(index), baseUrl, out)
        }
    }

    private fun navigationKey(key: String): Boolean {
        val value = normalizeField(key)
        return value in setOf("location", "redirect", "redirect_url", "redirect_uri", "return_url", "continue_url", "callback_url", "next_url", "next_step_url") ||
            ((value.contains("redirect") || value.contains("next") || value.contains("callback") || value.contains("continue") || value.contains("return")) && (value.contains("url") || value.contains("uri")))
    }

    private fun resolveNavigationUrl(baseUrl: String, raw: String): String? {
        var value = raw.trim().trim('"', '\'', ' ').replace("&amp;", "&").replace("&#38;", "&")
        repeat(2) { value = decode(value) }
        return try {
            val resolved = URL(URL(baseUrl), value).toString()
            if (resolved.startsWith("http://") || resolved.startsWith("https://")) resolved else null
        } catch (_: Exception) {
            null
        }
    }

    private fun collectProducers(nodes: List<Node>, hints: List<Hint>): List<Producer> {
        val out = mutableListOf<Producer>()
        hints.forEach { hint -> hint.fields.forEach { (key, value) -> if (value.isNotBlank() && value !in setOf("[password]", "[file]") && (interestingKey(key) || tokenLike(value))) out.add(Producer(-1, key, value, "html")) } }
        nodes.forEachIndexed { index, node ->
            val body = responseBody(node.event).trim()
            if (body.startsWith("{") || body.startsWith("[")) try {
                val parsed: Any = if (body.startsWith("{")) JSONObject(body) else JSONArray(body)
                collectJson(parsed, emptyList(), index, out)
            } catch (_: Exception) {}
            if (body.isNotBlank() && body.length <= 2_000_000) {
                Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(body).take(100).forEach { match ->
                    val attrs = htmlAttributes(match.value)
                    val key = attrs["name"].orEmpty()
                    val value = attrs["value"].orEmpty()
                    if (key.isNotBlank() && value.isNotBlank() && (interestingKey(key) || tokenLike(value))) out.add(Producer(index, key, value, "html"))
                }
            }
            responseHeaders(node.event).forEach { (name, value) ->
                val lower = name.lowercase(Locale.US)
                if (lower == "set-cookie") return@forEach
                if (lower == "location") try {
                    val location = URL(URL(node.url), value)
                    parseUrlEncoded(location.query.orEmpty()).forEach { (key, queryValue) -> if (interestingKey(key) && reusable(queryValue)) out.add(Producer(index, key, queryValue, "location", header = key)) }
                } catch (_: Exception) {}
                else if ((lower.contains("token") || lower.contains("csrf") || lower.contains("xsrf") || lower == "authorization") && reusable(value)) out.add(Producer(index, name, value, "header", header = name))
            }
        }
        return out.distinctBy { "${it.index}|${it.key}|${it.value}|${it.kind}" }
    }

    private fun collectJson(value: Any?, path: List<String>, index: Int, out: MutableList<Producer>) {
        when (value) {
            is JSONObject -> { val keys = value.keys(); while (keys.hasNext()) { val key = keys.next(); collectJson(value.opt(key), path + key, index, out) } }
            is JSONArray -> for (i in 0 until minOf(value.length(), 100)) collectJson(value.opt(i), path + i.toString(), index, out)
            null, JSONObject.NULL -> Unit
            else -> if (path.isNotEmpty()) { val text = value.toString(); val key = path.last(); if (interestingKey(key) || tokenLike(text)) out.add(Producer(index, key, text, "json", path)) }
        }
    }

    private fun fields(event: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val raw = event.optString("requestBody", "").trim()
        if (raw.startsWith("{")) try { flatten(JSONObject(raw), emptyList(), out) } catch (_: Exception) {}
        else if (raw.startsWith("[")) try { val array = JSONArray(raw); if (!pairArray(array, out)) flatten(array, emptyList(), out) } catch (_: Exception) {}
        else if (raw.isNotBlank() && raw !in setOf("[FormData]", "[unavailable]")) parseUrlEncoded(raw).forEach { out[it.first] = it.second }
        try { parseUrlEncoded(URL(event.optString("url", "")).query.orEmpty()).forEach { out.putIfAbsent(it.first, it.second) } } catch (_: Exception) {}
        event.optJSONArray("_authFormFields")?.let { array -> for (i in 0 until array.length()) { val field = array.optJSONObject(i) ?: continue; val name = field.optString("name", ""); if (name.isNotBlank()) out.putIfAbsent(name, field.optString("value", "")) } }
        return out
    }

    private fun pairArray(array: JSONArray, out: MutableMap<String, String>): Boolean {
        if (array.length() == 0) return false
        var count = 0
        for (i in 0 until minOf(array.length(), 200)) {
            val item = array.opt(i)
            if (item is JSONArray && item.length() >= 2 && item.optString(0, "").isNotBlank()) { out[item.optString(0)] = scalar(item.opt(1)); count++ }
            else if (item is JSONObject) { val key = item.optString("name", item.optString("key", "")); if (key.isBlank() || !item.has("value")) return false; out[key] = scalar(item.opt("value")); count++ }
            else return false
        }
        return count > 0
    }

    private fun capturedPairs(event: JSONObject): List<Pair<String, String>> {
        val raw = event.optString("requestBody", "").trim()
        if (!raw.startsWith("[")) return emptyList()
        return try { val out = linkedMapOf<String, String>(); if (pairArray(JSONArray(raw), out)) out.entries.map { it.key to it.value } else emptyList() } catch (_: Exception) { emptyList() }
    }

    private fun flatten(value: Any?, path: List<String>, out: MutableMap<String, String>) {
        when (value) {
            is JSONObject -> { val keys = value.keys(); while (keys.hasNext()) { val key = keys.next(); flatten(value.opt(key), path + key, out) } }
            is JSONArray -> for (i in 0 until minOf(value.length(), 100)) flatten(value.opt(i), path + i.toString(), out)
            null, JSONObject.NULL -> Unit
            else -> if (path.isNotEmpty()) out[path.last()] = value.toString()
        }
    }

    private fun hintFields(event: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val array = event.optJSONArray("formFields") ?: return out
        for (i in 0 until array.length()) { val field = array.optJSONObject(i) ?: continue; val name = field.optString("name", ""); if (name.isNotBlank()) out[name] = field.optString("value", "") }
        return out
    }

    private fun credentialValues(event: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        fields(event).forEach { (key, value) -> credentialKind(key)?.let { kind -> if (value.isNotBlank()) out[value] = if (kind == "csrf") "csrf_token" else kind } }
        return out
    }

    private fun credentialKind(key: String): String? {
        val value = normalizeField(key)
        return when {
            isPasswordField(value) -> "password"
            isLoginField(value) -> "login"
            isOtpField(value) -> "otp"
            value == "state" -> "oauth_state"
            value.contains("code_verifier") -> "code_verifier"
            value.contains("code_challenge") -> "code_challenge"
            isCsrfField(value) -> "csrf"
            else -> null
        }
    }

    private fun hasCredentials(event: JSONObject): Boolean = fields(event).keys.any { credentialKind(it) != null }
    private fun isLoginField(key: String): Boolean { val value = normalizeField(key); return value in setOf("login", "username", "user", "email", "phone", "mobile", "msisdn", "identifier", "account", "user_name", "userlogin", "userid", "user_id") || value.endsWith("_login") || value.endsWith("_email") || value.endsWith("_phone") || value.endsWith("_username") }
    private fun isPasswordField(key: String): Boolean { val value = normalizeField(key); return value in setOf("password", "pass", "passwd", "pwd", "passcode") || value.endsWith("_password") || value.endsWith("_passwd") }
    private fun isOtpField(key: String): Boolean { val value = normalizeField(key); return value in setOf("otp", "totp", "one_time_password", "verification_code", "sms_code", "pin", "mfa_code", "2fa_code") || value.endsWith("_otp") || (value.endsWith("_code") && !value.contains("status") && !value.contains("error")) }
    private fun isCsrfField(key: String): Boolean { val value = normalizeField(key); return value.contains("csrf") || value.contains("xsrf") || value in setOf("_token", "authenticity_token", "requestverificationtoken", "sessid", "nonce", "state") }
    private fun interestingKey(key: String): Boolean { val value = normalizeField(key); return value.contains("token") || value.contains("csrf") || value.contains("xsrf") || value.contains("session") || value.contains("nonce") || value in setOf("jwt", "authorization", "code", "state", "sessid") }

    private fun responseTokenKeys(event: JSONObject): List<String> {
        val body = responseBody(event).trim()
        if (!(body.startsWith("{") || body.startsWith("["))) return emptyList()
        return try { val out = mutableListOf<String>(); collectTokenKeys(if (body.startsWith("{")) JSONObject(body) else JSONArray(body), out); out } catch (_: Exception) { emptyList() }
    }

    private fun collectTokenKeys(value: Any?, out: MutableList<String>) {
        when (value) {
            is JSONObject -> { val keys = value.keys(); while (keys.hasNext()) { val key = keys.next(); if (interestingKey(key)) out.add(key); collectTokenKeys(value.opt(key), out) } }
            is JSONArray -> for (i in 0 until minOf(value.length(), 50)) collectTokenKeys(value.opt(i), out)
        }
    }

    private fun addAuthHeaderVariables(nodes: List<Node>, selected: Set<Int>, replacements: MutableMap<String, String>, variables: MutableMap<String, VariableDef>, used: MutableSet<String>) {
        selected.forEach { index -> requestHeaders(nodes[index].event).forEach { (name, value) ->
            val lower = name.lowercase(Locale.US)
            val pair = when {
                lower == "authorization" && value.startsWith("Bearer ", true) -> "access_token" to value.substringAfter(' ').trim()
                lower == "authorization" && value.isNotBlank() -> "authorization" to value
                lower.contains("api-key") || lower == "x-api-key" -> "api_key" to value
                lower.contains("auth-token") || lower.contains("access-token") -> "auth_token" to value
                else -> null
            } ?: return@forEach
            if (pair.second.isBlank() || replacements.containsKey(pair.second)) return@forEach
            val variable = uniqueVariable(pair.first, used); used.add(variable); replacements[pair.second] = variable; variables[variable] = VariableDef(variable, pair.second, "Captured authentication header fallback")
        } }
    }

    private fun addUsedStorageVariables(nodes: List<Node>, selected: Set<Int>, before: AuthFlowAnalyzer.BrowserState, after: AuthFlowAnalyzer.BrowserState, replacements: MutableMap<String, String>, variables: MutableMap<String, VariableDef>, used: MutableSet<String>) {
        val material = selected.joinToString("\n") { requestMaterial(nodes[it].event) }
        listOf(after.localStorage, after.sessionStorage).forEach { store ->
            val keys = store.keys()
            while (keys.hasNext()) {
                val key = keys.next(); val value = store.optString(key, "")
                if (!reusable(value) || replacements.containsKey(value) || (!containsMaterial(material, value) && !interestingKey(key))) continue
                val old = before.localStorage.optString(key, before.sessionStorage.optString(key, ""))
                if (old == value && !containsMaterial(material, value)) continue
                val variable = uniqueVariable(canonicalVariable(key), used); used.add(variable); replacements[value] = variable; variables[variable] = VariableDef(variable, value, "Captured browser-storage authentication value fallback")
            }
        }
    }

    private fun buildRequest(event: JSONObject, method: String, rawUrl: String, baseOrigin: String, replacements: Map<String, String>): JSONObject {
        val body = buildBody(event, replacements)
        val formData = body?.optString("mode", "") == "formdata"
        val headers = JSONArray()
        requestHeaders(event).forEach { (name, value) ->
            val lower = name.lowercase(Locale.US)
            if (lower in setOf("cookie", "host", "content-length", "accept-encoding", "connection", "priority") || lower.startsWith("sec-fetch-") || lower.startsWith("sec-ch-ua")) return@forEach
            if (formData && lower == "content-type" && value.contains("multipart/form-data", true)) return@forEach
            val prepared = if (lower == "authorization" && value.startsWith("Bearer ", true)) {
                val token = value.substringAfter(' ').trim(); replacements[token]?.let { "Bearer {{$it}}" } ?: replace(value, replacements)
            } else replace(value, replacements)
            headers.put(JSONObject().put("key", name).put("value", prepared).put("type", "text"))
        }
        val request = JSONObject().put("method", method.ifBlank { "GET" }).put("header", headers).put("url", portableUrl(rawUrl, baseOrigin, replacements)).put("description", "Reconstructed from captured AUTH traffic by web research")
        if (body != null) request.put("body", body)
        return request
    }

    private fun buildBody(event: JSONObject, replacements: Map<String, String>): JSONObject? {
        val raw = event.optString("requestBody", "").trim()
        val mime = event.optString("requestMimeType", "").lowercase(Locale.US)
        val pairs = capturedPairs(event)
        if (pairs.isNotEmpty() && (event.optBoolean("_authFormCorrelated", false) || mime.contains("multipart") || mime.isBlank())) {
            val data = JSONArray(); pairs.forEach { data.put(JSONObject().put("key", it.first).put("value", replace(it.second, replacements)).put("type", "text")) }
            return JSONObject().put("mode", "formdata").put("formdata", data)
        }
        val hint = event.optJSONArray("_authFormFields")
        if (hint != null && hint.length() > 0 && mime.contains("multipart")) {
            val data = JSONArray(); for (i in 0 until hint.length()) { val field = hint.optJSONObject(i) ?: continue; val key = field.optString("name", ""); if (key.isNotBlank()) data.put(JSONObject().put("key", key).put("value", replace(field.optString("value", ""), replacements)).put("type", "text")) }
            return JSONObject().put("mode", "formdata").put("formdata", data)
        }
        if (mime.contains("x-www-form-urlencoded") || (raw.contains('=') && !raw.startsWith("{") && !raw.startsWith("["))) {
            val data = JSONArray(); parseUrlEncoded(raw).forEach { data.put(JSONObject().put("key", it.first).put("value", replace(it.second, replacements)).put("type", "text")) }
            if (data.length() > 0) return JSONObject().put("mode", "urlencoded").put("urlencoded", data)
        }
        if (raw.isBlank() || raw in setOf("[FormData]", "[unavailable]")) return null
        return JSONObject().put("mode", "raw").put("raw", replace(raw, replacements)).put("options", JSONObject().put("raw", JSONObject().put("language", if (mime.contains("json") || raw.startsWith("{") || raw.startsWith("[")) "json" else "text")))
    }

    private fun buildResponse(event: JSONObject, request: JSONObject): JSONObject? {
        val status = event.optInt("status", 0); val headers = responseHeaders(event); val body = responseBody(event)
        val usableBody = body.isNotBlank() && body !in setOf("[binary]", "[non-text response]", "[unavailable]")
        if (status <= 0 && headers.isEmpty() && !usableBody) return null
        val headerArray = JSONArray(); headers.forEach { headerArray.put(JSONObject().put("key", it.first).put("value", it.second).put("type", "text")) }
        val response = JSONObject().put("name", event.optString("statusText", "").ifBlank { if (status > 0) "Observed HTTP $status" else "Observed response" }).put("originalRequest", JSONObject(request.toString())).put("status", event.optString("statusText", "")).put("code", status).put("header", headerArray).put("cookie", JSONArray())
        if (usableBody) response.put("body", body)
        return response
    }

    private fun testsFor(bindings: List<Binding>): List<String> {
        val lines = mutableListOf<String>()
        bindings.distinctBy { it.variable }.forEach { binding ->
            val variable = JSONObject.quote(binding.variable)
            when (binding.producer.kind) {
                "json" -> {
                    val path = binding.producer.path ?: return@forEach
                    lines.add("try {"); lines.add("  let value = pm.response.json();"); lines.add("  for (const key of ${JSONArray(path)}) value = value == null ? undefined : value[key];"); lines.add("  if (value !== undefined && value !== null) pm.collectionVariables.set($variable, String(value));"); lines.add("} catch (e) {}")
                }
                "header" -> lines.add("try { const value = pm.response.headers.get(${JSONObject.quote(binding.producer.header)}); if (value) pm.collectionVariables.set($variable, String(value)); } catch (e) {}")
                "location" -> lines.add("try { const value = new URL(pm.response.headers.get('Location'), pm.request.url.toString()).searchParams.get(${JSONObject.quote(binding.producer.header)}); if (value) pm.collectionVariables.set($variable, value); } catch (e) {}")
                "html" -> {
                    lines.add("try {")
                    lines.add("  const text = pm.response.text();")
                    lines.add("  const key = ${JSONObject.quote(binding.producer.key)};")
                    lines.add("  const first = new RegExp('name=[\\\"\\\']' + key + '[\\\"\\\'][^>]*value=[\\\"\\\']([^\\\"\\\']+)', 'i');")
                    lines.add("  const second = new RegExp('value=[\\\"\\\']([^\\\"\\\']+)[\\\"\\\'][^>]*name=[\\\"\\\']' + key + '[\\\"\\\']', 'i');")
                    lines.add("  const match = text.match(first) || text.match(second); if (match && match[1]) pm.collectionVariables.set($variable, match[1]);")
                    lines.add("} catch (e) {}")
                }
            }
        }
        return lines
    }

    private fun testEvent(lines: List<String>): JSONArray = JSONArray().put(JSONObject().put("listen", "test").put("script", JSONObject().put("type", "text/javascript").put("exec", JSONArray(lines))))
    private fun role(index: Int, login: Int, verify: Int?, node: Node): String = when { index == login -> "Login"; index == verify -> "Verify authenticated session"; looksRefresh(node) -> "Refresh / token renewal"; index < login -> "Prepare / auth dependency"; hasCredentials(node.event) -> "Authentication step"; else -> "Auth dependency" }

    private fun dedupe(nodes: List<Node>, selected: Set<Int>): List<Int> {
        val seen = linkedSetOf<String>(); val out = mutableListOf<Int>()
        selected.sorted().forEach { index -> val node = nodes[index]; val body = node.event.optString("requestBody", ""); val fingerprint = if (body.length <= 200) body else body.take(100) + "#" + body.length + "#" + body.takeLast(80); if (seen.add("${node.method}|${normalizeUrl(node.url)}|$fingerprint")) out.add(index) }
        return out
    }

    private fun needsSeed(before: AuthFlowAnalyzer.BrowserState, nodes: List<Node>, chosen: List<Int>, hints: List<Hint>): Boolean {
        if (!before.url.startsWith("http")) return false
        if (chosen.any { nodes[it].method == "GET" && normalizeUrl(nodes[it].url) == normalizeUrl(before.url) }) return false
        return authPath(before.url) || hints.any { normalizeUrl(it.page) == normalizeUrl(before.url) }
    }

    private fun isNoise(node: Node, origins: Set<String>): Boolean {
        if (isStatic(node)) return true
        if (isTelemetry(node)) return true
        val nodeOrigin = origin(node.url)
        if (nodeOrigin.isNotBlank() && nodeOrigin !in origins && !isCrossOriginAuthBridge(node)) return true
        return false
    }

    private fun isStatic(node: Node): Boolean {
        if (node.source in setOf("resource-copy", "resource-timing", "script-archive", "source-map", "js-file")) return true
        if (NetworkEventClassifier.responseKind(node.event) in setOf("CSS", "JS", "IMG", "PDF", "BIN")) return true
        val path = try { URL(node.url).path.lowercase(Locale.US) } catch (_: Exception) { node.url.lowercase(Locale.US).substringBefore('?') }
        return listOf(".css", ".js", ".mjs", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".otf", ".map", ".mp4", ".webm", ".mp3").any(path::endsWith)
    }

    private fun documentLike(node: Node): Boolean = node.source == "navigation" || (node.method == "GET" && NetworkEventClassifier.responseKind(node.event) in setOf("HTML", "TEXT", "OTHER"))
    private fun isTelemetry(node: Node): Boolean { val path = try { URL(node.url).path.lowercase(Locale.US) } catch (_: Exception) { node.url.lowercase(Locale.US) }; return listOf("/collect", "/analytics", "/telemetry", "/metrics", "/metric/", "/pixel", "/watch/", "/track", "/counter", "/beacon").any(path::contains) || node.source == "beacon" }
    private fun isCrossOriginAuthBridge(node: Node): Boolean {
        if (isStatic(node) || isTelemetry(node)) return false
        val lower = node.url.lowercase(Locale.US)
        if (authPath(node.url) && (node.method in setOf("POST", "PUT", "PATCH") || hasCredentials(node.event))) return true
        if (listOf("client_id=", "redirect_uri=", "response_type=", "code_challenge=", "samlrequest=", "samlresponse=").any(lower::contains)) return true
        return responseTokenKeys(node.event).isNotEmpty() || requestHeaders(node.event).any { it.first.equals("Authorization", true) && it.second.isNotBlank() }
    }
    private fun looksLikeLoginResponse(event: JSONObject, url: String): Boolean { val body = responseBody(event).lowercase(Locale.US); if (body.isBlank()) return false; val password = body.contains("type=\"password\"") || body.contains("type='password'") || body.contains("name=\"password\"") || body.contains("name='password'"); return password && (authPath(url) || body.contains("login") || body.contains("signin") || body.contains("username")) }
    private fun looksRefresh(node: Node): Boolean { val lower = node.url.lowercase(Locale.US); return lower.contains("refresh") || fields(node.event).keys.any { normalizeField(it).contains("refresh_token") || normalizeField(it) == "refresh" } }
    private fun logoutOrRegistration(url: String): Boolean { val lower = url.lowercase(Locale.US); return listOf("logout", "signout", "sign-out", "register", "signup", "sign-up", "create-account", "reset-password", "forgot-password").any(lower::contains) }
    private fun authPath(url: String): Boolean {
        val lower = try {
            URL(url).path.lowercase(Locale.US)
        } catch (_: Exception) {
            url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        }
        return listOf("/auth", "/login", "/signin", "/sign-in", "/token", "/session", "/oauth", "/sso", "/verify", "/otp", "/mfa", "/2fa", "/refresh", "/callback").any(lower::contains)
    }
    private fun hasResponse(event: JSONObject): Boolean = event.optInt("status", 0) > 0 || responseHeaders(event).isNotEmpty() || responseBody(event).let { it.isNotBlank() && it !in setOf("[binary]", "[non-text response]", "[unavailable]") }
    private fun responseBody(event: JSONObject): String = NetworkEventClassifier.responseBodyText(event)
    private fun requestMaterial(event: JSONObject): String = buildString { append(event.optString("url", "")).append('\n'); requestHeaders(event).forEach { append(it.first).append(':').append(it.second).append('\n') }; append(event.optString("requestBody", "")) }
    private fun containsMaterial(material: String, value: String): Boolean { if (value.isBlank()) return false; if (material.contains(value)) return true; val encoded = try { URLEncoder.encode(value, "UTF-8").replace("+", "%20") } catch (_: Exception) { "" }; return encoded.isNotBlank() && material.contains(encoded, true) }

    private fun requestHeaders(event: JSONObject): List<Pair<String, String>> {
        val headers = event.optJSONObject("requestHeaders") ?: event.optJSONObject("headers") ?: return emptyList()
        val out = linkedMapOf<String, Pair<String, String>>()
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = headers.opt(key)
            if (value == null || value == JSONObject.NULL) continue
            val normalized = key.lowercase(Locale.US)
            val originalName = out[normalized]?.first ?: key
            out[normalized] = originalName to value.toString()
        }
        return out.values.toList()
    }
    private fun responseHeaders(event: JSONObject): List<Pair<String, String>> { event.optJSONObject("responseHeaders")?.let { headers -> val out = mutableListOf<Pair<String, String>>(); val keys = headers.keys(); while (keys.hasNext()) { val key = keys.next(); val value = headers.opt(key); if (value != null && value != JSONObject.NULL) out.add(key to value.toString()) }; return out }; val raw = event.optString("responseHeadersRaw", ""); if (raw.isBlank()) return emptyList(); return raw.lines().mapNotNull { line -> val split = line.indexOf(':'); if (split <= 0) null else line.substring(0, split).trim() to line.substring(split + 1).trim() } }
    private fun parseUrlEncoded(raw: String): List<Pair<String, String>> { if (raw.isBlank()) return emptyList(); return raw.split('&').filter { it.isNotBlank() }.map { part -> val split = part.indexOf('='); decode(if (split >= 0) part.substring(0, split) else part) to decode(if (split >= 0) part.substring(split + 1) else "") } }
    private fun htmlAttributes(tag: String): Map<String, String> { val out = linkedMapOf<String, String>(); Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*([\\\"'])(.*?)\\2", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(tag).forEach { out[it.groupValues[1].lowercase(Locale.US)] = it.groupValues[3] }; return out }
    private fun scalar(value: Any?): String = when (value) { null, JSONObject.NULL -> ""; is JSONObject, is JSONArray -> value.toString(); else -> value.toString() }
    private fun canonicalVariable(key: String): String { val value = normalizeField(key); return when { value.contains("refresh") && value.contains("token") -> "refresh_token"; value.contains("access") && value.contains("token") -> "access_token"; value.contains("id_token") || value == "idtoken" -> "id_token"; value.contains("csrf") || value.contains("xsrf") || value == "sessid" -> "csrf_token"; value.contains("session") && value.contains("token") -> "session_token"; value in setOf("jwt", "authorization", "token") -> "access_token"; value == "state" -> "oauth_state"; value == "code" -> "authorization_code"; value.contains("nonce") -> "nonce"; value.isNotBlank() -> value.take(48); else -> "auth_value" } }
    private fun uniqueVariable(base: String, used: Set<String>): String { if (base !in used) return base; var index = 2; while ("${base}_$index" in used) index++; return "${base}_$index" }
    private fun credentialDescription(name: String): String = when (name) { "login" -> "Login / username / email / phone detected in the authentication request"; "password" -> "Password detected in the authentication request; intentionally left empty"; "otp" -> "One-time code detected in the authentication flow"; "csrf_token" -> "Anti-CSRF value detected in the authentication flow"; "oauth_state" -> "OAuth/OIDC state value"; "code_verifier" -> "OAuth PKCE code_verifier"; "code_challenge" -> "OAuth PKCE code_challenge"; else -> "AUTH variable" }
    private fun tokenLike(value: String): Boolean { if (value.length < 16 || value.length > 4096 || value.contains(' ')) return false; if (value.count { it == '.' } == 2 && value.all { it.isLetterOrDigit() || it in "-_." }) return true; return value.length >= 24 && value.toSet().size >= 10 && value.all { it.isLetterOrDigit() || it in "-_=.~" } }
    private fun reusable(value: String): Boolean = value.length in 4..4096 && value.lowercase(Locale.US) !in setOf("true", "false", "null", "undefined", "success", "error")
    private fun portableUrl(raw: String, base: String, replacements: Map<String, String>): String { var value = replace(raw, replacements); if (base.isNotBlank() && raw.startsWith(base)) value = "{{base_url}}" + value.substring(base.length); return value }
    private fun replace(raw: String, replacements: Map<String, String>): String { var out = raw; replacements.entries.sortedByDescending { it.key.length }.forEach { (value, variable) -> if (value.isBlank()) return@forEach; val marker = "{{$variable}}"; out = out.replace(value, marker); try { val encoded = URLEncoder.encode(value, "UTF-8").replace("+", "%20"); if (encoded != value) out = out.replace(encoded, marker, true) } catch (_: Exception) {} }; return out.replace("[password]", "{{password}}") }
    private fun changedCookies(before: String, after: String): List<String> { val a = parseCookies(before); val b = parseCookies(after); return (a.keys + b.keys).distinct().filter { a[it] != b[it] }.sorted() }
    private fun changedStorage(before: AuthFlowAnalyzer.BrowserState, after: AuthFlowAnalyzer.BrowserState): List<String> { val out = mutableListOf<String>(); listOf("local" to (before.localStorage to after.localStorage), "session" to (before.sessionStorage to after.sessionStorage)).forEach { (prefix, pair) -> val keys = linkedSetOf<String>(); pair.first.keys().forEachRemaining { keys.add(it) }; pair.second.keys().forEachRemaining { keys.add(it) }; keys.filter { pair.first.opt(it)?.toString() != pair.second.opt(it)?.toString() }.forEach { out.add("$prefix:$it") } }; return out.sorted() }
    private fun parseCookies(raw: String): Map<String, String> = raw.split(';').mapNotNull { part -> val text = part.trim(); val split = text.indexOf('='); if (split <= 0) null else text.substring(0, split).trim() to text.substring(split + 1) }.toMap()
    private fun normalizeField(value: String): String = value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun normalizeUrl(raw: String): String = try { val url = URL(raw); val port = if (url.port > 0 && url.port != url.defaultPort) ":${url.port}" else ""; "${url.protocol.lowercase(Locale.US)}://${url.host.lowercase(Locale.US)}$port${url.path.ifBlank { "/" }}" + if (url.query.isNullOrBlank()) "" else "?${url.query}" } catch (_: Exception) { raw.substringBefore('#') }
    private fun decode(value: String): String = try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { value }
    private fun host(raw: String): String = try { URL(raw).host } catch (_: Exception) { "" }
    private fun origin(raw: String): String = try { val url = URL(raw); buildString { append(url.protocol).append("://").append(url.host); if (url.port > 0 && url.port != url.defaultPort) append(':').append(url.port) } } catch (_: Exception) { "" }
    private fun compact(raw: String): String = try { val url = URL(raw); val text = url.path.ifBlank { "/" } + if (url.query.isNullOrBlank()) "" else "?" + url.query.take(80); if (text.length <= 90) text else "…" + text.takeLast(89) } catch (_: Exception) { if (raw.length <= 90) raw else raw.take(87) + "…" }
}
