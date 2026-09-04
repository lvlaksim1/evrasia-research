package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.util.Base64

class UniversalAuthAnalyzerNewmanFixtureTest {
    @Test
    fun writesExecutableCleanReplayCollection() {
        val account = "http://127.0.0.1:18080"
        val id = "http://127.0.0.1:18081"
        val api = "http://127.0.0.1:18082"
        val loginHost = "http://127.0.0.1:18083"
        val auth = "http://127.0.0.1:18084"
        val touch = "http://127.0.0.1:18085"

        val login = "user@example.test"
        val challenge = "captured_challenge_abcdefghijklmnopqrstuvwxyz"
        val copiedChallenge = "copy_challenge_abcdefghijklmnopqrstuvwxyz123"
        val state = "captured_state_abcdefghijklmnopqrstuvwxyz"
        val copiedState = "copy_state_abcdefghijklmnopqrstuvwxyz123"
        val oid = "captured_oid_abcdefghijklmnop"
        val copiedOid = "copy_oid_abcdefghijklmnopq"
        val device = "DeviceId_abcdefghijkl"
        val anonymous = "captured_anonymous_abcdefghijklmnopqrstuvwxyz"
        val authToken = "captured_auth_token_abcdefghijklmnopqrstuvwxyz"
        val sid = "captured_sid_abcdefghijklmnopqrstuvwxyz"

        val action = base64Json(
            JSONObject()
                .put("name", "mail_auth")
                .put("params", JSONObject().put("mail_auth_type", "auth_login_page"))
        )
        val appSettings = base64Json(JSONObject().put("service_groups", JSONObject().put("oid", oid)))
        val copiedAppSettings = base64Json(JSONObject().put("service_groups", JSONObject().put("oid", copiedOid)))

        val accountUrl = "$account/login?success_redirect=" + encode("$touch/messages") + "&from=main"
        val idUrl = idUrl(id, touch, action, appSettings, challenge, state)
        val copiedIdUrl = idUrl(id, touch, action, copiedAppSettings, copiedChallenge, copiedState)

        val pageSource = "window.init=" + JSONObject()
            .put("auth", JSONObject().put("access_token", authToken).put("anonymous_token", anonymous))
            .toString() + ";"

        val silentBase = "$auth/api/v1/vkid_auth/silent/grey_login"
        val toDecoded = silentBase +
            "?state=" + state +
            "&from=" + encode(encode(idUrl)) +
            "&email=" + encode(login)
        val to = Base64.getEncoder().encodeToString(toDecoded.toByteArray(Charsets.UTF_8))

        val nextUrl = silentBase +
            "?state=" + state +
            "&from=" + encode(idUrl) +
            "&email=" + encode(login) +
            "&payload=captured_payload_that_must_be_replaced_by_response"

        val events = listOf(
            event("navigation", 1000, "GET", accountUrl, 200),
            event("navigation", 1020, "GET", idUrl, 200),
            JSONObject()
                .put("source", "auth-page-source")
                .put("time", 1021)
                .put("method", "GET")
                .put("url", idUrl)
                .put("content", pageSource),
            event("resource-copy", 1030, "GET", accountUrl, 200)
                .put("redirectURL", copiedIdUrl),

            event("fetch", 1040, "POST", "$api/method/auth.validateAccount", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put(
                    "requestBody",
                    form(
                        "login" to login,
                        "client_id" to "7539952",
                        "device_id" to device,
                        "mail_token" to state,
                        "oid" to oid,
                        "code_challenge" to challenge,
                        "code_challenge_method" to "S256",
                        "anonymous_token" to anonymous
                    )
                )
                .put("responseBody", response("sid", sid)),

            event("fetch", 1050, "POST", "$api/method/vkidmail.checkPassword", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", form("sid" to sid, "password" to "[password]", "anonymous_token" to anonymous))
                .put("responseBody", response("next_step", "on_success_validation")),

            event("fetch", 1060, "POST", "$api/method/auth.onSuccessValidation", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", form("sid" to sid, "anonymous_token" to anonymous))
                .put("responseBody", response("next_step", "connect_authorize")),

            event("fetch", 1070, "POST", "$loginHost/?act=connect_authorize", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put(
                    "requestBody",
                    form(
                        "auth_token" to authToken,
                        "sid" to sid,
                        "device_id" to device,
                        "service_group" to "oid_$oid",
                        "oauth_state" to state,
                        "to" to to,
                        "app_id" to "7539952"
                    )
                )
                .put("responseBody", response("next_step_url", nextUrl)),

            event("navigation", 1080, "GET", nextUrl, 200),
            event("navigation", 1090, "GET", "$touch/messages?afterRedir=1", 200),
            event("resource-copy", 1100, "GET", nextUrl, 200)
                .put("redirectURL", "$touch/messages?afterRedir=1")
                .put("responseHeaders", JSONObject().put("Set-Cookie", "session=captured-old-session; Path=/; HttpOnly"))
        )

        val before = state(accountUrl, "")
        val after = state("$touch/messages?afterRedir=1", "session=captured-new-session")
        val result = UniversalAuthAnalyzer.analyze(events, before, after)
        val validation = PostmanReplayabilityValidator.validate(result.collectionJson)
        assertTrue(validation.issues.joinToString("\n"), validation.ok)

        val collection = JSONObject(result.collectionJson)
        val items = collection.getJSONArray("item")
        items.put(
            JSONObject()
                .put("name", "E2E assert authenticated session")
                .put(
                    "request",
                    JSONObject()
                        .put("method", "GET")
                        .put("header", JSONArray())
                        .put("url", "$touch/assert")
                )
                .put(
                    "event",
                    JSONArray().put(
                        JSONObject()
                            .put("listen", "test")
                            .put(
                                "script",
                                JSONObject()
                                    .put("type", "text/javascript")
                                    .put(
                                        "exec",
                                        JSONArray(
                                            listOf(
                                                "pm.test('E2E authenticated session', function () {",
                                                "  pm.response.to.have.status(200);",
                                                "});"
                                            )
                                        )
                                    )
                            )
                    )
                )
        )

        val target = File("build/auth-e2e.postman_collection.json")
        target.parentFile?.mkdirs()
        target.writeText(collection.toString(2), Charsets.UTF_8)
        assertTrue("E2E collection was not written", target.isFile && target.length() > 0)
    }

    private fun idUrl(
        id: String,
        touch: String,
        action: String,
        appSettings: String,
        challenge: String,
        state: String
    ): String =
        "$id/auth?action=" + encode(action) +
            "&app_id=7539952" +
            "&app_settings=" + encode(appSettings) +
            "&code_challenge=" + encode(challenge) +
            "&code_challenge_method=S256" +
            "&redirect_state=" + encode(state) +
            "&redirect_uri=" + encode("$touch/messages") +
            "&response_type=silent_token"

    private fun base64Json(value: JSONObject): String =
        Base64.getEncoder().encodeToString(value.toString().toByteArray(Charsets.UTF_8))

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun form(vararg values: Pair<String, String>): String =
        values.joinToString("&") { (key, value) -> encode(key) + "=" + encode(value) }

    private fun response(key: String, value: String): String =
        JSONObject().put("response", JSONObject().put(key, value)).toString()

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
}
