package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

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

        runNodeReplay(result.collectionJson)

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

    private fun runNodeReplay(collectionJson: String) {
        val encodedCollection = Base64.getEncoder().encodeToString(collectionJson.toByteArray(Charsets.UTF_8))
        val lines = mutableListOf<String>()
        lines.add("const collection = JSON.parse(Buffer.from(" + JSONObject.quote(encodedCollection) + ", 'base64').toString('utf8'));")
        lines.add("const vars=Object.create(null); for(const v of (collection.variable||[])) vars[String(v.key||'')]=String(v.value||'');")
        lines.add("const env={login:'user@example.test',password:'secret'}; const runtime={device:'',connected:false,authenticated:false};")
        lines.add("const fresh={challenge:'fresh_challenge_'+ 'A'.repeat(30),state:'fresh_state_'+ 'B'.repeat(26),oid:'fresh_oid_'+ 'C'.repeat(20),anonymous:'fresh_anonymous_'+ 'D'.repeat(28),authToken:'fresh_auth_token_'+ 'E'.repeat(28),sid:'fresh_sid_'+ 'F'.repeat(24),payload:'fresh_payload_'+ 'G'.repeat(26),session:'fresh_session_'+ 'H'.repeat(28)};")
        lines.add("const account='http://127.0.0.1:18080',id='http://127.0.0.1:18081',api='http://127.0.0.1:18082',loginHost='http://127.0.0.1:18083',auth='http://127.0.0.1:18084',touch='http://127.0.0.1:18085';")
        lines.add("const enc=v=>encodeURIComponent(String(v)); const b64json=v=>Buffer.from(JSON.stringify(v)).toString('base64');")
        lines.add("function freshIdUrl(){const action=b64json({name:'mail_auth',params:{mail_auth_type:'auth_login_page'}});const settings=b64json({service_groups:{oid:fresh.oid}});return id+'/auth?action='+enc(action)+'&app_id=7539952&app_settings='+enc(settings)+'&code_challenge='+enc(fresh.challenge)+'&code_challenge_method=S256&redirect_state='+enc(fresh.state)+'&redirect_uri='+enc(touch+'/messages')+'&response_type=silent_token';}")
        lines.add("function expectedTo(){return auth+'/api/v1/vkid_auth/silent/grey_login?state='+fresh.state+'&from='+enc(enc(freshIdUrl()))+'&email='+enc(env.login);}")
        lines.add("function nextUrl(){return auth+'/api/v1/vkid_auth/silent/grey_login?state='+fresh.state+'&from='+enc(freshIdUrl())+'&email='+enc(env.login)+'&payload='+enc(fresh.payload);}")
        lines.add("function value(k){return Object.prototype.hasOwnProperty.call(env,k)?env[k]:(vars[k]==null?'':String(vars[k]));}")
        lines.add("function render(x){return String(x==null?'':x).replace(/\\{\\{\\s*([^{}]+?)\\s*}}/g,(_,k)=>value(String(k).trim()));}")
        lines.add("function reqUrl(r){const raw=(r.url&&typeof r.url==='object')?(r.url.raw||''):(r.url||'');return render(raw);}")
        lines.add("function reqBody(r){const b=r.body||{},o=Object.create(null),list=b.urlencoded||b.formdata||[];for(const f of list){if(f&&f.key!=null)o[String(f.key)]=render(f.value||'');}if(b.raw){for(const pair of render(b.raw).split('&')){const p=pair.indexOf('=');if(p>0)o[decodeURIComponent(pair.slice(0,p))]=decodeURIComponent(pair.slice(p+1).replace(/\\+/g,' '));}}return o;}")
        lines.add("function resp(status,body,headers){return {status,body:String(body||''),headers:headers||{}};} function reject(m){throw new Error('MOCK REJECT: '+m);} function eq(a,e,l){if(String(a)!==String(e))reject(l+' expected='+e+' actual='+a);}")
        lines.add("function respond(method,urlText,body){const u=new URL(urlText),port=u.port,path=u.pathname;")
        lines.add("if(method==='GET'&&port==='18080'&&path==='/login')return resp(200,'<script>location.href='+JSON.stringify(freshIdUrl())+';</script>');")
        lines.add("if(method==='GET'&&port==='18081'&&path==='/auth'){eq(u.searchParams.get('code_challenge'),fresh.challenge,'id challenge');eq(u.searchParams.get('redirect_state'),fresh.state,'id state');const s=JSON.parse(Buffer.from(u.searchParams.get('app_settings'),'base64').toString('utf8'));eq(s.service_groups.oid,fresh.oid,'id oid');return resp(200,'window.init='+JSON.stringify({auth:{access_token:fresh.authToken,anonymous_token:fresh.anonymous}})+';');}")
        lines.add("if(method==='POST'&&port==='18082'&&path==='/method/auth.validateAccount'){eq(body.login,env.login,'validate login');eq(body.client_id,'7539952','validate client');eq(body.mail_token,fresh.state,'validate state');eq(body.oid,fresh.oid,'validate oid');eq(body.code_challenge,fresh.challenge,'validate challenge');eq(body.anonymous_token,fresh.anonymous,'validate anonymous');if(!body.device_id||body.device_id==='DeviceId_abcdefghijkl')reject('device_id stale');runtime.device=body.device_id;return resp(200,JSON.stringify({response:{sid:fresh.sid}}));}")
        lines.add("if(method==='POST'&&port==='18082'&&path==='/method/vkidmail.checkPassword'){eq(body.sid,fresh.sid,'password sid');eq(body.password,env.password,'password input');eq(body.anonymous_token,fresh.anonymous,'password anonymous');return resp(200,JSON.stringify({response:{next_step:'on_success_validation'}}));}")
        lines.add("if(method==='POST'&&port==='18082'&&path==='/method/auth.onSuccessValidation'){eq(body.sid,fresh.sid,'success sid');eq(body.anonymous_token,fresh.anonymous,'success anonymous');return resp(200,JSON.stringify({response:{next_step:'connect_authorize'}}));}")
        lines.add("if(method==='POST'&&port==='18083'&&path==='/'&&u.searchParams.get('act')==='connect_authorize'){eq(body.auth_token,fresh.authToken,'connect auth_token');eq(body.sid,fresh.sid,'connect sid');eq(body.device_id,runtime.device,'connect device');eq(body.service_group,'oid_'+fresh.oid,'connect service_group');eq(body.oauth_state,fresh.state,'connect state');let d='';try{d=Buffer.from(body.to,'base64').toString('utf8');}catch(e){reject('to invalid base64');}eq(d,expectedTo(),'connect to');runtime.connected=true;return resp(200,JSON.stringify({response:{next_step_url:nextUrl()}}));}")
        lines.add("if(method==='GET'&&port==='18084'&&path==='/api/v1/vkid_auth/silent/grey_login'){if(!runtime.connected)reject('silent before connect');eq(u.searchParams.get('state'),fresh.state,'silent state');eq(u.searchParams.get('email'),env.login,'silent email');eq(u.searchParams.get('payload'),fresh.payload,'silent payload');runtime.authenticated=true;return resp(200,'<html>authenticated</html>',{'Set-Cookie':'session='+fresh.session+'; Path=/; HttpOnly'});}reject('unexpected '+method+' '+urlText);}")
        lines.add("function scripts(item,listen,pm){for(const ev of (item.event||[])){if(ev.listen!==listen)continue;const ex=((ev.script||{}).exec||[]),src=Array.isArray(ex)?ex.join('\\n'):String(ex||'');if(src.trim())Function('pm','URL','atob','btoa',src)(pm,URL,atob,btoa);}}")
        lines.add("for(const item of (collection.item||[])){const r=item.request||{};let current=reqUrl(r);const pm={collectionVariables:{get:k=>vars[k],set:(k,v)=>{vars[k]=String(v);},unset:k=>{delete vars[k];}},variables:{get:k=>value(k)},request:{url:{toString:()=>current}},response:null};scripts(item,'prerequest',pm);current=reqUrl(r);pm.request.url={toString:()=>current};const method=String(r.method||'GET').toUpperCase(),body=reqBody(r),x=respond(method,current,body);pm.response={text:()=>x.body,json:()=>JSON.parse(x.body),headers:{get:key=>{const n=Object.keys(x.headers).find(h=>h.toLowerCase()===String(key).toLowerCase());return n?x.headers[n]:null;}}};scripts(item,'test',pm);}if(!runtime.authenticated)throw new Error('AUTH replay never reached session');console.log('AUTH_NODE_REPLAY_OK');")

        val file = File.createTempFile("web-research-auth-node-", ".js")
        try {
            file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
            val process = ProcessBuilder("node", file.absolutePath).redirectErrorStream(true).start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue("Node AUTH replay timed out. Output:\n$output", finished)
            assertTrue("Node AUTH replay failed. Output:\n$output", process.exitValue() == 0)
            assertTrue("Node AUTH replay marker missing. Output:\n$output", output.contains("AUTH_NODE_REPLAY_OK"))
        } finally {
            file.delete()
        }
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
