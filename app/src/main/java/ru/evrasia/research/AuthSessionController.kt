package ru.evrasia.research

import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.json.JSONTokener

internal class AuthSessionController(
    private val activity: AppCompatActivity,
    private val web: WebView
) {
    private var active = false
    private var startRevision = -1L
    private var startTime = 0L
    private var beforeState: AuthFlowAnalyzer.BrowserState? = null
    private var previousRecording = true

    fun isActive(): Boolean = active

    fun toggle() {
        if (active) finish() else start()
    }

    fun start() {
        if (active) return
        previousRecording = NetworkDebugStore.recording
        NetworkDebugStore.recording = true
        startRevision = NetworkDebugStore.revision()
        startTime = System.currentTimeMillis()
        captureState { state ->
            beforeState = state
            active = true
            injectAuthHooks()
            Toast.makeText(
                activity,
                "AUTH-анализ запущен. Выполните вход на сайте и снова нажмите AUTH.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun finish() {
        if (!active) return
        disableAuthHooks()
        captureState { after ->
            val before = beforeState
            active = false
            beforeState = null
            val delta = NetworkDebugStore.delta(startRevision)
            restoreRecording()
            if (before == null) {
                Toast.makeText(activity, "Не найден начальный снимок AUTH-сессии", Toast.LENGTH_LONG).show()
                return@captureState
            }
            val events = delta.events.filter { event ->
                val time = event.optLong("time", 0L)
                time <= 0L || time >= startTime - 1500L
            }
            try {
                val result = AuthFlowAnalyzer.analyze(events, before, after)
                Toast.makeText(
                    activity,
                    "AUTH: ${result.confidence} · ${result.requestCount} запросов",
                    Toast.LENGTH_SHORT
                ).show()
                PostmanDelivery.deliver(
                    activity,
                    result.collectionJson,
                    result.loginUrl.ifBlank { after.url.ifBlank { before.url } }
                )
            } catch (error: Exception) {
                Toast.makeText(
                    activity,
                    "AUTH-анализ: ${error.message ?: "не удалось построить коллекцию"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun cancel() {
        if (!active) return
        disableAuthHooks()
        active = false
        beforeState = null
        restoreRecording()
    }

    private fun restoreRecording() {
        NetworkDebugStore.recording = previousRecording
    }

    private fun captureState(onReady: (AuthFlowAnalyzer.BrowserState) -> Unit) {
        val page = web.url.orEmpty()
        val nativeCookies = CookieManager.getInstance().getCookie(page).orEmpty()
        val script = """
            (function(){
              function store(s){
                var o={};
                try{for(var i=0;i<s.length;i++){var k=s.key(i);o[k]=s.getItem(k)}}catch(e){o.__error=String(e)}
                return o;
              }
              return JSON.stringify({
                time:Date.now(),
                url:location.href,
                cookie:document.cookie,
                localStorage:store(localStorage),
                sessionStorage:store(sessionStorage)
              });
            })();
        """.trimIndent()
        web.evaluateJavascript(script) { raw ->
            activity.runOnUiThread {
                val objectValue = decodeJavascriptJson(raw)
                val state = AuthFlowAnalyzer.BrowserState(
                    time = objectValue.optLong("time", System.currentTimeMillis()),
                    url = objectValue.optString("url", page),
                    nativeCookies = nativeCookies,
                    documentCookies = objectValue.optString("cookie", ""),
                    localStorage = objectValue.optJSONObject("localStorage") ?: JSONObject(),
                    sessionStorage = objectValue.optJSONObject("sessionStorage") ?: JSONObject()
                )
                onReady(state)
            }
        }
    }

    private fun decodeJavascriptJson(raw: String?): JSONObject {
        if (raw.isNullOrBlank() || raw == "null") return JSONObject()
        return try {
            val first = JSONTokener(raw).nextValue()
            when (first) {
                is String -> JSONObject(first)
                is JSONObject -> first
                else -> JSONObject()
            }
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun injectAuthHooks() {
        val script = """
            (function(){
              window.__WR_AUTH_ACTIVE=true;
              if(window.__WR_AUTH_INSTALLED)return;
              window.__WR_AUTH_INSTALLED=true;
              const send=o=>{try{EvrasiaResearch.record(JSON.stringify(o))}catch(e){}};
              const absolute=u=>{try{return new URL(String(u||''),location.href).href}catch(e){return String(u||'')}};
              const safeField=e=>{
                const type=String(e.type||'').toLowerCase();
                let value='';
                if(type==='password')value='[password]';
                else if(type==='checkbox'||type==='radio'){if(!e.checked)return null;value=String(e.value||'on')}
                else if(type==='file')value='[file]';
                else value=String(e.value==null?'':e.value);
                return {name:String(e.name||''),type:type,value:value};
              };
              document.addEventListener('submit',function(ev){
                if(!window.__WR_AUTH_ACTIVE)return;
                try{
                  const form=ev.target;
                  if(!form||String(form.tagName||'').toLowerCase()!=='form')return;
                  const fields=[];
                  const pairs=[];
                  for(const element of Array.from(form.elements||[])){
                    const field=safeField(element);
                    if(!field||!field.name)continue;
                    fields.push(field);
                    pairs.push([field.name,field.value]);
                  }
                  const method=String(form.method||'GET').toUpperCase();
                  const enctype=String(form.enctype||'application/x-www-form-urlencoded');
                  let body='';
                  if(/x-www-form-urlencoded/i.test(enctype)){
                    const p=new URLSearchParams();pairs.forEach(x=>p.append(x[0],x[1]));body=p.toString();
                  }else{
                    body=JSON.stringify(pairs);
                  }
                  send({
                    source:'auth-form-submit',
                    time:Date.now(),
                    page:location.href,
                    url:absolute(form.action||location.href),
                    method:method,
                    requestMimeType:enctype,
                    requestBody:body,
                    formFields:fields
                  });
                }catch(e){}
              },true);

              try{
                const proto=Storage.prototype;
                if(!proto.__wrAuthWrapped){
                  proto.__wrAuthWrapped=true;
                  const setItem=proto.setItem;
                  const removeItem=proto.removeItem;
                  const clear=proto.clear;
                  const storeName=s=>{try{return s===localStorage?'localStorage':s===sessionStorage?'sessionStorage':'storage'}catch(e){return'storage'}};
                  proto.setItem=function(k,v){
                    let old='';try{old=this.getItem(k)||''}catch(e){}
                    const result=setItem.apply(this,arguments);
                    if(window.__WR_AUTH_ACTIVE)send({source:'auth-storage',time:Date.now(),url:location.href,store:storeName(this),action:'setItem',key:String(k),oldValue:old,value:String(v)});
                    return result;
                  };
                  proto.removeItem=function(k){
                    let old='';try{old=this.getItem(k)||''}catch(e){}
                    const result=removeItem.apply(this,arguments);
                    if(window.__WR_AUTH_ACTIVE)send({source:'auth-storage',time:Date.now(),url:location.href,store:storeName(this),action:'removeItem',key:String(k),oldValue:old});
                    return result;
                  };
                  proto.clear=function(){
                    const result=clear.apply(this,arguments);
                    if(window.__WR_AUTH_ACTIVE)send({source:'auth-storage',time:Date.now(),url:location.href,store:storeName(this),action:'clear'});
                    return result;
                  };
                }
              }catch(e){}
              send({source:'auth-session',time:Date.now(),url:location.href,action:'started'});
            })();
        """.trimIndent()
        web.evaluateJavascript(script, null)
    }

    private fun disableAuthHooks() {
        web.evaluateJavascript(
            "try{window.__WR_AUTH_ACTIVE=false;EvrasiaResearch.record(JSON.stringify({source:'auth-session',time:Date.now(),url:location.href,action:'stopped'}))}catch(e){};void 0;",
            null
        )
    }
}
