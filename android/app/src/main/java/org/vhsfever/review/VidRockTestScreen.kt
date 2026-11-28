package org.vhsfever.review
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.*
import androidx.compose.foundation.layout.*
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
 val events=remember{mutableStateListOf<VidRockEvent>()}
 val seen=remember{ConcurrentHashMap.newKeySet<String>()}
 val media=events.filter{e->e.kind.contains("media")||e.kind.contains("video")||e.kind.contains("audio")||e.url.contains(".m3u8",true)||e.url.contains(".mp4",true)||e.url.contains(".mpd",true)||e.detail.contains("mpegurl",true)||e.detail.contains("dash",true)}
 val report=remember(events.size,imdb,pageStatus){JSONObject().put("tool","VHS Fever VidRock Playback Inspector").put("imdb_id",imdb).put("page_status",pageStatus).put("generated_at",Instant.now().toString()).put("note","Request metadata only. No cookies, request headers, or response bodies are collected.").put("events",JSONArray().also{a->events.forEach{a.put(it.toJson())}}).toString(2)}
 DisposableEffect(Unit){
  VidRockEventBus.sink={e->val k=e.kind+"|"+e.url+"|"+e.detail;if(seen.add(k))events.add(e)}
  onDispose{VidRockEventBus.sink=null;webView?.apply{stopLoading();removeJavascriptInterface("VidRockCapture");loadUrl("about:blank");destroy()};webView=null}
 }
 Column(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick={m.back()}){Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back",tint=C.text)};Text("VidRock Playback Inspector",color=C.text,fontSize=19.sp,fontWeight=FontWeight.Bold)}
  Text("Live browser + network inspection. Only VidRock is opened; no VHS Vault or BackWhen services.",color=C.muted,fontSize=11.sp)
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
   OutlinedTextField(value=imdb,onValueChange={imdb=it.trim().take(20)},label={Text("IMDb ID")},singleLine=true,shape=RoundedCornerShape(12.dp),colors=fieldColors(),modifier=Modifier.weight(1f))
   Button(onClick={val u=runCatching{VidRock.movieUrl(imdb)}.getOrElse{pageStatus=it.message?:"Invalid IMDb ID";return@Button};events.clear();seen.clear();progress=0;busy=true;pageStatus="Loading VidRock in inspection browser…";webView?.loadUrl(u)},enabled=Regex("tt\\d{5,10}").matches(imdb.trim()),colors=ButtonDefaults.buttonColors(containerColor=C.gold,contentColor=Color(0xFF1A1206))){Text("Inspect",fontWeight=FontWeight.Bold)}
  }
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
   Text(if(busy)"Loading · $progress%" else pageStatus,color=C.muted,fontSize=10.sp,modifier=Modifier.weight(1f))
   Text("\${events.size} requests",color=C.blue,fontSize=11.sp,fontWeight=FontWeight.Bold)
   IconButton(onClick={val send=Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_SUBJECT,"VidRock inspection · $imdb").putExtra(Intent.EXTRA_TEXT,report);ctx.startActivity(Intent.createChooser(send,"Export inspection report"))},enabled=events.isNotEmpty()){Icon(Icons.Default.Share,"Export report",tint=C.gold)}
  }
  Surface(color=C.panel,shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().weight(0.55f)){
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
      override fun onReceivedError(view:WebView?,request:WebResourceRequest?,error:WebResourceError?){if(request?.isForMainFrame==true)pageStatus="Page error: \${error?.description?:"unknown"}";VidRockEventBus.emit("load-error",request?.url?.toString().orEmpty(),error?.description?.toString().orEmpty())}
     }
     loadUrl("about:blank")
    }
   },update={webView=it})
  }
  Row(verticalAlignment=Alignment.CenterVertically){Text("DISCOVERED MEDIA / MANIFESTS",color=C.gold,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));Text("\${media.size} candidates",color=C.muted,fontSize=11.sp)}
  if(media.isEmpty())Text("No media URL detected yet. Start playback in the browser above. Player API and script requests are still being recorded.",color=C.warn,fontSize=12.sp)
  else LazyColumn(Modifier.fillMaxWidth().weight(0.25f),verticalArrangement=Arrangement.spacedBy(4.dp)){items(media.takeLast(30).asReversed()){e->Column(Modifier.fillMaxWidth().padding(vertical=3.dp)){Text(e.kind.uppercase(),color=C.good,fontSize=10.sp,fontWeight=FontWeight.Bold);Text(e.url,color=C.blue,fontSize=10.sp);if(e.detail.isNotBlank())Text(e.detail,color=C.muted,fontSize=10.sp)}}}
  Text("Signed URLs may contain temporary tokens. Export includes URLs but never cookies or request headers; share privately.",color=C.muted,fontSize=10.sp)
 }
}
private object VidRockEventBus{
 @Volatile var sink:((VidRockEvent)->Unit)?=null
 private val main=android.os.Handler(android.os.Looper.getMainLooper())
 fun emit(kind:String,url:String,detail:String){if(url.isBlank())return;val e=VidRockEvent(Instant.now().toString(),kind,url,detail);main.post{sink?.invoke(e)}}
}