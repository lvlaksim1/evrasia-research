package ru.evrasia.research

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class UniversalAuthAnalyzerCaptchaTest {
    @Test
    fun keepsUnprovenCaptchaSuccessTokenUnresolvedAndUnseeded() {
        val login = "user@example.test"
        val challenge = "challenge_abcdefghijklmnopqrstuvwxyz123456"
        val state = "state_abcdefghijklmnopqrstuvwxyz123456"
        val anonymous = "anonymous_abcdefghijklmnopqrstuvwxyz123456"
        val authToken = "auth_token_abcdefghijklmnopqrstuvwxyz123456"
        val sid = "sid_abcdefghijklmnopqrstuvwxyz123456"
        val successToken = "captcha_success_token_abcdefghijklmnopqrstuvwxyz1234567890"
        val rootUrl = "https://account.test/login"
        val authUrl = "https://id.test/auth?code_challenge=" + encode(challenge) + "&redirect_state=" + encode(state)
        val captchaUrl = "https://captcha.test/challenge?sid=captcha_sid_1234567890"

        val pageSource = "window.init=" + JSONObject()
            .put("auth", JSONObject().put("access_token", authToken).put("anonymous_token", anonymous))
            .toString() + ";"

        val nextUrl = "https://auth.test/silent?state=" + encode(state)

        val events = listOf(
            event("navigation", 1000, "GET", rootUrl, 200),
            event("navigation", 1020, "GET", authUrl, 200),
            JSONObject()
                .put("source", "auth-page-source")
                .put("time", 1021)
                .put("method", "GET")
                .put("url", authUrl)
                .put("content", pageSource),
            event("resource-copy", 1030, "GET", rootUrl, 200)
                .put("redirectURL", authUrl),

            event("fetch", 1040, "POST", "https://api.test/method/auth.validateAccount", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", form(
                    "login" to login,
                    "code_challenge" to challenge,
                    "mail_token" to state,
                    "anonymous_token" to anonymous
                ))
                .put("responseBody", JSONObject().put("response", JSONObject().put("sid", sid)).toString()),

            event("fetch", 1050, "POST", "https://api.test/method/vkidmail.checkPassword", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", form(
                    "sid" to sid,
                    "password" to "[password]",
                    "anonymous_token" to anonymous
                ))
                .put(
                    "responseBody",
                    JSONObject().put(
                        "error",
                        JSONObject()
                            .put("error_code", 14)
                            .put("error_msg", "Captcha need")
                            .put("redirect_uri", captchaUrl)
                    ).toString()
                ),

            event("navigation", 1060, "GET", captchaUrl, 200),

            // The token below intentionally has NO producer in earlier traffic.
            event("fetch", 1070, "POST", "https://api.test/method/vkidmail.checkPassword", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", form(
                    "sid" to sid,
                    "password" to "[password]",
                    "success_token" to successToken,
                    "anonymous_token" to anonymous
                ))
                .put("responseBody", JSONObject().put("response", JSONObject().put("next_step", "on_success_validation")).toString()),

            event("fetch", 1080, "POST", "https://api.test/method/auth.onSuccessValidation", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", form("sid" to sid))
                .put("responseBody", JSONObject().put("response", JSONObject().put("next_step", "connect_authorize")).toString()),

            event("fetch", 1090, "POST", "https://login.test/?act=connect_authorize", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", form("auth_token" to authToken, "sid" to sid))
                .put("responseBody", JSONObject().put("response", JSONObject().put("next_step_url", nextUrl)).toString()),

            event("navigation", 1100, "GET", nextUrl, 200)
                .put("responseHeaders", JSONObject().put("Set-Cookie", "session=fresh-session; Path=/; HttpOnly"))
        )

        val before = state(rootUrl, "")
        val after = state("https://mail.test/inbox", "session=fresh-session")
        val result = UniversalAuthAnalyzer.analyze(events, before, after)

        assertTrue(
            "Analyzer must expose unresolved captcha dependency. Notes: " + result.notes.joinToString(" | "),
            result.notes.any { it.contains("success_token", true) && it.contains("UNRESOLVED", true) }
        )
        assertTrue("Unresolved flow must not be HIGH confidence", result.confidence == "LOW")

        val collection = JSONObject(result.collectionJson)
        val text = collection.toString()
        assertTrue("Collection must parameterize success_token", text.contains("{{success_token"))
        assertFalse("Captured captcha token must not be hardcoded", text.contains(successToken))

        val validation = PostmanReplayabilityValidator.validate(result.collectionJson)
        assertFalse("Unresolved captcha flow must fail clean replayability validation", validation.ok)
        assertTrue(
            validation.issues.joinToString("\n"),
            validation.issues.any { it.contains("unresolved", true) && it.contains("success_token", true) }
        )

        val environment = PostmanEnvironmentSnapshotSafe.build(result.collectionJson, events, pageSource)
        assertFalse("Environment must not seed captured captcha token", environment.contains(successToken))
    }

    private fun event(source: String, time: Long, method: String, url: String, status: Int): JSONObject =
        JSONObject().put("source", source).put("time", time).put("method", method).put("url", url).put("status", status)

    private fun form(vararg values: Pair<String, String>): String =
        values.joinToString("&") { (key, value) -> encode(key) + "=" + encode(value) }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun state(url: String, cookies: String) = AuthFlowAnalyzer.BrowserState(
        time = 0,
        url = url,
        nativeCookies = cookies,
        documentCookies = cookies,
        localStorage = JSONObject(),
        sessionStorage = JSONObject()
    )
}
