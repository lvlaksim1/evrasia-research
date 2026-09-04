package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject

internal object PostmanReplayabilityValidator {
    data class Result(val ok: Boolean, val issues: List<String>)

    fun validate(collectionJson: String, environmentJson: String = ""): Result {
        return try {
            val collection = JSONObject(collectionJson)
            val items = collection.optJSONArray("item") ?: JSONArray()
            val variables = collection.optJSONArray("variable") ?: JSONArray()
            val descriptions = linkedMapOf<String, String>()
            val staticValues = linkedSetOf<String>()
            val userInputs = linkedSetOf<String>()
            val unresolved = linkedSetOf<String>()

            for (index in 0 until variables.length()) {
                val variable = variables.optJSONObject(index) ?: continue
                val key = variable.optString("key", "").trim()
                if (key.isBlank()) continue
                val value = variable.optString("value", "")
                val description = variable.optString("description", "")
                descriptions[key] = description
                if (description.contains("UNRESOLVED", true)) unresolved.add(key)
                if (isUserInput(key, description)) userInputs.add(key)
                if (value.isNotBlank() && !isRuntimeDynamic(description)) staticValues.add(key)
            }

            val environmentValues = environmentValues(environmentJson)
            val issues = mutableListOf<String>()
            unresolved.forEach { key ->
                if (environmentValues[key].orEmpty().isNotBlank()) {
                    issues.add("UNRESOLVED variable {{$key}} is seeded by the environment")
                }
            }

            val producedAt = linkedMapOf<String, Int>()
            val producedPerStep = mutableListOf<Set<String>>()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val produced = producedVariables(item)
                producedPerStep.add(produced)
                produced.forEach { key -> producedAt.putIfAbsent(key, index) }
            }

            val producedBefore = linkedSetOf<String>()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val request = item.optJSONObject("request") ?: continue
                val method = request.optString("method", "GET").uppercase()
                if (method == "GET" && hasBody(request)) {
                    issues.add("Step ${index + 1} GET request contains a body")
                }

                val used = requestVariables(request)
                used.forEach { key ->
                    if (key in userInputs || key in staticValues || key in producedBefore) return@forEach
                    val producer = producedAt[key]
                    when {
                        producer == index -> issues.add("Step ${index + 1} consumes {{$key}} produced only by its own response")
                        producer != null && producer > index -> issues.add("Step ${index + 1} consumes {{$key}} produced only by future step ${producer + 1}")
                        key in unresolved -> issues.add("Step ${index + 1} consumes unresolved dynamic variable {{$key}}")
                        environmentValues[key].orEmpty().isNotBlank() && !isRuntimeDynamic(descriptions[key].orEmpty()) -> Unit
                        else -> issues.add("Step ${index + 1} consumes {{$key}} without an earlier producer or explicit user/static input")
                    }
                }
                producedBefore.addAll(producedPerStep.getOrElse(index) { emptySet() })
            }

            Result(issues.isEmpty(), issues.distinct())
        } catch (error: Exception) {
            Result(false, listOf("Replayability validator failed: ${error.message ?: "invalid Postman collection"}"))
        }
    }

    private fun isUserInput(key: String, description: String): Boolean {
        val normalized = key.lowercase()
        if (normalized in setOf("login", "password", "otp")) return true
        return description.contains("Login / username / email / phone", true) ||
            description.contains("Password detected", true) ||
            description.contains("One-time code", true)
    }

    private fun isRuntimeDynamic(description: String): Boolean =
        description.contains("Automatically extracted from an earlier AUTH response", true) ||
            description.contains("Dynamically extracted AUTH value", true) ||
            description.contains("UNRESOLVED", true)

    private fun environmentValues(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return try {
            val root = JSONObject(json)
            val values = root.optJSONArray("values") ?: JSONArray()
            buildMap {
                for (index in 0 until values.length()) {
                    val item = values.optJSONObject(index) ?: continue
                    val key = item.optString("key", "").trim()
                    if (key.isNotBlank()) put(key, item.optString("value", ""))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun requestVariables(request: JSONObject): Set<String> {
        val out = linkedSetOf<String>()
        collectMarkers(request.opt("url"), out)
        val headers = request.optJSONArray("header") ?: JSONArray()
        for (index in 0 until headers.length()) collectMarkers(headers.optJSONObject(index)?.optString("value", ""), out)
        request.optJSONObject("body")?.let { body ->
            collectMarkers(body.optString("raw", ""), out)
            listOf("urlencoded", "formdata").forEach { name ->
                val array = body.optJSONArray(name) ?: return@forEach
                for (index in 0 until array.length()) collectMarkers(array.optJSONObject(index)?.optString("value", ""), out)
            }
        }
        return out
    }

    private fun collectMarkers(value: Any?, out: MutableSet<String>) {
        val text = when (value) {
            is JSONObject -> value.optString("raw", value.toString())
            null, JSONObject.NULL -> ""
            else -> value.toString()
        }
        Regex("\\{\\{\\s*([^{}]+?)\\s*}}").findAll(text).forEach { match ->
            val key = match.groupValues[1].trim()
            if (key.isNotBlank()) out.add(key)
        }
    }

    private fun producedVariables(item: JSONObject): Set<String> {
        val out = linkedSetOf<String>()
        val events = item.optJSONArray("event") ?: return out
        for (eventIndex in 0 until events.length()) {
            val event = events.optJSONObject(eventIndex) ?: continue
            if (event.optString("listen", "") != "test") continue
            val exec = event.optJSONObject("script")?.optJSONArray("exec") ?: continue
            for (lineIndex in 0 until exec.length()) {
                val line = exec.optString(lineIndex, "")
                Regex("pm\\.collectionVariables\\.set\\(\\s*[\'\"]([^\'\"]+)[\'\"]").findAll(line).forEach { match ->
                    out.add(match.groupValues[1])
                }
            }
        }
        return out
    }

    private fun hasBody(request: JSONObject): Boolean {
        val body = request.optJSONObject("body") ?: return false
        if (body.optString("raw", "").isNotBlank()) return true
        return listOf("urlencoded", "formdata").any { name ->
            val array = body.optJSONArray(name)
            array != null && array.length() > 0
        }
    }
}