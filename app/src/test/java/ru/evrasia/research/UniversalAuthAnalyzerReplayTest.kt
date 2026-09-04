package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class UniversalAuthAnalyzerReplayTest {
    @Test
    fun reconstructsReplayableFlowFromDelayedReplayRedirectEvidence() {
        val login = "user@example.test"
        val challenge = "challenge_actual_abcdefghijklmnopqrstuvwxyz"
        val copiedChallenge = "challenge_copy_abcdefghijklmnopqrstuvwxyz12"
        val state = "state_actual_abcdefghijklmnopqrstuvwxyz"
        val copiedState = "state_copy_abcdefghijklmnopqrstuvwxyz12"
        val oid = "OID_TEST_abcdefghijklmnopqrstuvwxyz"
        val copiedOid = "OID_COPY_abcdefghijklmnopqrstuvwxyz"
        val device = "DeviceId_abcdefghijkl"
        val anonymous = "anonymous_abcdefghijklmnopqrstuvwxyz123456"
        val authToken = "auth_token_abcdefghijklmnopqrstuvwxyz123456"
        val sid = "sid_abcdefghijklmnopqrstuvwxyz123456"

        val action = base64Json(
            JSONObject()
                .put("name", "mail_auth")
                .put("params", JSONObject().put("mail_auth_type", "auth_login_page"))
        )
        val appSettings = base64Json(JSONObject().put("service_groups", JSONObject().put("oid", oid)))
        val copiedAppSettings = base64Json(JSONObject().put("service_groups", JSONObject().put("oid", copiedOid)))

        val accountUrl = "https://account.test/login?success_redirect=https%3A%2F%2Ftouch.test%2Fmessages&from=main"
        val idUrl = idUrl(action, appSettings, challenge, state)
        val copiedIdUrl = idUrl(action, copiedAppSettings, copiedChallenge, copiedState)

        val authSource = "window.init=" + JSONObject()
            .put("auth", JSONObject().put("access_token", authToken).put("anonymous_token", anonymous))
            .toString() + ";"

        val nextUrl = "https://auth.test/silent?state=" + encode(state) + "&email=" + encode(login) + "&token=session_payload_abcdefghijklmnopqrstuvwxyz"
        val toDecoded = nextUrl + "&from=" + encode(idUrl) + "&email=" + encode(login)
        val to = Base64.getEncoder().encodeToString(toDecoded.toByteArray(Charsets.UTF_8))

        val events = listOf(
            event("navigation", 1000, "GET", accountUrl, 200),

            // In the real capture the actual browser navigation is seen before resource-copy finishes.
            event("navigation", 1020, "GET", idUrl, 200),
            JSONObject()
                .put("source", "auth-page-source")
                .put("time", 1021)
                .put("method", "GET")
                .put("url", idUrl)
                .put("content", authSource),

            // resource-copy is only evidence that accountUrl produces the auth route.
            // Its fresh query values intentionally differ from the actual browser navigation.
            event("resource-copy", 1030, "GET", accountUrl, 200)
                .put("redirectURL", copiedIdUrl),

            event("fetch", 1040, "POST", "https://api.test/method/auth.validateAccount", 200)
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

            event("fetch", 1050, "POST", "https://api.test/method/vkidmail.checkPassword", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", form("sid" to sid, "password" to "[password]", "anonymous_token" to anonymous))
                .put("responseBody", response("next_step", "on_success_validation")),

            event("fetch", 1060, "POST", "https://api.test/method/auth.onSuccessValidation", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", form("sid" to sid, "anonymous_token" to anonymous))
                .put("responseBody", response("next_step", "mailru_mimicry_get_silent_token")),

            event("fetch", 1070, "POST", "https://login.test/?act=connect_authorize", 200)
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
            event("navigation", 1090, "GET", "https://touch.test/messages?afterRedir=1", 200),

            // Delayed replay response proves that the previous silent-login request issued
            // the authenticated browser cookie. Captured cookie values are never replayed.
            event("resource-copy", 1100, "GET", nextUrl, 200)
                .put("redirectURL", "https://touch.test/messages?afterRedir=1")
                .put("responseHeaders", JSONObject().put("Set-Cookie", "session=old-captured-value; Path=/; HttpOnly"))
        )

        val before = state(accountUrl, "")
        val after = state("https://touch.test/messages?afterRedir=1", "session=new-authenticated-value")

        val result = UniversalAuthAnalyzer.analyze(events, before, after)
        val validation = PostmanReplayabilityValidator.validate(result.collectionJson)
        if (!validation.ok) throw AssertionError("FULL_SHAPE_REPLAYABILITY:\n" + validation.issues.joinToString("\n"))

        val collection = JSONObject(result.collectionJson)
        val items = collection.getJSONArray("item")
        assertTrue(items.length() >= 6)

        val firstUrl = requestUrl(items.getJSONObject(0).getJSONObject("request"))
        assertTrue(firstUrl.contains("account.test/login"))

        var idRequestUrl = ""
        var validateBody = ""
        var connectBody = ""
        var hasTouchAfterAuth = false

        for (index in 0 until items.length()) {
            val request = items.getJSONObject(index).getJSONObject("request")
            val url = requestUrl(request)
            val method = request.optString("method", "")
            if (url.contains("id.test/auth") || url == "{{redirect_url}}") idRequestUrl = url
            if (url.contains("auth.validateAccount")) validateBody = request.optJSONObject("body")?.toString().orEmpty()
            if (url.contains("connect_authorize")) connectBody = request.optJSONObject("body")?.toString().orEmpty()
            if (url.contains("touch.test/messages")) hasTouchAfterAuth = true
            if (method.equals("GET", true)) assertFalse("GET step contains body: $url", request.has("body"))
        }

        assertTrue("id auth must be reached through runtime redirect", idRequestUrl.contains("{{redirect_url}}"))

        assertTrue(validateBody.contains("{{login}}"))
        assertTrue(validateBody.contains("{{code_challenge}}"))
        assertTrue(validateBody.contains("{{oid}}"))
        assertTrue(validateBody.contains("{{device_id}}"))
        assertFalse(validateBody.contains("{{email}}"))
        assertFalse(validateBody.contains(challenge))
        assertFalse(validateBody.contains(oid))
        assertFalse(validateBody.contains(device))

        assertTemplateField(connectBody, "auth_token", authToken)
        assertTemplateField(connectBody, "sid", sid)
        assertTemplateField(connectBody, "device_id", device)
        assertTrue(connectBody.contains("{{oid}}"))
        assertTemplateField(connectBody, "to", to)
        assertFalse(connectBody.contains(device))
        assertFalse(connectBody.contains(to))

        assertFalse("post-auth navigation must be pruned after session cookie issuance", hasTouchAfterAuth)

        val collectionText = collection.toString()
        assertFalse(collectionText.contains(copiedChallenge))
        assertFalse(collectionText.contains(copiedState))
        assertFalse(collectionText.contains(copiedOid))

        val environment = PostmanEnvironmentSnapshotSafe.build(result.collectionJson, events, authSource)
        val environmentText = environment
        assertFalse(environmentText.contains(device))
        assertFalse(environmentText.contains(to))
        assertFalse(environmentText.contains("old-captured-value"))
    }


    // Sanitized structural mirror of the uploaded full AUTH capture: duplicates, WebView API calls and delayed evidence.
    @Test
    fun reconstructsReplayableFlowFromFullCaptureShape() {
        val login = "user@example.test"
        val challenge = "challenge_actual_full_abcdefghijklmnopqrstuvwxyz"
        val copiedChallenge = "challenge_copy_full_abcdefghijklmnopqrstuvwxyz"
        val state = "state_actual_full_abcdefghijklmnopqrstuvwxyz"
        val copiedState = "state_copy_full_abcdefghijklmnopqrstuvwxyz"
        val oid = "OID_FULL_abcdefghijklmnopqrstuvwxyz"
        val copiedOid = "OID_COPY_FULL_abcdefghijklmnopqrstuvwxyz"
        val device = "DeviceId_full_abcdefgh"
        val anonymous = "anonymous_full_abcdefghijklmnopqrstuvwxyz123456"
        val authToken = "auth_token_full_abcdefghijklmnopqrstuvwxyz123456"
        val sid = "sid_full_abcdefghijklmnopqrstuvwxyz123456"

        val action = base64Json(
            JSONObject()
                .put("name", "mail_auth")
                .put("params", JSONObject().put("mail_auth_type", "auth_login_page"))
        )
        val appSettings = base64Json(JSONObject().put("service_groups", JSONObject().put("oid", oid)))
        val copiedAppSettings = base64Json(JSONObject().put("service_groups", JSONObject().put("oid", copiedOid)))

        val accountUrl = "https://account.test/login?success_redirect=https%3A%2F%2Ftouch.test%2Fmessages&from=main_touch"
        val idUrl = idUrl(action, appSettings, challenge, state)
        val copiedIdUrl = idUrl(action, copiedAppSettings, copiedChallenge, copiedState)
        val copiedAfterAuthIdUrl = idUrl(action, copiedAppSettings, "post_auth_$copiedChallenge", "post_auth_$copiedState")
        val pageSource = "window.init=" + JSONObject()
            .put("auth", JSONObject().put("access_token", authToken).put("anonymous_token", anonymous))
            .toString() + ";"

        val silentBase = "https://auth.test/api/v1/vkid_auth/silent/grey_login"
        val toDecoded = silentBase +
            "?state=" + state +
            "&from=" + encode(encode(idUrl)) +
            "&email=" + encode(login)
        val to = Base64.getEncoder().encodeToString(toDecoded.toByteArray(Charsets.UTF_8))
        val nextUrl = silentBase +
            "?state=" + state +
            "&from=" + encode(idUrl) +
            "&email=" + encode(login) +
            "&payload=fresh_payload_from_connect_response"
        val touchUrl = "https://touch.test/messages?afterRedir=1"
        val trackingUrl = "https://trk.test/c/puzcp2"
        val inboxUrl = "https://mailbox.test/inbox?fromTouch=1"

        val events = mutableListOf<JSONObject>()
        events.add(event("webview", 1000, "GET", accountUrl, 0))
        events.add(event("webview", 1001, "GET", accountUrl, 0))
        events.add(event("navigation", 1005, "GET", "https://portal.test/", 0))
        events.add(event("navigation", 1020, "GET", idUrl, 0))
        events.add(event("navigation", 1021, "GET", idUrl, 0))
        events.add(
            JSONObject()
                .put("source", "auth-page-source")
                .put("time", 1022)
                .put("method", "GET")
                .put("url", idUrl)
                .put("content", pageSource)
        )
        events.add(
            event("resource-copy", 1030, "GET", accountUrl, 200)
                .put("redirectURL", copiedIdUrl)
        )
        events.add(
            JSONObject()
                .put("source", "auth-form-submit")
                .put("time", 1040)
                .put("page", idUrl)
                .put("url", idUrl)
                .put("method", "GET")
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", "save_user=on")
                .put("formFields", JSONArray().put(JSONObject().put("name", "save_user").put("value", "on")))
        )
        events.add(
            event("webview", 1050, "POST", "https://api.test/method/auth.validateAccount?v=5.280&client_id=7539952", 200)
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
                .put("responseBody", response("sid", sid))
        )
        events.add(
            event("webview", 1060, "GET", "https://account.test/login?deepLink=password", 200)
                .put("responseHeaders", JSONObject().put("Set-Cookie", "oid=$oid; Path=/; Domain=account.test"))
        )
        events.add(
            JSONObject()
                .put("source", "auth-form-submit")
                .put("time", 1070)
                .put("page", idUrl)
                .put("url", idUrl)
                .put("method", "GET")
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", form("username" to login, "password" to "[password]"))
                .put(
                    "formFields",
                    JSONArray()
                        .put(JSONObject().put("name", "username").put("value", login))
                        .put(JSONObject().put("name", "password").put("value", "[password]"))
                )
        )
        events.add(
            event("webview", 1080, "POST", "https://api.test/method/vkidmail.checkPassword?v=5.280&client_id=7539952", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", form("sid" to sid, "password" to "[password]", "anonymous_token" to anonymous))
                .put("responseBody", response("next_step", "on_success_validation"))
        )
        events.add(
            event("webview", 1090, "POST", "https://api.test/method/auth.onSuccessValidation?v=5.280&client_id=7539952", 200)
                .put("requestMimeType", "application/x-www-form-urlencoded")
                .put("requestBody", form("sid" to sid, "anonymous_token" to anonymous))
                .put("responseBody", response("next_step", "mailru_mimicry_get_silent_token"))
        )
        events.add(
            event("webview", 1100, "POST", "https://login.test/?act=connect_authorize", 200)
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
                .put("responseBody", response("next_step_url", nextUrl))
        )
        events.add(
            event("webview", 1110, "GET", nextUrl, 200)
                .put("redirectURL", copiedAfterAuthIdUrl)
        )
        events.add(
            JSONObject()
                .put("source", "auth-page-source")
                .put("time", 1120)
                .put("method", "GET")
                .put("url", touchUrl)
                .put("content", "<script>window.location=" + JSONObject.quote(trackingUrl) + ";</script>")
        )
        events.add(
            event("webview", 1130, "GET", trackingUrl, 200)
                .put("redirectURL", inboxUrl)
                .put("responseHeaders", JSONObject().put("Set-Cookie", "session=fresh-authenticated-session; Path=/; HttpOnly"))
        )
        events.add(event("webview", 1140, "GET", inboxUrl, 0))
        events.add(event("webview", 1150, "GET", "https://metrics.test/collect", 204))
        events.add(event("webview", 1200, "GET", "https://auth.test/cgi-bin/logout", 200))

        val before = state(accountUrl, "")
        val after = state(inboxUrl, "session=fresh-authenticated-session")
        val result = UniversalAuthAnalyzer.analyze(events, before, after)
        val validation = PostmanReplayabilityValidator.validate(result.collectionJson)
        assertTrue(validation.issues.joinToString("\n"), validation.ok)

        val collection = JSONObject(result.collectionJson)
        val items = collection.getJSONArray("item")
        val text = collection.toString()

        assertTrue("full-shape replay must start at account login", requestUrl(items.getJSONObject(0).getJSONObject("request")).contains("account.test/login"))
        assertTrue(text.contains("auth.validateAccount"))
        assertTrue(text.contains("vkidmail.checkPassword"))
        assertTrue(text.contains("auth.onSuccessValidation"))
        assertTrue(text.contains("connect_authorize"))
        assertTrue(text.contains("grey_login"))
        assertFalse("captured copied challenge must never be replayed", text.contains(copiedChallenge))
        assertFalse("captured copied state must never be replayed", text.contains(copiedState))
        assertFalse("post-auth copied challenge must never re-enter AUTH", text.contains("post_auth_$copiedChallenge"))
        assertFalse("logout must not be included", text.contains("cgi-bin/logout"))
        assertFalse("telemetry must not be included", text.contains("metrics.test/collect"))

        for (index in 0 until items.length()) {
            val request = items.getJSONObject(index).getJSONObject("request")
            if (request.optString("method", "").equals("GET", true)) {
                assertFalse("GET step contains body: " + requestUrl(request), request.has("body"))
            }
        }

        val validate = (0 until items.length())
            .map { items.getJSONObject(it).getJSONObject("request") }
            .first { requestUrl(it).contains("auth.validateAccount") }
            .optJSONObject("body")
            .toString()
        assertTrue(validate.contains("{{login}}"))
        assertFalse(validate.contains("{{email}}"))
        assertFalse(validate.contains(login))
        assertFalse(validate.contains(device))

        val environment = PostmanEnvironmentSnapshotSafe.build(result.collectionJson, events, pageSource)
        assertFalse(environment.contains(device))
        assertFalse(environment.contains(to))
        assertFalse(environment.contains("fresh-authenticated-session"))
    }

    private fun assertTemplateField(bodyJson: String, key: String, captured: String) {
        val body = JSONObject(bodyJson)
        val values = when (body.optString("mode", "")) {
            "urlencoded" -> body.optJSONArray("urlencoded") ?: JSONArray()
            "formdata" -> body.optJSONArray("formdata") ?: JSONArray()
            else -> JSONArray()
        }
        var value = ""
        for (index in 0 until values.length()) {
            val field = values.optJSONObject(index) ?: continue
            if (field.optString("key", "") == key) {
                value = field.optString("value", "")
                break
            }
        }
        assertTrue("$key must be a runtime template, got: $value", value.startsWith("{{") && value.endsWith("}}"))
        assertFalse("$key must not reuse captured value", value.contains(captured))
    }

    private fun idUrl(action: String, appSettings: String, challenge: String, state: String): String =
        "https://id.test/auth?action=" + encode(action) +
            "&app_id=7539952" +
            "&app_settings=" + encode(appSettings) +
            "&code_challenge=" + encode(challenge) +
            "&code_challenge_method=S256" +
            "&redirect_state=" + encode(state) +
            "&redirect_uri=https%3A%2F%2Ftouch.test%2Fmessages" +
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

    private fun requestUrl(request: JSONObject): String {
        val value = request.opt("url")
        return if (value is JSONObject) value.optString("raw", value.toString()) else value?.toString().orEmpty()
    }
}
