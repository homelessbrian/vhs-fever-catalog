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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
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
class YouTubePlayer(ctx: Context, private val muted: Boolean, private val loop: Boolean) {
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
        view.webChromeClient = WebChromeClient()
        view.addJavascriptInterface(object {
            @JavascriptInterface fun ready() = main.post { ready = true; startCurrent() }.let { }
            @JavascriptInterface fun state(s: Int) = main.post {
                if (s == 1) onShowing(true)
                onState(s)
            }.let { }
            @JavascriptInterface fun time(t: Float) = main.post { time = t; onTime(t) }.let { }
            // 2 bad id, 5 can't play here, 100 removed, 101/150 the owner doesn't allow it outside YouTube
            @JavascriptInterface fun error(code: Int) = main.post { next() }.let { }
        }, "Android")
        view.loadDataWithBaseURL("$ORIGIN/", page(), "text/html", "utf-8", null)
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
    fun pause() = js("pause()")
    fun resume() = js("resume()")
    fun release() { view.stopLoading(); view.destroy() }

    private fun js(code: String) = view.evaluateJavascript("try{$code}catch(e){}", null)

    private fun page() = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}
#p,iframe{position:absolute;top:0;left:0;width:100%;height:100%;border:0;pointer-events:none}</style></head>
<body><div id="p"></div>
<script>
var player=null, muted=$muted, loop=$loop, origin='$ORIGIN';
function onYouTubeIframeAPIReady(){ Android.ready(); }
function start(id, at){
  if(player && player.loadVideoById){ player.loadVideoById({videoId:id, startSeconds:at}); return; }
  player=new YT.Player('p',{videoId:id, width:'100%', height:'100%',
    playerVars:{autoplay:1, controls:0, disablekb:1, fs:0, rel:0, iv_load_policy:3, modestbranding:1, playsinline:1,
      cc_load_policy:0, start:Math.floor(at), mute:muted?1:0, origin:origin, widget_referrer:origin},
    events:{
      onReady:function(e){ if(muted) e.target.mute(); else { e.target.unMute(); e.target.setVolume(100); } e.target.playVideo(); },
      onStateChange:function(e){ Android.state(e.data); if(e.data===0 && loop){ e.target.seekTo(0); e.target.playVideo(); } },
      onError:function(e){ Android.error(e.data); }
    }});
}
function stop(){ if(player && player.stopVideo) player.stopVideo(); }
function toggle(){ if(!player) return; if(player.getPlayerState()===1) player.pauseVideo(); else player.playVideo(); }
function seek(d){ if(player && player.getCurrentTime) player.seekTo(Math.max(0, player.getCurrentTime()+d), true); }
function pause(){ if(player && player.pauseVideo) player.pauseVideo(); }
function resume(){ if(player && player.playVideo && player.getPlayerState()===2) player.playVideo(); }
setInterval(function(){ if(player && player.getCurrentTime && player.getPlayerState()===1) Android.time(player.getCurrentTime()); }, 500);
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
    val yt = remember { YouTubePlayer(ctx, muted = true, loop = true) }
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

/** A trailer full screen with sound. OK pauses, ◀ ▶ skip 10 seconds, Back goes back to the tape. */
class TrailerActivity : ComponentActivity() {
    private lateinit var yt: YouTubePlayer
    private lateinit var hint: TextView
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val keys = intent.getStringArrayListExtra("keys") ?: arrayListOf()
        val title = intent.getStringExtra("title") ?: ""
        yt = YouTubePlayer(this, muted = false, loop = false)
        hint = TextView(this).apply {
            setTextColor(AColor.WHITE); textSize = 16f
            setShadowLayer(6f, 0f, 0f, AColor.BLACK)
            setBackgroundColor(0x99000000.toInt())
            setPadding(40, 20, 40, 20)
            text = "$title · TRAILER      OK pause  ·  ◀ ▶ skip  ·  BACK to the tape"
        }
        val root = FrameLayout(this).apply { setBackgroundColor(AColor.BLACK) }
        root.addView(yt.view, ViewGroup.LayoutParams(-1, -1))
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 48 })
        setContentView(root)
        showHint()
        yt.onState = { s ->
            when (s) {
                0 -> finish()                                  // trailer over: back to the tape page
                2 -> { hint.text = "‖ PAUSED      OK play  ·  ◀ ▶ skip  ·  BACK to the tape"; hint.visibility = View.VISIBLE; main.removeCallbacksAndMessages(null) }
                1 -> if (hint.text.startsWith("‖")) { hint.text = "$title · TRAILER"; showHint() }
            }
        }
        yt.onFailed = {
            Toast.makeText(this, "YouTube won’t play this trailer on the TV", Toast.LENGTH_LONG).show()
            finish()
        }
        yt.play(keys, intent.getIntExtra("index", 0).coerceIn(0, (keys.size - 1).coerceAtLeast(0)), intent.getFloatExtra("at", 0f))
    }

    private fun showHint() {
        hint.visibility = View.VISIBLE
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ hint.visibility = View.GONE }, 4000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { { yt.toggle() } }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { { yt.seek(-10); showHint() } }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { { yt.seek(10); showHint() } }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { { showHint() } }
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) action()
        return true
    }

    override fun onPause() { super.onPause(); yt.pause() }
    override fun onDestroy() { super.onDestroy(); main.removeCallbacksAndMessages(null); yt.release() }

    companion object {
        fun intent(ctx: Context, title: String, keys: List<String>, index: Int, atSec: Float): Intent =
            Intent(ctx, TrailerActivity::class.java)
                .putExtra("title", title)
                .putStringArrayListExtra("keys", ArrayList(keys))
                .putExtra("index", index)
                .putExtra("at", atSec)
    }
}
