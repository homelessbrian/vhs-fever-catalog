package org.vhsfever.review
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Composable fun VidRockTestScreen(m:AppModel) {
 val ctx=LocalContext.current
 var imdb by remember{mutableStateOf("tt0096895")}
 var busy by remember{mutableStateOf(false)}
 var pageStatus by remember{mutableStateOf("Enter an IMDb ID and start inspection.")}
 var progress by remember{mutableIntStateOf(0)}
 var webView by remember{mutableStateOf<WebView?>(null)}
 var playbackUrl by remember{mutableStateOf("")}
 var playbackStatus by remember{mutableStateOf("Choose a captured HLS URL or paste a stream URL.")}
 var playbackError by remember{mutableStateOf<String?>(null)}
 var playbackDiagnostics by remember{mutableStateOf("No playback diagnostics yet.")}
 val player=remember(ctx){ExoPlayer.Builder(ctx).build()}
 val events=remember{mutableStateListOf<VidRockEvent>()}
 val seen=remember{ConcurrentHashMap.newKeySet<String>()}
 val media=events.filter{e->e.kind.contains("media")||e.kind.contains("video")||e.kind.contains("audio")||e.url.contains(".m3u8",true)||e.url.contains(".mp4",true)||e.url.contains(".mpd",true)||e.detail.contains("mpegurl",true)||e.detail.contains("dash",true)}
 val report=remember(events.size,imdb,pageStatus){JSONObject().put("tool","VHS Fever VidRock Playback Inspector").put("imdb_id",imdb).put("page_status",pageStatus).put("generated_at",Instant.now().toString()).put("note","Captures request metadata and up to 24 KB of HLS manifest text only. Does not collect cookies, request headers, or media segment bodies.").put("events",JSONArray().also{a->events.forEach{a.put(it.toJson())}}).toString(2)}
 DisposableEffect(player){
  val listener=object:Player.Listener{
   override fun onPlaybackStateChanged(state:Int){playbackStatus=when(state){Player.STATE_BUFFERING->"Buffering…";Player.STATE_READY->"Ready · playing";Player.STATE_ENDED->"Playback ended";else->playbackStatus}}
   override fun onPlayerError(error:PlaybackException){
    playbackError=error.message?:error.errorCodeName
    playbackStatus="Playback failed"
    val causes=generateSequence<Throwable>(error){it.cause}.take(8).joinToString("\\n"){cause->
     val http=cause as? HttpDataSource.InvalidResponseCodeException
     if(http!=null) "HTTP ${http.responseCode} · ${cause.javaClass.simpleName}: ${cause.message}"
     else "${cause.javaClass.simpleName}: ${cause.message}"
    }
    playbackDiagnostics="Media3 ${error.errorCodeName} (code ${error.errorCode})\\n${causes}"
   }
  }
  player.addListener(listener)
  onDispose{player.removeListener(listener);player.release()}
 }
 DisposableEffect(Unit){
  VidRockEventBus.sink={e->val k=e.kind+"|"+e.url+"|"+e.detail;if(seen.add(k))events.add(e)}
  onDispose{VidRockEventBus.sink=null;webView?.apply{stopLoading();removeJavascriptInterface("VidRockCapture");loadUrl("about:blank");destroy()};webView=null}
 }
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick={m.back()}){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back",tint=C.text)};Text("VidRock Playback Inspector",color=C.text,fontSize=19.sp,fontWeight=FontWeight.Bold)}
  Text("Live browser + network inspection. Only VidRock is opened; no VHS Vault or BackWhen services.",color=C.muted,fontSize=11.sp)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
   OutlinedTextField(value=imdb,onValueChange={imdb=it.trim().take(20)},label={Text("IMDb ID")},singleLine=true,shape=RoundedCornerShape(12.dp),colors=fieldColors(),modifier=Modifier.weight(1f))
   Button(onClick={val u=runCatching{VidRock.movieUrl(imdb)}.getOrElse{pageStatus=it.message?:"Invalid IMDb ID";return@Button};events.clear();seen.clear();progress=0;busy=true;pageStatus="Loading VidRock in inspection browser…";webView?.loadUrl(u)},enabled=Regex("tt\\d{5,10}").matches(imdb.trim()),colors=ButtonDefaults.buttonColors(containerColor=C.gold,contentColor=Color(0xFF1A1206))){Text("Inspect",fontWeight=FontWeight.Bold)}
  }
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
   Text(if(busy)"Loading · $progress%" else pageStatus,color=C.muted,fontSize=10.sp,modifier=Modifier.weight(1f))
   Text("${events.size} requests",color=C.blue,fontSize=11.sp,fontWeight=FontWeight.Bold)
   IconButton(onClick={val send=Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_SUBJECT,"VidRock inspection · $imdb").putExtra(Intent.EXTRA_TEXT,report);ctx.startActivity(Intent.createChooser(send,"Export inspection report"))},enabled=events.isNotEmpty()){Icon(Icons.Default.Share,"Export report",tint=C.gold)}
  }
  Surface(color=C.panel,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().weight(0.32f)){
   AndroidView(modifier=Modifier.fillMaxSize(),factory={context->
    WebView(context).apply{
     webView=this;settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.loadsImagesAutomatically=true;settings.mediaPlaybackRequiresUserGesture=true;settings.javaScriptCanOpenWindowsAutomatically=false;settings.setSupportMultipleWindows(false);settings.allowFileAccess=false;settings.allowContentAccess=false;settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
     CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
     addJavascriptInterface(object{@JavascriptInterface fun record(kind:String,url:String,detail:String){VidRockEventBus.emit(kind,url,detail)}},"VidRockCapture")
     webChromeClient=object:WebChromeClient(){override fun onProgressChanged(view:WebView?,p:Int){progress=p};override fun onConsoleMessage(msg:ConsoleMessage):Boolean{if(msg.messageLevel()==ConsoleMessage.MessageLevel.ERROR)VidRockEventBus.emit("console-error",msg.sourceId(),msg.message().take(300));return true}}
     webViewClient=object:WebViewClient(){
      override fun onPageStarted(view:WebView?,url:String?,favicon:Bitmap?){busy=true;pageStatus="Page loading…";url?.let{VidRockEventBus.emit("navigation",it,"Main-frame navigation")}}
      override fun onPageFinished(view:WebView?,url:String?){busy=false;pageStatus="Page loaded. Press Play in the player; capture remains active.";view?.evaluateJavascript(VidRock.instrumentationScript,null)}
      override fun shouldInterceptRequest(view:WebView?,request:WebResourceRequest?):WebResourceResponse?{request?.url?.toString()?.let{VidRockEventBus.emit(if(request.isForMainFrame)"document-request" else "resource-request",it,request.method+if(request.isForMainFrame)"; main frame" else "; subresource")};return null}
      override fun onReceivedError(view:WebView?,request:WebResourceRequest?,error:WebResourceError?){if(request?.isForMainFrame==true)pageStatus="Page error: ${error?.description?:"unknown"}";VidRockEventBus.emit("load-error",request?.url?.toString().orEmpty(),error?.description?.toString().orEmpty())}
     }
     loadUrl("about:blank")
    }
   },update={webView=it})
  }
  Row(verticalAlignment=Alignment.CenterVertically){Text("DISCOVERED MEDIA / MANIFESTS",color=C.gold,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));Text("${media.size} candidates",color=C.muted,fontSize=11.sp)}
  val playlists=media.filter{it.kind=="playlist-body" && it.url.contains(".m3u8",true)}.distinctBy{it.url}
  val renditions=playlists.flatMap { manifest ->
   val lines=manifest.detail.replace("\\\\n","\\n").lineSequence().toList()
   lines.mapIndexedNotNull { index,line ->
    if(line.startsWith("#EXT-X-STREAM-INF")) {
     val next=lines.drop(index+1).firstOrNull{it.trim().startsWith("http://")||it.trim().startsWith("https://")}
     next?.trim()?.let{url-> val resolution=Regex("RESOLUTION=([0-9]+x[0-9]+)").find(line)?.groupValues?.get(1) ?: "Variant"; val bitrate=Regex("BANDWIDTH=([0-9]+)").find(line)?.groupValues?.get(1)?.toIntOrNull()?.div(1000); Triple(resolution,bitrate?.let{"$it kbps"}?: "",url)}
    } else null
   }
  }.distinctBy{it.third}
  if(playlists.isEmpty()) {
   Text("No HLS manifest captured yet. Start playback in the browser above and wait for PLAYLIST-BODY.",color=C.warn,fontSize=12.sp)
  } else {
   Text("${playlists.size} master manifest(s) · ${renditions.size} quality variant(s)",color=C.muted,fontSize=11.sp)
   Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
    playlists.asReversed().forEach { candidate ->
     Surface(color=C.panel,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()) {
      Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
       Text("MASTER HLS MANIFEST",color=C.good,fontSize=11.sp,fontWeight=FontWeight.Bold)
       Text(candidate.url,color=C.blue,fontSize=9.sp,maxLines=3)
       Button(onClick={playbackUrl=candidate.url;playbackStatus="Master manifest selected · press Play URL";playbackError=null},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=C.gold,contentColor=Color(0xFF1A1206))) { Text("TEST MASTER PLAYLIST") }
      }
     }
    }
    renditions.forEach { rendition ->
     Surface(color=C.panel,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth()) {
      Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
       Text("RENDITION · ${rendition.first} · ${rendition.second}",color=C.good,fontSize=11.sp,fontWeight=FontWeight.Bold)
       Text(rendition.third,color=C.blue,fontSize=9.sp,maxLines=3)
       OutlinedButton(onClick={playbackUrl=rendition.third;playbackStatus="Selected ${rendition.first} rendition · press Play URL";playbackError=null},modifier=Modifier.fillMaxWidth()) { Text("TEST THIS QUALITY") }
      }
     }
    }
   }
  }
  Text("NATIVE PLAYER",color=C.gold,fontSize=11.sp,fontWeight=FontWeight.Bold)
  OutlinedTextField(value=playbackUrl,onValueChange={playbackUrl=it;playbackError=null},label={Text("HLS / MP4 / DASH URL")},singleLine=true,shape=RoundedCornerShape(12.dp),colors=fieldColors(),modifier=Modifier.fillMaxWidth())
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
   Button(onClick={
    val url=playbackUrl.trim()
    if(!(url.startsWith("https://")||url.startsWith("http://"))){playbackError="Enter a valid http(s) media URL"}
    else {runCatching{
     playbackError=null;playbackDiagnostics="Opening: $url";playbackStatus="Connecting…"
     // Reuse only cookies already held by Android WebView for this media host.
     // Never add them to inspector events, diagnostics, or exported reports.
     val mediaUri=Uri.parse(url)
     val hostCookies=runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
     val requestHeaders=buildMap {
      put("Referer","https://vidrock.to/")
      if(mediaUri.host=="vercetti.org" || mediaUri.host=="vidrock.to") {
       if(!hostCookies.isNullOrBlank()) put("Cookie",hostCookies)
      }
     }
     // Match the actual inspection WebView User-Agent instead of a guessed Chrome version.
     val browserUserAgent=webView?.settings?.userAgentString?.takeIf { it.isNotBlank() }
       ?: System.getProperty("http.agent").orEmpty().ifBlank { "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36" }
     val http=DefaultHttpDataSource.Factory().setUserAgent(browserUserAgent)
       .setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(requestHeaders)
     player.setMediaSource(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ctx).setDataSourceFactory(http).createMediaSource(MediaItem.fromUri(Uri.parse(url))))
     player.prepare();player.playWhenReady=true
    }.onFailure{playbackError=it.message;playbackStatus="Could not start playback"}}
   },enabled=playbackUrl.isNotBlank(),colors=ButtonDefaults.buttonColors(containerColor=C.gold,contentColor=Color(0xFF1A1206))){Text("Play URL",fontWeight=FontWeight.Bold)}
   OutlinedButton(onClick={player.stop();playbackStatus="Stopped";playbackError=null}){Text("Stop")}
   Text(playbackStatus,color=C.muted,fontSize=10.sp,modifier=Modifier.weight(1f))
  }
  OutlinedButton(onClick={
   val target=playbackUrl.trim()
   if(target.startsWith("https://")||target.startsWith("http://")) {
    playbackStatus="Testing same URL in inspection WebView…"
    val js="(async()=>{try{const r=await fetch("+JSONObject.quote(target)+",{credentials:'include'});const t=await r.text();VidRockCapture.record('webview-direct-test',r.url,'status='+r.status+'; content-type='+(r.headers.get('content-type')||'')+'; body='+t.slice(0,1200));}catch(e){VidRockCapture.record('webview-direct-test-error',"+JSONObject.quote(target)+",String(e));}})();"
    webView?.evaluateJavascript(js,null)
   }
  },enabled=playbackUrl.isNotBlank()&&webView!=null,modifier=Modifier.fillMaxWidth()){Text("TEST SAME URL IN WEBVIEW")}
  playbackError?.let{Text("Player error: $it",color=C.bad,fontSize=11.sp)}
  Text(playbackDiagnostics,color=C.muted,fontSize=10.sp)
  Surface(color=Color.Black,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().aspectRatio(16f / 9f)){
   AndroidView(factory={context->PlayerView(context).apply{this.player=player;useController=true;keepScreenOn=true}},update={it.player=player},modifier=Modifier.fillMaxSize())
  }
  Text("Tap a captured .m3u8 candidate above to load it here, then press Play URL. Native playback uses the manifest URL; the browser session may still be required by the host.",color=C.muted,fontSize=10.sp)
  Text("Signed URLs may contain temporary tokens. Export includes URLs but never cookies or request headers; share privately.",color=C.muted,fontSize=10.sp)
 }
}
private object VidRockEventBus{
 @Volatile var sink:((VidRockEvent)->Unit)?=null
 private val main=android.os.Handler(android.os.Looper.getMainLooper())
 fun emit(kind:String,url:String,detail:String){if(url.isBlank())return;val e=VidRockEvent(Instant.now().toString(),kind,url,detail);main.post{sink?.invoke(e)}}
}