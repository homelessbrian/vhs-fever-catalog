package org.vhsfever.tv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Plays one tape, with an old VCR's front panel for the controls (see Vcr.kt). If the file won't play
 * (or plays with no picture), it moves on to the next copy of the same recording and says so on screen.
 * The picture can be set to fill the screen in different ways (aspect) and zoomed in; both are kept per tape.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var frame: FrameLayout
    private lateinit var banner: TextView
    private lateinit var vcr: VcrState
    private val prefs by lazy { getSharedPreferences("positions", MODE_PRIVATE) }
    private val picture by lazy { getSharedPreferences("picture", MODE_PRIVATE) }

    private lateinit var title: String
    private lateinit var label: String
    private lateinit var fileId: String
    private lateinit var urls: List<String>
    private lateinit var names: List<String>
    private lateinit var formats: List<String>
    private var index = 0
    private val problems = mutableListOf<String>()
    private var videoCheckDone = false
    private var audioRefs = listOf<Pair<Tracks.Group, Int>>()
    private var subRefs = listOf<Pair<Tracks.Group, Int>>()
    /** A key whose press we used (e.g. to bring up the deck), so its release doesn't also press a button. */
    private var swallowUp = -1
    /** Which loading screen this movie gets: the rewinding tape or the tape going into the VCR. */
    private val look = LoadingLook.entries.random()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        title = intent.getStringExtra("title") ?: ""
        label = intent.getStringExtra("label") ?: ""
        fileId = intent.getStringExtra("fileId") ?: ""
        urls = intent.getStringArrayListExtra("urls") ?: arrayListOf()
        names = intent.getStringArrayListExtra("names") ?: arrayListOf()
        formats = intent.getStringArrayListExtra("formats") ?: arrayListOf()
        val dm = resources.displayMetrics
        Ui.fit(dm.widthPixels / dm.density, dm.heightPixels / dm.density)

        vcr = VcrState(title).apply {
            aspect = Aspect.entries.firstOrNull { it.name == picture.getString("$fileId:aspect", null) } ?: Aspect.Auto
            zoom = picture.getInt("$fileId:zoom", 100).takeIf { it in ZOOMS } ?: 100
        }

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("VHS-FEVER-TV")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(30000)
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(DefaultRenderersFactory(this).setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()

        // The picture is sized by us (aspect × zoom) and may be bigger than the screen; the frame crops it.
        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            useController = false
            keepScreenOn = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)  // our own animations instead (Loading.kt)
            isFocusable = false
        }
        frame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
            addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutVideo() }
        }
        banner = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(0xCC000000.toInt()); gravity = Gravity.CENTER
            setPadding(48, 32, 48, 32); visibility = View.GONE
        }
        val deck = ComposeView(this).apply {
            setContent {
                StoreTheme {
                  Box(Modifier.fillMaxSize()) {
                    VcrOverlay(vcr, actions())
                    // full screen until the first picture, then a small tape whenever it stops to load more
                    val starting = !vcr.started && !vcr.gaveUp
                    val cover by animateFloatAsState(if (starting) 1f else 0f, tween(if (starting) 0 else 450), label = "loading")
                    if (cover > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover }) { LoadingScreen(look) }
                    else if (vcr.loading && !vcr.ended && !vcr.stopped && !vcr.gaveUp) BufferingTape()
                    // the tape counter
                    LaunchedEffect(Unit) {
                        while (true) {
                            vcr.positionMs = player.currentPosition
                            vcr.durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
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
        root.addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                tryNext("${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')}")
            }
            override fun onRenderedFirstFrame() { vcr.started = true }
            override fun onTracksChanged(tracks: Tracks) {
                readTracks(tracks)
                // a tape with sound but no picture never draws a first frame
                if (tracks.groups.isNotEmpty() && tracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }) vcr.started = true
                if (videoCheckDone || tracks.groups.isEmpty()) return
                val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                if (video.isEmpty()) return
                videoCheckDone = true
                if (video.none { it.isSupported }) {
                    val fmt = video.firstOrNull()?.getTrackFormat(0)?.sampleMimeType ?: "unknown"
                    tryNext("this TV can’t decode the picture ($fmt)")
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) { updateInfo(); layoutVideo() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { vcr.playing = playWhenReady }
            override fun onPlaybackStateChanged(state: Int) {
                updateInfo()
                vcr.loading = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                vcr.ended = state == Player.STATE_ENDED
                if (state == Player.STATE_READY) banner.visibility = View.GONE
                if (state == Player.STATE_ENDED) { prefs.edit().remove(fileId).apply(); vcr.show("eject") }
            }
        })
        val from = intent.getLongExtra("startMs", -1L)
        if (from >= 0) start(0, resume = false, fromMs = from) else start(0, resume = true)
        vcr.show("pause")
    }

    private fun actions() = VcrActions(
        play = { playTape() },
        pause = { player.pause() },
        stop = { player.pause(); vcr.stopped = true },
        rew = { seekBy(-10_000); vcr.flash("◀◀ REW") },
        ff = { seekBy(10_000); vcr.flash("FF ▶▶") },
        eject = { finish() },
        seekBy = { seekBy(it) },
        aspect = { a -> vcr.aspect = a; picture.edit().putString("$fileId:aspect", a.name).apply(); layoutVideo() },
        zoom = { z -> vcr.zoom = z; picture.edit().putInt("$fileId:zoom", z).apply(); layoutVideo() },
        audio = { id -> audioRefs.getOrNull(id)?.let { (g, i) -> choose(C.TRACK_TYPE_AUDIO, g, i) } },
        subs = { id ->
            val ref = id?.let { subRefs.getOrNull(it) }
            if (ref == null) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            else choose(C.TRACK_TYPE_TEXT, ref.first, ref.second)
        },
    )

    private fun playTape() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        vcr.stopped = false
        player.play()
    }

    private fun seekBy(ms: Long) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        val to = (player.currentPosition + ms).coerceIn(0L, dur)
        player.seekTo(to)
        vcr.positionMs = to
    }

    private fun choose(type: Int, g: Tracks.Group, i: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            .build()
    }

    /** The sound tracks and subtitles on this file, for the AUDIO and SUBTITLES menus. */
    private fun readTracks(tracks: Tracks) {
        fun lang(f: Format) = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale(it).displayLanguage.uppercase() }
        val audio = mutableListOf<TrackChoice>(); val aRefs = mutableListOf<Pair<Tracks.Group, Int>>()
        val subs = mutableListOf<TrackChoice>(); val sRefs = mutableListOf<Pair<Tracks.Group, Int>>()
        for (g in tracks.groups) for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            when (g.type) {
                C.TRACK_TYPE_AUDIO -> {
                    val ch = when (f.channelCount) { 1 -> "MONO"; 2 -> "STEREO"; 6 -> "5.1"; 8 -> "7.1"; Format.NO_VALUE -> "SOUND"; else -> "${f.channelCount} CH" }
                    audio += TrackChoice(aRefs.size, f.label?.uppercase() ?: lang(f) ?: "TRACK ${aRefs.size + 1}", ch, g.isTrackSelected(i))
                    aRefs += g to i
                }
                C.TRACK_TYPE_TEXT -> {
                    subs += TrackChoice(sRefs.size, f.label?.uppercase() ?: lang(f) ?: "SUBTITLES ${sRefs.size + 1}", lang(f) ?: "", g.isTrackSelected(i))
                    sRefs += g to i
                }
            }
        }
        audioRefs = aRefs; subRefs = sRefs
        vcr.audio = audio; vcr.subs = subs
    }

    /**
     * Sizes the picture: AUTO keeps the file's own shape, 4:3 and 16:9 force that shape (16:9 stretches a 4:3
     * tape across the screen), FILL fills the whole screen and crops what doesn't fit. Zoom then enlarges it.
     */
    private fun layoutVideo() {
        val fw = frame.width; val fh = frame.height
        if (fw <= 0 || fh <= 0) return
        val v = player.videoSize
        val own = if (v.width > 0 && v.height > 0) v.width * v.pixelWidthHeightRatio / v.height else 4f / 3f
        val shape = when (vcr.aspect) { Aspect.Four3 -> 4f / 3f; Aspect.Wide -> 16f / 9f; else -> own }
        val screen = fw.toFloat() / fh
        val cover = vcr.aspect == Aspect.Fill
        var w: Float; var h: Float
        if ((shape > screen) != cover) { w = fw.toFloat(); h = w / shape } else { h = fh.toFloat(); w = h * shape }
        val z = vcr.zoom / 100f
        w *= z; h *= z
        val lp = view.layoutParams as FrameLayout.LayoutParams
        val nw = w.roundToInt(); val nh = h.roundToInt()
        if (lp.width != nw || lp.height != nh) {
            view.layoutParams = FrameLayout.LayoutParams(nw, nh, Gravity.CENTER)
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val code = e.keyCode
        if (!down && code == swallowUp) { swallowUp = -1; return true }
        // While the loading screen covers everything, Back leaves and the other keys wait.
        if (!vcr.started && !vcr.gaveUp) {
            if (code == KeyEvent.KEYCODE_BACK && down && e.repeatCount == 0) finish()
            return true
        }
        fun used(block: () -> Unit): Boolean { if (down) { if (e.repeatCount == 0) swallowUp = code; block() }; return true }
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK ->
                return used { if (e.repeatCount == 0) { if (player.playWhenReady && !vcr.stopped) player.pause() else playTape(); vcr.show() } }
            KeyEvent.KEYCODE_MEDIA_PLAY -> return used { if (e.repeatCount == 0) { playTape(); vcr.show() } }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> return used { if (e.repeatCount == 0) { player.pause(); vcr.show() } }
            KeyEvent.KEYCODE_MEDIA_STOP -> return used { if (e.repeatCount == 0) { player.pause(); vcr.stopped = true; vcr.show("play") } }
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
                    else -> if (e.repeatCount == 0) vcr.show(if (player.playWhenReady && !vcr.stopped) "pause" else "play")
                }
            }
        } else vcr.touched++
        return super.dispatchKeyEvent(e)
    }

    private fun start(i: Int, resume: Boolean, fromMs: Long = 0L) {
        index = i
        videoCheckDone = false
        vcr.started = false
        player.setMediaItem(MediaItem.fromUri(urls[i]))
        val pos = if (resume) prefs.getLong(fileId, 0L) else fromMs
        if (pos > 0) player.seekTo(pos)
        player.prepare()
        player.playWhenReady = true
        updateInfo()
    }

    private fun tryNext(reason: String) {
        problems += "${names.getOrNull(index) ?: "file"}: $reason"
        val pos = player.currentPosition
        if (index + 1 < urls.size) {
            showBanner("Couldn’t play that file ($reason).\nTrying the Archive’s copy…")
            Toast.makeText(this, "Switching to backup copy", Toast.LENGTH_SHORT).show()
            start(index + 1, resume = false)
            if (pos > 0) player.seekTo(pos)
        } else {
            player.stop()
            vcr.gaveUp = true
            showBanner("This tape won’t play on this TV.\n\n" + problems.joinToString("\n"))
            vcr.show("eject")
        }
    }

    private fun showBanner(text: String) { banner.text = text; banner.visibility = View.VISIBLE }

    private fun updateInfo() {
        val v = player.videoSize
        val res = if (v.width > 0) "${v.width}×${v.height}" else null
        val copy = if (index == 0) "marked file" else "backup copy $index of ${urls.size - 1}"
        vcr.info = "$label\n" + listOfNotNull(names.getOrNull(index), formats.getOrNull(index)?.takeIf { it.isNotBlank() },
            res, copy).joinToString("  ·  ")
    }

    override fun onPause() {
        super.onPause()
        if (player.playbackState != Player.STATE_ENDED && player.currentPosition > 30_000) {
            prefs.edit().putLong(fileId, player.currentPosition).apply()
        }
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }

    companion object {
        /** [startMs] ≥ 0 starts there (e.g. where the little TV preview was); otherwise resumes where you stopped. */
        fun intent(ctx: Context, title: String, tape: Tape, startMs: Long = -1L): Intent = Intent(ctx, PlayerActivity::class.java)
            .putExtra("startMs", startMs)
            .putExtra("title", title)
            .putExtra("label", tape.label)
            .putExtra("fileId", tape.fileId)
            .putStringArrayListExtra("urls", ArrayList(tape.sources.map { it.url }))
            .putStringArrayListExtra("names", ArrayList(tape.sources.map { it.name }))
            .putStringArrayListExtra("formats", ArrayList(tape.sources.map { it.format }))
    }
}
