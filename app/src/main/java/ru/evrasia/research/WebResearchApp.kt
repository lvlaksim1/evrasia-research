package ru.evrasia.research

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.lang.ref.WeakReference

class WebResearchApp : Application(), Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private var browserRef = WeakReference<WebResearchV10Activity>(null)
    private var advancedTick = 0

    private val ticker = object : Runnable {
        override fun run() {
            advancedTick++
            browserRef.get()?.let { browser ->
                if (advancedTick % 5 == 0) {
                    browser.ensureInstrumentation()
                    installAdvancedCapture(browser)
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        WebUiTheme.applySaved(this)
        registerActivityLifecycleCallbacks(this)
        handler.post(ticker)
    }

    internal fun activeBrowserActivity(): WebResearchV10Activity? = browserRef.get()

    fun currentCookiesText(): String {
        val browser = browserRef.get() ?: return "Браузер не открыт"
        return try {
            val web = browser.researchWebView()
            val page = web?.url.orEmpty()
            val raw = if (page.isBlank()) "" else CookieManager.getInstance().getCookie(page).orEmpty()
            val cookies = raw.split(';').map { it.trim() }.filter { it.isNotBlank() }
            buildString {
                append("Страница: ").append(page.ifBlank { "—" })
                append("\nCookies текущей сессии: ").append(cookies.size)
                if (cookies.isNotEmpty()) {
                    append("\n\n")
                    cookies.forEachIndexed { index, cookie -> append(index + 1).append(". ").append(cookie).append('\n') }
                }
            }.trimEnd()
        } catch (e: Exception) {
            "Не удалось получить cookies: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun installAdvancedCapture(activity: WebResearchV10Activity) {
        try {
            val web = activity.researchWebView() ?: return
            val js = """
                (function(){
                  if(window.__WR_PERF)return;window.__WR_PERF=true;
                  const send=o=>{try{EvrasiaResearch.record(JSON.stringify(o))}catch(e){}};
                  const num=x=>Number.isFinite(x)?Math.max(0,Math.round(x*1000)/1000):0;
                  const timing=r=>({queueing:num(r.fetchStart-r.startTime),dns:num(r.domainLookupEnd-r.domainLookupStart),connect:num(r.connectEnd-r.connectStart),ssl:r.secureConnectionStart>0?num(r.connectEnd-r.secureConnectionStart):0,request:num(r.responseStart-r.requestStart),ttfb:num(r.responseStart-r.requestStart),download:num(r.responseEnd-r.responseStart),redirect:num(r.redirectEnd-r.redirectStart),worker:num(r.workerStart>0?r.fetchStart-r.workerStart:0)});
                  const cache=r=>r.transferSize===0&&r.decodedBodySize>0?(r.workerStart>0?'service-worker':'memory/disk-cache'):'network';
                  const methodFor=(url,time)=>{try{let hints=window.__WR_REQ_HINTS||[],best=null,delta=1e15;for(let i=hints.length-1;i>=0;i--){let h=hints[i];if(h.url!==url)continue;let d=Math.abs((h.time||0)-time);if(d<delta&&d<=5000){best=h;delta=d}}return best?.method||'GET'}catch(e){return 'GET'}};
                  const emit=r=>{let t=Math.round(performance.timeOrigin+r.startTime);send({source:'resource-timing',time:t,method:methodFor(r.name,t),url:r.name,initiatorType:r.initiatorType||'',duration:num(r.duration),httpVersion:r.nextHopProtocol||'',transferSize:r.transferSize||0,encodedBodySize:r.encodedBodySize||0,decodedBodySize:r.decodedBodySize||0,responseSize:r.decodedBodySize||0,cache:cache(r),deliveryType:r.deliveryType||'',renderBlockingStatus:r.renderBlockingStatus||'',redirected:r.redirectEnd>r.redirectStart,redirectStart:num(r.redirectStart),redirectEnd:num(r.redirectEnd),workerStart:num(r.workerStart),requestStart:num(r.requestStart),responseStart:num(r.responseStart),responseEnd:num(r.responseEnd),timing:timing(r)})};
                  try{performance.getEntriesByType('resource').forEach(emit);new PerformanceObserver(l=>l.getEntries().forEach(emit)).observe({type:'resource',buffered:true})}catch(e){}
                  try{let n=performance.getEntriesByType('navigation')[0];if(n)send({source:'navigation-timing',time:Math.round(performance.timeOrigin+n.startTime),method:'GET',url:n.name,httpVersion:n.nextHopProtocol||'',duration:num(n.duration),transferSize:n.transferSize||0,encodedBodySize:n.encodedBodySize||0,decodedBodySize:n.decodedBodySize||0,cache:cache(n),redirectCount:n.redirectCount||0,timing:timing(n)})}catch(e){}
                })();
            """.trimIndent()
            web.evaluateJavascript(js, null)
        } catch (_: Exception) {
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is WebResearchV10Activity) {
            browserRef = WeakReference(activity)
            NetworkDebugStore.clear()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is WebResearchV10Activity) {
            browserRef = WeakReference(activity)
            activity.ensureInstrumentation()
            installAdvancedCapture(activity)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is WebResearchV10Activity && browserRef.get() === activity) browserRef.clear()
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
