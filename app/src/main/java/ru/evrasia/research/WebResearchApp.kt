package ru.evrasia.research

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.*
import java.lang.ref.WeakReference

class WebResearchApp : Application(), Application.ActivityLifecycleCallbacks {
    private val handler=Handler(Looper.getMainLooper())
    private var browserRef=WeakReference<WebResearchV10Activity>(null)
    private var debuggerRef=WeakReference<NetworkDebuggerActivity>(null)
    private var mirroredCount=0
    private var advancedTick=0
    private val mirroredScripts=mutableSetOf<String>()
    private val ink=Color.rgb(3,10,15); private val surface=Color.rgb(7,18,25); private val surface2=Color.rgb(10,25,34); private val line=Color.rgb(21,57,69); private val cyan=Color.rgb(0,226,239); private val white=Color.rgb(232,244,248); private val muted=Color.rgb(113,139,151)
    private val ticker=object:Runnable{override fun run(){syncNetworkStore();advancedTick++;browserRef.get()?.let{if(advancedTick%5==0){it.ensureInstrumentation();installAdvancedCapture(it)}};handler.postDelayed(this,1000)}}
    override fun onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);handler.post(ticker)}

    fun syncNetworkStore(){
        val browser=browserRef.get()?:return
        try{
            val f=WebResearchV10Activity::class.java.getDeclaredField("archive");f.isAccessible=true
            val a=f.get(browser) as? ResearchArchive?:return
            synchronized(a){
                val n=a.records.length()
                if(n<mirroredCount){
                    NetworkDebugStore.clear()
                    mirroredScripts.clear()
                }
                mirroredCount=n
                a.scripts.forEach{(u,b)->
                    val inline=!u.startsWith("http://")&&!u.startsWith("https://")||u.contains("#inline-")
                    if(inline&&mirroredScripts.add(u))NetworkDebugStore.add(org.json.JSONObject().put("source","js-file").put("time",System.currentTimeMillis()).put("method","JS").put("url",u).put("mimeType","application/javascript").put("responseSize",b.size).put("responseBody",try{b.toString(Charsets.UTF_8)}catch(_:Exception){"[binary]"}))
                }
            }
        }catch(_:Exception){}
    }

    private fun installAdvancedCapture(a:WebResearchV10Activity){
        try{
            val f=WebResearchV10Activity::class.java.getDeclaredField("web");f.isAccessible=true
            val w=f.get(a) as? WebView?:return
            val js="""
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
            w.evaluateJavascript(js,null)
        }catch(_:Exception){}
    }

    private fun iconButton(a:Activity,kind:TechIconDrawable.Kind,strong:Boolean=false)=Button(a).apply{text="";minWidth=0;minimumWidth=0;setPadding(dp(a,9),dp(a,9),dp(a,9),dp(a,9));background=round(a,if(strong)Color.rgb(5,43,51) else surface2,10,if(strong)cyan else line);foreground=TechIconDrawable(kind,cyan)}
    private fun applyIcon(a:Activity,b:Button,kind:TechIconDrawable.Kind,strong:Boolean=false){b.text="";b.minWidth=0;b.minimumWidth=0;b.setPadding(dp(a,9),dp(a,9),dp(a,9),dp(a,9));b.background=round(a,if(strong)Color.rgb(5,43,51) else surface2,10,if(strong)cyan else line);b.foreground=TechIconDrawable(kind,cyan)}

    private fun brandBrowser(a:WebResearchV10Activity){val content=a.findViewById<ViewGroup>(android.R.id.content)?:return;val root=content.getChildAt(0) as? LinearLayout?:return;if(root.tag=="compact-cyan-v23")return;root.tag="compact-cyan-v23";if(root.childCount<7)return;root.setBackgroundColor(ink);val navCard=root.getChildAt(1) as? LinearLayout?:return;val bookmarks=root.getChildAt(2) as? LinearLayout?:return;val cookies=root.getChildAt(3) as? Button;root.getChildAt(0).visibility=View.GONE;cookies?.visibility=View.VISIBLE;root.getChildAt(4).visibility=View.GONE;root.getChildAt(6).visibility=View.GONE;bookmarks.visibility=View.GONE
        navCard.background=round(a,surface,12,line);navCard.setPadding(dp(a,4),dp(a,4),dp(a,4),dp(a,4));(navCard.layoutParams as? LinearLayout.LayoutParams)?.apply{setMargins(dp(a,5),dp(a,4),dp(a,5),dp(a,4));navCard.layoutParams=this}
        cookies?.apply{textSize=10f;typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD);setTextColor(white);gravity=Gravity.START or Gravity.CENTER_VERTICAL;background=round(a,surface,10,line);setPadding(dp(a,10),0,dp(a,10),0);layoutParams=LinearLayout.LayoutParams(-1,dp(a,38)).apply{setMargins(dp(a,5),0,dp(a,5),dp(a,4))}}
        val nav=navCard.getChildAt(0) as? LinearLayout?:return;val address=nav.getChildAt(0) as? EditText?:return;val go=nav.getChildAt(1) as? Button?:return;address.textSize=12f;address.typeface=Typeface.create(Typeface.MONOSPACE,Typeface.NORMAL);address.setTextColor(white);address.setHintTextColor(muted);address.background=round(a,surface2,9,line);address.setPadding(dp(a,10),0,dp(a,10),0);address.layoutParams=LinearLayout.LayoutParams(0,dp(a,36),1f).apply{marginStart=dp(a,4);marginEnd=dp(a,4)};applyIcon(a,go,TechIconDrawable.Kind.NAVIGATE,true);go.layoutParams=LinearLayout.LayoutParams(dp(a,38),dp(a,36))
        if(bookmarks.childCount>0)bookmarks.getChildAt(0).visibility=View.GONE;bookmarks.background=round(a,surface,10,line);bookmarks.setPadding(dp(a,5),dp(a,4),dp(a,5),dp(a,4));(bookmarks.layoutParams as? LinearLayout.LayoutParams)?.apply{setMargins(dp(a,5),0,dp(a,5),dp(a,4));bookmarks.layoutParams=this};val row=if(bookmarks.childCount>1)bookmarks.getChildAt(1) as? LinearLayout else null;row?.gravity=Gravity.CENTER_VERTICAL;row?.let{for(i in 0 until it.childCount){when(val v=it.getChildAt(i)){is Button->{v.textSize=9f;v.setTextColor(white);v.background=round(a,surface2,8,line);v.minWidth=0;v.minimumWidth=0};is Spinner->v.background=round(a,surface2,8,line)}};val bs=(0 until it.childCount).map{i->it.getChildAt(i)}.filterIsInstance<Button>();bs.firstOrNull()?.let{b->applyIcon(a,b,TechIconDrawable.Kind.NAVIGATE,true)}}
        val menu=iconButton(a,TechIconDrawable.Kind.MENU).apply{setOnClickListener{bookmarks.visibility=if(bookmarks.visibility==View.VISIBLE)View.GONE else View.VISIBLE}};nav.addView(menu,0,LinearLayout.LayoutParams(dp(a,38),dp(a,36)));val net=iconButton(a,TechIconDrawable.Kind.NETWORK,true).apply{setOnClickListener{installAdvancedCapture(a);syncNetworkStore();a.startActivity(Intent(a,NetworkDebuggerActivity::class.java))}};nav.addView(net,LinearLayout.LayoutParams(dp(a,38),dp(a,36)).apply{marginStart=dp(a,4)})}

    private fun brandDebugger(a:NetworkDebuggerActivity){val content=a.findViewById<ViewGroup>(android.R.id.content)?:return;val root=content.getChildAt(0) as? LinearLayout?:return;root.setBackgroundColor(ink);if(root.childCount>=5){val h=root.getChildAt(0) as? LinearLayout;h?.setPadding(dp(a,9),dp(a,5),dp(a,9),dp(a,4));h?.background=surfaceDrawable();(h?.getChildAt(0) as? TextView)?.apply{text="NETWORK TRACE";setTextColor(white);textSize=13f;typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD);letterSpacing=.08f};(h?.getChildAt(1) as? TextView)?.apply{text="LIVE · TIMING · PROTOCOL";setTextColor(cyan);textSize=8f;typeface=Typeface.MONOSPACE};val tools=root.getChildAt(1);tools.setBackgroundColor(surface);styleTree(a,tools);(root.getChildAt(2) as? LinearLayout)?.let{it.setPadding(dp(a,5),dp(a,3),dp(a,5),dp(a,3));styleTree(a,it)};(root.getChildAt(3) as? TextView)?.apply{setTextColor(muted);textSize=9f;typeface=Typeface.MONOSPACE;setPadding(dp(a,7),dp(a,3),dp(a,7),dp(a,3))};(root.getChildAt(4) as? ListView)?.apply{setBackgroundColor(ink);divider=null;setPadding(dp(a,4),dp(a,2),dp(a,4),dp(a,4));clipToPadding=false}};addBack(a);styleDebuggerRows(a)}
    private fun styleTree(a:Activity,v:View){when(v){is Button->{v.textSize=9f;v.setTextColor(white);v.minWidth=0;v.minimumWidth=0;v.setPadding(dp(a,7),0,dp(a,7),0);v.background=round(a,surface2,8,line)};is EditText->{v.textSize=10f;v.setTextColor(white);v.setHintTextColor(muted);v.typeface=Typeface.MONOSPACE;v.background=round(a,surface2,8,line)};is Spinner->v.background=round(a,surface2,8,line);is ViewGroup->for(i in 0 until v.childCount)styleTree(a,v.getChildAt(i))}}
    private fun styleDebuggerRows(a:NetworkDebuggerActivity){val root=(a.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? LinearLayout)?:return;val l=if(root.childCount>=5)root.getChildAt(4) as? ListView else null;l?.let{for(i in 0 until it.childCount){val r=it.getChildAt(i) as? LinearLayout?:continue;r.background=round(a,surface,8,line);r.setPadding(dp(a,8),dp(a,5),dp(a,8),dp(a,5));(r.getChildAt(0) as? TextView)?.apply{typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD);textSize=10f};(r.getChildAt(1) as? TextView)?.apply{typeface=Typeface.MONOSPACE;textSize=9f;setTextColor(muted)}}}}
    private fun addBack(a:NetworkDebuggerActivity){val c=a.findViewById<ViewGroup>(android.R.id.content)?:return;if(c.findViewWithTag<Button>("debugger-back")!=null)return;val b=iconButton(a,TechIconDrawable.Kind.BACK).apply{tag="debugger-back";setOnClickListener{a.finish()}};c.addView(b,FrameLayout.LayoutParams(dp(a,34),dp(a,32)).apply{gravity=Gravity.TOP or Gravity.END;topMargin=dp(a,28);marginEnd=dp(a,7)})}
    private fun surfaceDrawable()=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(surface,surface2))
    private fun round(a:Activity,fill:Int,r:Int,stroke:Int?=null)=GradientDrawable().apply{shape=GradientDrawable.RECTANGLE;setColor(fill);cornerRadius=dp(a,r).toFloat();stroke?.let{setStroke(dp(a,1),it)}}
    private fun dp(a:Activity,v:Int)=(v*a.resources.displayMetrics.density).toInt()
    override fun onActivityCreated(a:Activity,s:Bundle?){when(a){is WebResearchV10Activity->{browserRef=WeakReference(a);mirroredCount=0;mirroredScripts.clear();NetworkDebugStore.clear()};is NetworkDebuggerActivity->debuggerRef=WeakReference(a)}}
    override fun onActivityResumed(a:Activity){when(a){is WebResearchV10Activity->{browserRef=WeakReference(a);syncNetworkStore();brandBrowser(a);a.ensureInstrumentation();installAdvancedCapture(a)};is NetworkDebuggerActivity->{debuggerRef=WeakReference(a);browserRef.get()?.let{it.ensureInstrumentation();installAdvancedCapture(it)};syncNetworkStore();brandDebugger(a)}}}
    override fun onActivityDestroyed(a:Activity){if(a is WebResearchV10Activity&&browserRef.get()===a)browserRef.clear();if(a is NetworkDebuggerActivity&&debuggerRef.get()===a)debuggerRef.clear()}
    override fun onActivityStarted(a:Activity){};override fun onActivityPaused(a:Activity){};override fun onActivityStopped(a:Activity){};override fun onActivitySaveInstanceState(a:Activity,o:Bundle){}
}
