package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostmanReplayabilityValidatorTest {
    @Test
    fun rejectsSelfFutureAndGetBody() {
        val collection = collection(
            variables = listOf(
                variable("code_challenge", "", "Automatically extracted from an earlier AUTH response"),
                variable("email", "", "Automatically extracted from an earlier AUTH response")
            ),
            items = listOf(
                item("GET", "https://example.test/auth?code_challenge={{code_challenge}}", "{{code_challenge}}", "code_challenge"),
                item("POST", "https://example.test/login", "login={{email}}", null),
                item("POST", "https://example.test/final", "", "email")
            )
        )
        val result = PostmanReplayabilityValidator.validate(collection.toString())
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.contains("own response") })
        assertTrue(result.issues.any { it.contains("future step") })
        assertTrue(result.issues.any { it.contains("GET request contains a body") })
    }

    @Test
    fun acceptsStrictCausalReplay() {
        val collection = collection(
            variables = listOf(
                variable("login", "", "Login / username / email / phone detected in the authentication request"),
                variable("password", "", "Password detected in the authentication request; intentionally left empty"),
                variable("sid", "", "Automatically extracted from an earlier AUTH response")
            ),
            items = listOf(
                item("POST", "https://example.test/validate", "login={{login}}", "sid"),
                item("POST", "https://example.test/password", "sid={{sid}}&password={{password}}", null)
            )
        )
        val result = PostmanReplayabilityValidator.validate(collection.toString())
        assertTrue(result.issues.joinToString("\n"), result.ok)
    }

    @Test
    fun rejectsEnvironmentSeedForUnresolved() {
        val collection = collection(
            variables = listOf(variable("deviceid", "", "UNRESOLVED dynamic AUTH dependency; no earlier producer was captured")),
            items = listOf(item("POST", "https://example.test/auth", "device_id={{deviceid}}", null))
        )
        val environment = JSONObject().put("values", JSONArray().put(JSONObject().put("key", "deviceid").put("value", "captured-old-value")))
        val result = PostmanReplayabilityValidator.validate(collection.toString(), environment.toString())
        assertFalse(result.ok)
        assertTrue(result.issues.any { it.contains("seeded by the environment") })
    }

    private fun collection(variables: List<JSONObject>, items: List<JSONObject>): JSONObject =
        JSONObject().put("variable", JSONArray(variables)).put("item", JSONArray(items))

    private fun variable(key: String, value: String, description: String): JSONObject =
        JSONObject().put("key", key).put("value", value).put("description", description)

    private fun item(method: String, url: String, body: String, produced: String?): JSONObject {
        val request = JSONObject().put("method", method).put("url", url).put("header", JSONArray())
        if (body.isNotBlank()) request.put("body", JSONObject().put("mode", "raw").put("raw", body))
        val item = JSONObject().put("name", method).put("request", request)
        if (produced != null) {
            val exec = JSONArray().put("pm.collectionVariables.set(\"$produced\", \"value\");")
            item.put("event", JSONArray().put(JSONObject().put("listen", "test").put("script", JSONObject().put("exec", exec))))
        }
        return item
    }
}