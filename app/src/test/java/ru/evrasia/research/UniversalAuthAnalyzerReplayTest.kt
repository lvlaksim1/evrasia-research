package ru.evrasia.research

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalAuthAnalyzerReplayTest {
    @Test
    fun reconstructsReplayableFlowFromObservedRedirectRoot() {
        val challenge = "challenge_abcdefghijklmnopqrstuvwxyz123456"
        val anonymous = "anonymous_abcdefghijklmnopqrstuvwxyz123456"
        val sid = "sid_abcdefghijklmnopqrstuvwxyz123456"
        val sessionToken = "session_abcdefghijklmnopqrstuvwxyz123456"
        val idUrl = "https://id.test/auth?code_challenge=$challenge&state=fresh_state_1234567890"
        val nextUrl = "https://auth.test/silent?token=$sessionToken"
        val events = listOf(
            event("navigation", 1000, "GET", "https://account.test/login", 200),
            event("resource-copy", 1010, "GET", "https://account.test/login", 200).put("redirectURL", idUrl),
            event("webview", 1020, "GET", idUrl, 200),
            JSONObject().put("source", "auth-page-source").put("time", 1021).put("method", "GET").put("url", idUrl).put("content", "window.init={\\\"oauth\\\":{\\\"code_challenge\\\":\\\"$challenge\\\"},\\\"auth\\\":{\\\"anonymous_token\\\":\\\"$anonymous\\\"}};"),
            event("fetch", 1030, "POST", "https://api.test/method/auth.validateAccount", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", "login=user@example.test&code_challenge=$challenge&anonymous_token=$anonymous")
                .put("responseBody", "{\\\"response\\\":{\\\"sid\\\":\\\"$sid\\\"}}"),
            event("fetch", 1040, "POST", "https://api.test/method/vkidmail.checkPassword", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", "sid=$sid&password=[password]&anonymous_token=$anonymous")
                .put("responseBody", "{\\\"response\\\":{\\\"next_step\\\":\\\"on_success_validation\\\"}}"),
            event("fetch", 1050, "POST", "https://api.test/method/auth.onSuccessValidation", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", "sid=$sid")
                .put("responseBody", "{\\\"response\\\":{\\\"next_step\\\":\\\"connect_authorize\\\"}}"),
            event("fetch", 1060, "POST", "https://login.test/?act=connect_authorize", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", "sid=$sid")
                .put("responseBody", "{\\\"response\\\":{\\\"next_step_url\\\":\\\"$nextUrl\\\"}}"),
            event("navigation", 1070, "GET", nextUrl, 200)
                .put("responseHeaders", JSONObject().put("Set-Cookie", "session=authenticated_value; Path=/; HttpOnly"))
        )

        val before = state("https://account.test/login", "")
        val after = state("https://mail.test/inbox", "session=authenticated_value")
        val result = UniversalAuthAnalyzer.analyze(events, before, after)
        val validation = PostmanReplayabilityValidator.validate(result.collectionJson)
        assertTrue(validation.issues.joinToString("\n"), validation.ok)

        val collection = JSONObject(result.collectionJson)
        val items = collection.getJSONArray("item")
        assertTrue(items.length() >= 5)
        val firstUrl = requestUrl(items.getJSONObject(0).getJSONObject("request"))
        assertTrue(firstUrl.contains("account.test/login"))

        var validateBody = ""
        for (index in 0 until items.length()) {
            val request = items.getJSONObject(index).getJSONObject("request")
            if (requestUrl(request).contains("auth.validateAccount")) {
                validateBody = request.optJSONObject("body")?.toString().orEmpty()
            }
            if (request.optString("method", "").equals("GET", true)) {
                assertFalse(request.has("body"))
            }
        }
        assertTrue(validateBody.contains("{{login}}"))
        assertFalse(validateBody.contains("{{email}}"))
    }

    private fun event(source: String, time: Long, method: String, url: String, status: Int): JSONObject =
        JSONObject().put("source", source).put("time", time).put("method", method).put("url", url).put("status", status)

    private fun state(url: String, cookies: String) = AuthFlowAnalyzer.BrowserState(
        time = 0,
        url = url,
        nativeCookies = cookies,
        documentCookies = cookies,
        localStorage = JSONObject(),
        sessionStorage = JSONObject()
    )

    private fun requestUrl(request: JSONObject): String {
        val value = request.opt("url")
        return if (value is JSONObject) value.optString("raw", value.toString()) else value?.toString().orEmpty()
    }
}