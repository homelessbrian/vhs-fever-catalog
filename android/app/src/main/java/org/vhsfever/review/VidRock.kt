package org.vhsfever.review
import org.json.JSONObject
data class VidRockEvent(val time:String,val kind:String,val url:String,val detail:String="") {
 fun toJson()=JSONObject().put("time",time).put("kind",kind).put("url",url).put("detail",detail)
}
object VidRock {
 fun movieUrl(imdbId:String):String { val id=imdbId.trim(); require(Regex("tt\\d{5,10}").matches(id)) { "Enter a valid IMDb ID (for example tt0096895)." }; return "https://vidrock.to/movie/$id" }
 val instrumentationScript = """
 (function(){
 if(window.__vhsFeverProbeInstalled)return;window.__vhsFeverProbeInstalled=true;
 const send=(k,u,d)=>{try{if(window.VidRockCapture&&u)window.VidRockCapture.record(String(k),String(u),String(d||''));}catch(_){}};
 const abs=u=>{try{return new URL(u,document.baseURI).href;}catch(_){return String(u||'');}};
 const scan=()=>{try{document.querySelectorAll('video,audio,source,iframe').forEach(e=>{const u=e.currentSrc||e.src||e.getAttribute('src');if(u)send(e.tagName.toLowerCase(),abs(u),e.type||'');if(e.tagName==='VIDEO')e.querySelectorAll('source').forEach(s=>send('media-source',abs(s.src),s.type||''));});}catch(_){}};
 const f=window.fetch;if(f)window.fetch=function(){const x=arguments[0],u=abs(typeof x==='string'?x:(x&&x.url));return f.apply(this,arguments).then(r=>{let t='';try{t=r.headers.get('content-type')||'';}catch(_){}send('fetch-response',r.url||u,'status='+r.status+(t?'; content-type='+t:''));if(r.ok&&(/\\.m3u8(?:$|[?#])/i.test(r.url||u)||/mpegurl/i.test(t))){try{r.clone().text().then(body=>send('playlist-body',r.url||u,body.slice(0,24000)+(body.length>24000?'\\n[TRUNCATED]':''))).catch(e=>send('playlist-body-error',r.url||u,String(e)));}catch(e){send('playlist-body-error',r.url||u,String(e));}}return r;}).catch(e=>{send('fetch-error',u,String(e));throw e;});};
 const xo=XMLHttpRequest.prototype.open,xs=XMLHttpRequest.prototype.send;
 XMLHttpRequest.prototype.open=function(m,u){this.__vhsUrl=abs(u);this.__vhsMethod=m;return xo.apply(this,arguments);};
 XMLHttpRequest.prototype.send=function(){this.addEventListener('loadend',function(){let t='';try{t=this.getResponseHeader('content-type')||'';}catch(_){}const u=this.responseURL||this.__vhsUrl;send('xhr-response',u,'method='+this.__vhsMethod+'; status='+this.status+(t?'; content-type='+t:''));if(this.status>=200&&this.status<300&&(/\\.m3u8(?:$|[?#])/i.test(u)||/mpegurl/i.test(t))){try{const body=typeof this.responseText==='string'?this.responseText:'';if(body)send('playlist-body',u,body.slice(0,24000)+(body.length>24000?'\\n[TRUNCATED]':''));}catch(e){send('playlist-body-error',u,String(e));}}});return xs.apply(this,arguments);};
 try{new MutationObserver(scan).observe(document.documentElement||document,{childList:true,subtree:true,attributes:true,attributeFilter:['src']});}catch(_){}
 try{new PerformanceObserver(l=>l.getEntries().forEach(e=>{if(e.name)send('resource-timing',e.name,'initiator='+e.initiatorType+'; duration='+Math.round(e.duration)+'ms');})).observe({entryTypes:['resource']});}catch(_){}
 document.addEventListener('play',e=>{const x=e.target;if(x&&(x.currentSrc||x.src))send('play-event',abs(x.currentSrc||x.src),x.tagName);},true);
 scan();send('probe',location.href,'Instrumentation installed in this document');
 })();
 """.trimIndent()
}