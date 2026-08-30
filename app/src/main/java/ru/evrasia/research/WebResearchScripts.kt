package ru.evrasia.research

import org.json.JSONObject

internal object WebResearchScripts {
    fun instrumentation(): String = """
          (function(){
            if(window.__WR10)return; window.__WR10=true;
            window.__WR_REQ_HINTS=window.__WR_REQ_HINTS||[];
            const send=o=>{try{EvrasiaResearch.record(JSON.stringify(o))}catch(e){}};
            const absolute=u=>{try{return new URL(String(u||''),location.href).href}catch(e){return String(u||'')}};
            const remember=(u,m,t)=>{try{let a=window.__WR_REQ_HINTS;a.push({url:absolute(u),method:String(m||'GET').toUpperCase(),time:t});if(a.length>300)a.splice(0,a.length-300)}catch(e){}};
            const headersObject=h=>{let o={};try{new Headers(h||{}).forEach((v,k)=>o[k]=v)}catch(e){}return o};
            const bodyPreview=b=>{try{if(b==null)return'';if(typeof b==='string')return b;if(b instanceof URLSearchParams)return b.toString();if(typeof FormData!=='undefined'&&b instanceof FormData)return JSON.stringify(Array.from(b.entries()).map(([k,v])=>[k,typeof v==='string'?v:'[File '+(v?.name||'')+' '+(v?.size||0)+' bytes]']));if(typeof Blob!=='undefined'&&b instanceof Blob)return '[Blob '+(b.type||'')+' '+b.size+' bytes]';if(typeof ArrayBuffer!=='undefined'&&b instanceof ArrayBuffer)return '[ArrayBuffer '+b.byteLength+' bytes]';if(ArrayBuffer.isView?.(b))return '[TypedArray '+b.byteLength+' bytes]';return String(b)}catch(e){return'[unavailable]'}};
            const isTextual=ct=>!ct||/json|text|javascript|ecmascript|css|html|xml|x-www-form-urlencoded|graphql/.test(String(ct).toLowerCase());
            const chunk=(k,t,s)=>{t=String(t??'');let z=100000,n=Math.max(1,Math.ceil(t.length/z));for(let i=0;i<n;i++){try{s?EvrasiaResearch.scriptChunk(k,i,n,t.slice(i*z,(i+1)*z)):EvrasiaResearch.artifactChunk(k,i,n,t.slice(i*z,(i+1)*z))}catch(e){}}};
            const target=e=>{if(!e||e.nodeType!==1)return{};return{tag:(e.tagName||'').toLowerCase(),id:e.id||'',className:typeof e.className==='string'?e.className:'',name:e.name||'',type:e.type||'',role:e.getAttribute?.('role')||'',href:e.href||'',text:(e.innerText||e.textContent||'').trim().slice(0,300)}};
            ['click','change','submit'].forEach(type=>document.addEventListener(type,e=>send({source:'user-action',time:Date.now(),action:type,page:location.href,target:target(e.target)}),true));
            const HP=history.pushState.bind(history),HR=history.replaceState.bind(history);
            history.pushState=function(s,t,u){let r=HP(s,t,u);send({source:'history',time:Date.now(),action:'pushState',url:location.href,state:s});return r};
            history.replaceState=function(s,t,u){let r=HR(s,t,u);send({source:'history',time:Date.now(),action:'replaceState',url:location.href,state:s});return r};
            addEventListener('popstate',e=>send({source:'history',time:Date.now(),action:'popstate',url:location.href,state:e.state}));
            addEventListener('hashchange',e=>send({source:'history',time:Date.now(),action:'hashchange',url:location.href,oldURL:e.oldURL,newURL:e.newURL}));

            const F=window.fetch;
            if(F&&!F.__wrUnified){
              const wrapped=async function(i,n){
                const u=absolute(typeof i==='string'?i:(i&&i.url)||'');
                const m=String((n&&n.method)||(i&&i.method)||'GET').toUpperCase();
                const t=Date.now();
                let stack='';try{stack=(new Error()).stack||''}catch(e){}
                const rqHeaders=headersObject((n&&n.headers)||(i&&i.headers)||{});
                let body=bodyPreview(n&&n.body);
                if(!body&&typeof Request!=='undefined'&&i instanceof Request){try{body=await i.clone().text()}catch(e){}}
                remember(u,m,t);
                try{
                  const r=await F.apply(this,arguments);
                  const rh={};try{r.headers.forEach((v,k)=>rh[k]=v)}catch(e){}
                  const ct=(r.headers&&r.headers.get('content-type'))||'';
                  let responseBody='';
                  if(isTextual(ct)){try{responseBody=await r.clone().text()}catch(e){responseBody='[unavailable]'}}else responseBody='[binary]';
                  let responseSize=-1;try{responseSize=new TextEncoder().encode(responseBody).length}catch(e){responseSize=responseBody.length}
                  send({source:'fetch',time:t,duration:Date.now()-t,method:m,url:u,finalUrl:r.url||u,requestHeaders:rqHeaders,requestMimeType:rqHeaders['content-type']||'',requestBody:body,status:r.status,statusText:r.statusText,redirected:!!r.redirected,responseType:r.type||'',responseHeaders:rh,responseBody:responseBody,mimeType:ct,responseSize:responseSize,initiatorStack:stack});
                  return r
                }catch(e){send({source:'fetch',time:t,duration:Date.now()-t,method:m,url:u,requestHeaders:rqHeaders,requestMimeType:rqHeaders['content-type']||'',requestBody:body,initiatorStack:stack,error:String(e)});throw e}
              };
              wrapped.__wrUnified=true;window.fetch=wrapped;
            }

            try{
              const XP=XMLHttpRequest.prototype, O=XP.open, S=XP.send, H=XP.setRequestHeader;
              if(!XP.__wrUnified){
                XP.__wrUnified=true;
                XP.open=function(m,u){this.__wrMethod=String(m||'GET').toUpperCase();this.__wrUrl=absolute(u);this.__wrHeaders={};return O.apply(this,arguments)};
                XP.setRequestHeader=function(k,v){try{this.__wrHeaders[String(k).toLowerCase()]=String(v)}catch(e){};return H.apply(this,arguments)};
                XP.send=function(b){
                  const x=this,t=Date.now(),m=x.__wrMethod||'GET',u=x.__wrUrl||'';let stack='';try{stack=(new Error()).stack||''}catch(e){};const body=bodyPreview(b);remember(u,m,t);
                  x.addEventListener('loadend',()=>{let responseBody='[binary]';try{if(x.responseType===''||x.responseType==='text')responseBody=x.responseText}catch(e){};let ct='';try{ct=x.getResponseHeader('content-type')||''}catch(e){};send({source:'xhr',time:t,duration:Date.now()-t,method:m,url:u,finalUrl:x.responseURL||u,requestHeaders:x.__wrHeaders||{},requestMimeType:(x.__wrHeaders||{})['content-type']||'',requestBody:body,status:x.status,statusText:x.statusText,responseType:x.responseType||'',responseHeadersRaw:x.getAllResponseHeaders(),responseBody:responseBody,mimeType:ct,initiatorStack:stack})},{once:true});
                  return S.apply(this,arguments)
                };
              }
            }catch(e){}

            if(window.WebSocket){const W=window.WebSocket;if(!W.__wrUnified){const Wrapped=class extends W{constructor(u,p){super(u,p);this.__u=String(u);send({source:'websocket-open',time:Date.now(),url:this.__u});this.addEventListener('message',e=>send({source:'websocket-receive',time:Date.now(),url:this.__u,data:typeof e.data==='string'?e.data:'[binary]'}))}send(d){send({source:'websocket-send',time:Date.now(),url:this.__u,data:typeof d==='string'?d:'[binary]'});return super.send(d)}};Wrapped.__wrUnified=true;window.WebSocket=Wrapped}}
            if(window.EventSource){const E=window.EventSource;if(!E.__wrUnified){const Wrapped=class extends E{constructor(u,o){super(u,o);this.__u=String(u);send({source:'sse-open',time:Date.now(),url:this.__u});this.addEventListener('message',e=>send({source:'sse-message',time:Date.now(),url:this.__u,data:e.data,lastEventId:e.lastEventId||''}))}};Wrapped.__wrUnified=true;window.EventSource=Wrapped}}
            const seenScripts=new WeakSet();let dynamicInline=0;
            const archiveScript=(s,key)=>{try{if(!s||seenScripts.has(s))return;seenScripts.add(s);if(s.src)EvrasiaResearch.externalScript(String(s.src));else if(s.textContent)chunk(key||location.href+'#inline-dynamic-'+(++dynamicInline),s.textContent,true)}catch(e){}};
            Array.from(document.scripts).forEach((s,i)=>archiveScript(s,location.href+'#inline-'+i));
            let mutationAdded=0,mutationRemoved=0,mutationAttributes=0,mutationTimer=0;
            const flushMutations=()=>{mutationTimer=0;if(!(mutationAdded||mutationRemoved||mutationAttributes))return;send({source:'dom-mutation',time:Date.now(),page:location.href,mutations:[{type:'batch',added:mutationAdded,removed:mutationRemoved,attributes:mutationAttributes}]});mutationAdded=0;mutationRemoved=0;mutationAttributes=0};
            new MutationObserver(ms=>{for(const m of ms){if(m.type==='attributes'){mutationAttributes++;continue}mutationAdded+=m.addedNodes?.length||0;mutationRemoved+=m.removedNodes?.length||0;for(const n of Array.from(m.addedNodes||[])){if(!n||n.nodeType!==1)continue;if(String(n.tagName||'').toLowerCase()==='script')archiveScript(n,location.href+'#inline-dynamic-'+(++dynamicInline));try{if(n.querySelectorAll)n.querySelectorAll('script').forEach(s=>archiveScript(s,location.href+'#inline-dynamic-'+(++dynamicInline)))}catch(e){}}}if(!mutationTimer)mutationTimer=setTimeout(flushMutations,1000)}).observe(document.documentElement,{subtree:true,childList:true,attributes:true});
            addEventListener('error',e=>send({source:'js-error',time:Date.now(),message:e.message,url:e.filename||location.href,line:e.lineno||0,column:e.colno||0}));
            addEventListener('unhandledrejection',e=>send({source:'promise-rejection',time:Date.now(),message:String(e.reason)}));
            send({source:'hook',time:Date.now(),url:location.href,status:0});
          })();
        """.trimIndent()

    fun lightSnapshot(nativeCookies: String): String = """
          (function(){
            function store(s){let o={};try{for(let i=0;i<s.length;i++){let k=s.key(i);o[k]=s.getItem(k)}}catch(e){o.__error=String(e)}return o}
            const attrs=e=>{let o={};try{for(const a of e.attributes||[])o[a.name]=a.value}catch(x){}return o};
            const elements=Array.from(document.querySelectorAll('a,button,input,select,textarea,form,[role],[onclick]')).slice(0,2500).map(e=>({tag:e.tagName.toLowerCase(),attrs:attrs(e),text:(e.innerText||e.textContent||'').trim().slice(0,300)}));
            const resources=performance.getEntriesByType('resource').map(r=>({name:r.name,initiatorType:r.initiatorType,startTime:r.startTime,duration:r.duration,transferSize:r.transferSize,encodedBodySize:r.encodedBodySize,decodedBodySize:r.decodedBodySize}));
            try{EvrasiaResearch.snapshot(JSON.stringify({time:Date.now(),url:location.href,title:document.title,cookie:document.cookie,nativeCookie:${JSONObject.quote(nativeCookies)},localStorage:store(localStorage),sessionStorage:store(sessionStorage),resources:resources,elements:elements,lightweight:true}))}catch(e){}
          })();
        """.trimIndent()

    fun fullSnapshot(nativeCookies: String): String = """
          (async function(){
            const chunk=(k,t)=>{t=String(t??'');let z=100000,n=Math.max(1,Math.ceil(t.length/z));for(let i=0;i<n;i++)try{EvrasiaResearch.artifactChunk(k,i,n,t.slice(i*z,(i+1)*z))}catch(e){}};
            function store(s){let o={};try{for(let i=0;i<s.length;i++){let k=s.key(i);o[k]=s.getItem(k)}}catch(e){o.__error=String(e)}return o}
            let sw=[],cacheNames=[],db=[];
            try{if(navigator.serviceWorker){let rs=await navigator.serviceWorker.getRegistrations();sw=rs.map(r=>({scope:r.scope,active:r.active?.scriptURL||'',waiting:r.waiting?.scriptURL||'',installing:r.installing?.scriptURL||''}));sw.forEach(r=>[r.active,r.waiting,r.installing].filter(Boolean).forEach(u=>EvrasiaResearch.externalScript(String(u))))}}catch(e){sw=[{error:String(e)}]}
            try{if(window.caches){cacheNames=await window.caches.keys();for(const name of cacheNames){let c=await window.caches.open(name),keys=await c.keys();let entries=[];for(const req of keys.slice(0,250)){try{let res=await c.match(req),ct=res?.headers?.get('content-type')||'',txt='';if(res&&(/json|text|javascript|css|html|xml/.test(ct)))txt=(await res.clone().text()).slice(0,200000);entries.push({url:req.url,status:res?.status||0,contentType:ct,body:txt})}catch(e){entries.push({url:req.url,error:String(e)})}}chunk('cache-'+name+'.json',JSON.stringify({name,entries}))}}}catch(e){cacheNames=['ERROR:'+String(e)]}
            try{if(indexedDB.databases){let list=await indexedDB.databases();for(const info of list){if(!info.name)continue;let dump={name:info.name,version:info.version,stores:{}};try{let d=await new Promise((ok,bad)=>{let q=indexedDB.open(info.name);q.onsuccess=()=>ok(q.result);q.onerror=()=>bad(q.error)});for(const sn of Array.from(d.objectStoreNames)){try{let tx=d.transaction(sn,'readonly'),st=tx.objectStore(sn),vals=await new Promise((ok,bad)=>{let q=st.getAll();q.onsuccess=()=>ok(q.result);q.onerror=()=>bad(q.error)});dump.stores[sn]=vals.slice(0,1000)}catch(e){dump.stores[sn]={error:String(e)}}}d.close()}catch(e){dump.error=String(e)}chunk('indexeddb-'+info.name+'.json',JSON.stringify(dump));db.push({name:info.name,version:info.version})}}}catch(e){db=[{error:String(e)}]}
            const attrs=e=>{let o={};try{for(const a of e.attributes||[])o[a.name]=a.value}catch(x){}return o};
            const elements=Array.from(document.querySelectorAll('a,button,input,select,textarea,form,[role],[onclick]')).slice(0,10000).map(e=>({tag:e.tagName.toLowerCase(),attrs:attrs(e),text:(e.innerText||e.textContent||'').trim().slice(0,500)}));
            const resources=performance.getEntriesByType('resource').map(r=>({name:r.name,initiatorType:r.initiatorType,startTime:r.startTime,duration:r.duration,transferSize:r.transferSize,encodedBodySize:r.encodedBodySize,decodedBodySize:r.decodedBodySize}));
            try{EvrasiaResearch.snapshot(JSON.stringify({time:Date.now(),url:location.href,title:document.title,cookie:document.cookie,nativeCookie:${JSONObject.quote(nativeCookies)},localStorage:store(localStorage),sessionStorage:store(sessionStorage),serviceWorkers:sw,cacheStorage:cacheNames,indexedDB:db,resources:resources,elements:elements,html:document.documentElement.outerHTML,fullSnapshot:true}))}catch(e){}
          })();
        """.trimIndent()
}
