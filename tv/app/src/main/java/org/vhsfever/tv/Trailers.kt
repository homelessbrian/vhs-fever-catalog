package org.vhsfever.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The movie's trailers on YouTube, found through TMDB. Best one first; empty when there are none. */
object Trailers {
    private val mem = HashMap<String, List<String>>()
    private var loaded = false
    private fun file(ctx: Context) = File(ctx.filesDir, "trailers-v1.json")

    suspend fun find(ctx: Context, t: Title): List<String> = withContext(Dispatchers.IO) {
        if (t.adult || BuildConfig.TMDB_TOKEN.isBlank()) return@withContext emptyList()
        synchronized(mem) {
            if (!loaded) {
                loaded = true
                runCatching {
                    val o = JSONObject(file(ctx).readText())
                    for (k in o.keys()) o.optJSONArray(k)?.let { a -> mem[k] = (0 until a.length()).map { a.optString(it) } }
                }
            }
            mem[t.key]?.let { return@withContext it }
        }
        // a failed lookup (no internet) isn't remembered, so it's tried again next time
        val keys = runCatching { lookup(t) }.getOrNull() ?: return@withContext emptyList()
        synchronized(mem) {
            mem[t.key] = keys
            val o = JSONObject()
            for ((k, v) in mem) o.put(k, JSONArray(v))
            runCatching { file(ctx).writeText(o.toString()) }
        }
        keys
    }

    private fun lookup(t: Title): List<String> {
        val type = if (t.tmdbType == "tv") "tv" else "movie"
        val id = t.tmdbId ?: run {
            val y = t.searchYear?.let { "&year=$it" } ?: ""
            JSONObject(get("$API/search/movie?include_adult=false&query=${URLEncoder.encode(t.searchName, "UTF-8")}$y"))
                .optJSONArray("results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
        } ?: return emptyList()
        val list = JSONObject(get("$API/$type/$id/videos?language=en-US&include_video_language=en,null")).optJSONArray("results")
            ?: return emptyList()
        return best((0 until list.length()).mapNotNull { list.optJSONObject(it) })
    }

    /** Real trailers first, then teasers; the studio's own uploads and English ones before others. */
    fun best(videos: List<JSONObject>): List<String> = videos
        .filter { it.optString("site") == "YouTube" && it.optString("key").isNotBlank() && it.optString("type") in setOf("Trailer", "Teaser") }
        .sortedWith(compareBy(
            { if (it.optString("type") == "Trailer") 0 else 1 },
            { if (it.optBoolean("official")) 0 else 1 },
            { if (it.optString("iso_639_1") == "en") 0 else 1 },
        ))
        .map { it.optString("key") }.distinct().take(5)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("Authorization", "Bearer ${BuildConfig.TMDB_TOKEN}")
        c.setRequestProperty("Accept", "application/json")
        if (c.responseCode !in 200..299) throw Exception("TMDB HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Looks up trailers ahead of time (for the boxes next to the one you're on), so they start sooner. */
    suspend fun prefetch(ctx: Context, titles: List<Title>) { for (t in titles) runCatching { find(ctx, t) } }

    private const val API = "https://api.themoviedb.org/3"
}

/** Looks up [t]'s trailers; null while looking, empty when there are none. */
@Composable
fun rememberTrailers(t: Title?): List<String>? {
    val ctx = LocalContext.current
    val inspection = LocalInspectionMode.current
    val keys by produceState<List<String>?>(null, t?.key) {
        value = null
        value = if (t == null || inspection) emptyList() else Trailers.find(ctx, t)
    }
    return keys
}

/**
 * YouTube's own player in a web page, no buttons or menus on it. Plays the first of [keys] that YouTube
 * allows outside its site, moving on to the next if one is blocked.
 */
@SuppressLint("SetJavaScriptEnabled")
class YouTubePlayer(ctx: Context, private val muted: Boolean, private val loop: Boolean, private val pageWidth: Int? = null) {
    val view = WebView(ctx)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var keys: List<String> = emptyList()
    private var index = 0
    private var startAt = 0f
    var onShowing: (Boolean) -> Unit = {}
    var onTime: (Float) -> Unit = {}
    var onState: (Int) -> Unit = {}
    var onFailed: () -> Unit = {}
    var time = 0f; private set
    /** How long the trailer is, in seconds (0 until YouTube says). */
    var duration = 0f; private set
    val key get() = keys.getOrNull(index)

    init {
        view.setBackgroundColor(AColor.BLACK)
        // the remote stays with the app, not the web page
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = false
        if (pageWidth != null) {
            // YouTube won't reliably play in a player smaller than 200×200 web pixels, and the little TVs are
            // smaller than that. So the page is laid out wider and shrunk to fit the TV.
            view.settings.useWideViewPort = true
            view.settings.loadWithOverviewMode = true
            view.settings.setSupportZoom(false)
        }
        view.webChromeClient = WebChromeClient()
        view.addJavascriptInterface(object {
            @JavascriptInterface fun ready() = main.post { ready = true; startCurrent() }.let { }
            @JavascriptInterface fun state(s: Int) = main.post {
                if (s == 1) onShowing(true)
                onState(s)
            }.let { }
            @JavascriptInterface fun time(t: Float, d: Float) = main.post { time = t; if (d > 0) duration = d; onTime(t) }.let { }
            // 2 bad id, 5 can't play here, 100 removed, 101/150 the owner doesn't allow it outside YouTube,
            // 152/153 YouTube couldn't tell which page it's on
            @JavascriptInterface fun error(code: Int) = main.post { next() }.let { }
        }, "Android")
        // YouTube refuses to play ("Error 153") unless the page it's on is a real web address that tells it
        // where it is. So the player page is opened at our own address and handed over by the app itself.
        val html = page()
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, req: WebResourceRequest): WebResourceResponse? =
                if (req.url.toString().startsWith("$ORIGIN/")) WebResourceResponse("text/html", "utf-8", html.byteInputStream())
                else null
        }
        view.loadUrl("$ORIGIN/player.html")
    }

    /** Plays [newKeys] (starting with [first], [atSec] seconds in); an empty list stops. */
    fun play(newKeys: List<String>, first: Int = 0, atSec: Float = 0f) {
        if (newKeys == keys && first == index) return
        keys = newKeys; index = first; startAt = atSec; time = atSec
        onShowing(false)
        if (keys.isEmpty()) js("stop()") else startCurrent()
    }

    private fun startCurrent() {
        val k = key ?: return
        if (ready) js("start('$k', ${startAt})")
    }

    private fun next() {
        onShowing(false)
        index++; startAt = 0f
        if (index < keys.size) startCurrent() else onFailed()
    }

    fun toggle() = js("toggle()")
    fun seek(sec: Int) = js("seek($sec)")
    fun seekTo(sec: Float) { time = sec; js("seekTo($sec)") }
    fun pause() = js("pause()")
    fun resume() = js("resume()")
    /** Plays whatever state it's in (paused, stopped or finished). */
    fun playNow() = js("playNow()")
    /** Closed captions on or off. They start off. */
    fun captions(on: Boolean) = js("captions($on)")
    fun release() { view.stopLoading(); view.destroy() }

    private fun js(code: String) = view.evaluateJavascript("try{$code}catch(e){}", null)

    private fun page() = """
<!DOCTYPE html><html><head><meta name="viewport" content="${pageWidth?.let { "width=$it" } ?: "width=device-width, initial-scale=1"}">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}
#p,iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0;pointer-events:none}</style></head>
<body><iframe id="p" src="about:blank" referrerpolicy="strict-origin-when-cross-origin"
  allow="autoplay; encrypted-media; picture-in-picture"></iframe>
<script>
var player=null, ready=false, pending=null, ccOn=false, muted=$muted, loop=$loop, origin='$ORIGIN';
function onYouTubeIframeAPIReady(){ Android.ready(); }
// captions stay off unless asked for (YouTube sometimes turns them on by itself)
function cc(p){ try{ if(ccOn){ p.loadModule('captions'); p.setOption('captions','track',{languageCode:'en'}); }
  else { p.unloadModule('captions'); p.unloadModule('cc'); } }catch(e){} }
function captions(on){ ccOn=on; if(player && ready) cc(player); }
function start(id, at){
  // a new trailer before YouTube's player is ready waits for it, instead of making a second player
  if(player){ if(ready) player.loadVideoById({videoId:id, startSeconds:at}); else pending={videoId:id, startSeconds:at}; return; }
  // our own player frame (so it sends where it's from), then YouTube's controls hooked onto it
  var q={enablejsapi:1, autoplay:1, controls:0, disablekb:1, fs:0, rel:0, iv_load_policy:3, modestbranding:1, playsinline:1,
    start:Math.floor(at), mute:muted?1:0, origin:origin, widget_referrer:origin};
  var f=document.getElementById('p');
  f.src='https://www.youtube.com/embed/'+encodeURIComponent(id)+'?'+Object.keys(q).map(function(k){return k+'='+encodeURIComponent(q[k]);}).join('&');
  player=new YT.Player('p',{
    events:{
      onReady:function(e){
        ready=true;
        if(muted) e.target.mute(); else { e.target.unMute(); e.target.setVolume(100); }
        cc(e.target);
        var p=pending; pending=null;
        if(p && p.videoId) e.target.loadVideoById(p); else if(p) e.target.stopVideo(); else e.target.playVideo();
      },
      onStateChange:function(e){ if(e.data===1) cc(e.target); Android.state(e.data); if(e.data===0 && loop){ e.target.seekTo(0); e.target.playVideo(); } },
      onError:function(e){ Android.error(e.data); }
    }});
}
function stop(){ if(!player) return; if(ready) player.stopVideo(); else pending={}; }
function toggle(){ if(!player || !ready) return; if(player.getPlayerState()===1) player.pauseVideo(); else player.playVideo(); }
function seek(d){ if(player && ready) player.seekTo(Math.max(0, player.getCurrentTime()+d), true); }
function seekTo(s){ if(player && ready) player.seekTo(Math.max(0, s), true); }
function pause(){ if(player && ready) player.pauseVideo(); }
function resume(){ if(player && ready && player.getPlayerState()===2) player.playVideo(); }
function playNow(){ if(player && ready) player.playVideo(); }
setInterval(function(){ if(player && ready && player.getCurrentTime) Android.time(player.getCurrentTime(), player.getDuration()||0); }, 500);
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body></html>"""

    companion object {
        /** YouTube only plays embedded videos for a page that says where it's from. */
        const val ORIGIN = "https://org.vhsfever.tv"
    }
}

/**
 * A muted trailer filling its space (on the little TVs). Pauses when the app is in the background.
 * Calls [onFailed] if YouTube won't play any of [keys] here.
 */
@Composable
fun TrailerVideo(keys: List<String>, onShowing: (Boolean) -> Unit, onTime: (Float) -> Unit, onFailed: () -> Unit,
                 modifier: Modifier = Modifier, onPlayer: (YouTubePlayer) -> Unit = {}) {
    if (LocalInspectionMode.current) {
        LaunchedEffect(Unit) { onShowing(true) }
        Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF9A3A3A), Color(0xFF2A1A1A)))), contentAlignment = Alignment.Center) {
            Text("TRAILER", style = TextStyle(fontFamily = Type.Vt, fontSize = 28.fs, color = Color.White))
        }
        return
    }
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val showing by rememberUpdatedState(onShowing)
    val time by rememberUpdatedState(onTime)
    val failed by rememberUpdatedState(onFailed)
    val yt = remember { YouTubePlayer(ctx, muted = true, loop = true, pageWidth = 640) }
    yt.onShowing = { showing(it) }; yt.onTime = { time(it) }; yt.onFailed = { failed() }
    LaunchedEffect(yt) { onPlayer(yt) }
    LaunchedEffect(keys) { yt.play(keys) }
    DisposableEffect(yt) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) yt.pause()
            if (e == Lifecycle.Event.ON_RESUME) yt.resume()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); yt.release() }
    }
    AndroidView(factory = { yt.view }, modifier = modifier.fillMaxSize())
}

/**
 * A trailer full screen with sound, with the same VCR deck and on-screen display as a tape (see Vcr.kt):
 * the loading screens, PLAY/PAUSE/STOP, REW/F.FWD 10 seconds, the tape bar, aspect and zoom, and closed
 * captions (off to start with). Back tucks the deck away, then goes back to the tape page.
 */
class TrailerActivity : ComponentActivity() {
    private lateinit var yt: YouTubePlayer
    private lateinit var vcr: VcrState
    private lateinit var frame: FrameLayout
    /** A key whose press we used (e.g. to bring up the deck), so its release doesn't also press a button. */
    private var swallowUp = -1
    private val look = LoadingLook.entries.random()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val keys = intent.getStringArrayListExtra("keys") ?: arrayListOf()
        val title = intent.getStringExtra("title") ?: ""
        val dm = resources.displayMetrics
        Ui.fit(dm.widthPixels / dm.density, dm.heightPixels / dm.density)
        vcr = VcrState("$title · trailer").apply {
            audio = listOf(TrackChoice(0, "TRAILER", "STEREO", true))
            subs = listOf(TrackChoice(0, "ENGLISH", "CC", false))
        }
        yt = YouTubePlayer(this, muted = false, loop = false)
        frame = FrameLayout(this).apply {
            setBackgroundColor(AColor.BLACK)
            clipChildren = true
            addView(yt.view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPicture() }
        }
        val deck = ComposeView(this).apply {
            setContent {
                StoreTheme {
                    Box(Modifier.fillMaxSize()) {
                        VcrOverlay(vcr, actions())
                        val starting = !vcr.started && !vcr.gaveUp
                        val cover by animateFloatAsState(if (starting) 1f else 0f, tween(if (starting) 0 else 450), label = "loading")
                        if (cover > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover }) { LoadingScreen(look) }
                        else if (vcr.loading && !vcr.ended && !vcr.stopped) BufferingTape()
                        // the tape counter
                        LaunchedEffect(Unit) {
                            while (true) {
                                vcr.positionMs = (yt.time * 1000).toLong()
                                vcr.durationMs = (yt.duration * 1000).toLong()
                                delay(250)
                            }
                        }
                        // the deck tucks away after a few seconds of playing with nothing pressed
                        LaunchedEffect(vcr.touched, vcr.visible, vcr.playing, vcr.menu, vcr.loading, vcr.stopped, vcr.ended) {
                            if (vcr.visible && vcr.playing && !vcr.stopped && !vcr.ended && vcr.menu == null && !vcr.loading) {
                                delay(5000); vcr.visible = false
                            }
                        }
                    }
                }
            }
        }
        val root = FrameLayout(this)
        root.addView(frame, ViewGroup.LayoutParams(-1, -1))
        root.addView(deck, ViewGroup.LayoutParams(-1, -1))
        setContentView(root)

        // YouTube's states: -1 not started, 0 ended, 1 playing, 2 paused, 3 loading, 5 ready
        yt.onState = { s ->
            when (s) {
                1 -> { vcr.started = true; vcr.playing = true; vcr.loading = false; vcr.ended = false; vcr.stopped = false }
                2 -> { vcr.playing = false; vcr.loading = false }
                3 -> vcr.loading = true
                0 -> { vcr.ended = true; vcr.playing = false; vcr.loading = false; vcr.show("eject") }
            }
        }
        yt.onFailed = {
            Toast.makeText(this, "YouTube won’t play this trailer on the TV", Toast.LENGTH_LONG).show()
            finish()
        }
        yt.play(keys, intent.getIntExtra("index", 0).coerceIn(0, (keys.size - 1).coerceAtLeast(0)), intent.getFloatExtra("at", 0f))
        vcr.show("pause")
    }

    private fun play() {
        if (vcr.ended) yt.seekTo(0f)
        vcr.stopped = false; vcr.ended = false
        yt.playNow()
    }

    private fun seekBy(ms: Long) {
        val dur = vcr.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val to = (vcr.positionMs + ms).coerceIn(0L, dur)
        yt.seekTo(to / 1000f)
        vcr.positionMs = to
    }

    private fun actions() = VcrActions(
        play = { play() },
        pause = { yt.pause() },
        stop = { yt.pause(); vcr.stopped = true },
        rew = { seekBy(-10_000); vcr.flash("◀◀ REW") },
        ff = { seekBy(10_000); vcr.flash("FF ▶▶") },
        eject = { finish() },
        seekBy = { seekBy(it) },
        aspect = { a -> vcr.aspect = a; layoutPicture() },
        zoom = { z -> vcr.zoom = z; layoutPicture() },
        subs = { id ->
            val on = id != null
            yt.captions(on)
            vcr.subs = listOf(TrackChoice(0, "ENGLISH", "CC", on))
        },
    )

    /**
     * YouTube fits the trailer inside its own picture, so the settings work on the whole picture: 4:3 squeezes
     * it to a 4:3 shape, FILL enlarges it to cut off black bars, and zoom enlarges it further.
     */
    private fun layoutPicture() {
        val fw = frame.width.toFloat(); val fh = frame.height.toFloat()
        if (fw <= 0f || fh <= 0f) return
        val screen = fw / fh
        val (sx, sy) = when (vcr.aspect) {
            Aspect.Four3 -> (4f / 3f) / screen to 1f
            Aspect.Fill -> 4f / 3f to 4f / 3f
            else -> 1f to 1f
        }
        val z = vcr.zoom / 100f
        yt.view.scaleX = sx * z
        yt.view.scaleY = sy * z
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val code = e.keyCode
        if (!down && code == swallowUp) { swallowUp = -1; return true }
        // While the loading screen covers everything, Back leaves and the other keys wait.
        if (!vcr.started) {
            if (code == KeyEvent.KEYCODE_BACK && down && e.repeatCount == 0) finish()
            return true
        }
        fun used(block: () -> Unit): Boolean { if (down) { if (e.repeatCount == 0) swallowUp = code; block() }; return true }
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK ->
                return used { if (e.repeatCount == 0) { if (vcr.playing && !vcr.stopped) yt.pause() else play(); vcr.show() } }
            KeyEvent.KEYCODE_MEDIA_PLAY -> return used { if (e.repeatCount == 0) { play(); vcr.show() } }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> return used { if (e.repeatCount == 0) { yt.pause(); vcr.show() } }
            KeyEvent.KEYCODE_MEDIA_STOP -> return used { if (e.repeatCount == 0) { yt.pause(); vcr.stopped = true; vcr.show("play") } }
            KeyEvent.KEYCODE_MEDIA_REWIND -> return used { seekBy(-10_000); vcr.flash("◀◀ REW") }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> return used { seekBy(10_000); vcr.flash("FF ▶▶") }
            KeyEvent.KEYCODE_BACK -> {
                // Back closes a menu, then tucks the deck away, then leaves.
                if (vcr.menu != null) return used { if (e.repeatCount == 0) { vcr.menu = null; vcr.touched++ } }
                if (vcr.visible && !vcr.ended) return used { if (e.repeatCount == 0) vcr.visible = false }
            }
        }
        if (!vcr.visible) {
            val dpad = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_UP ||
                code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            if (dpad) return used {
                when (code) {
                    // Left/Right while watching jump back/ahead 10 seconds, with the tape bar showing where you are
                    KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-10_000); vcr.show("bar") }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(10_000); vcr.show("bar") }
                    else -> if (e.repeatCount == 0) vcr.show(if (vcr.playing && !vcr.stopped) "pause" else "play")
                }
            }
        } else vcr.touched++
        return super.dispatchKeyEvent(e)
    }

    override fun onPause() { super.onPause(); yt.pause() }
    override fun onDestroy() { super.onDestroy(); yt.release() }

    companion object {
        fun intent(ctx: Context, title: String, keys: List<String>, index: Int, atSec: Float): Intent =
            Intent(ctx, TrailerActivity::class.java)
                .putExtra("title", title)
                .putStringArrayListExtra("keys", ArrayList(keys))
                .putExtra("index", index)
                .putExtra("at", atSec)
    }
}
