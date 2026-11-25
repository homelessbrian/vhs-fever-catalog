package org.vhsfever.tv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Plays one tape. If the file won't play (or plays with no picture), it moves on to the
 * next copy of the same recording and says so on screen.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var info: TextView
    private lateinit var banner: TextView
    private val prefs by lazy { getSharedPreferences("positions", MODE_PRIVATE) }

    private lateinit var title: String
    private lateinit var label: String
    private lateinit var fileId: String
    private lateinit var urls: List<String>
    private lateinit var names: List<String>
    private lateinit var formats: List<String>
    private var index = 0
    private val problems = mutableListOf<String>()
    private var videoCheckDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        title = intent.getStringExtra("title") ?: ""
        label = intent.getStringExtra("label") ?: ""
        fileId = intent.getStringExtra("fileId") ?: ""
        urls = intent.getStringArrayListExtra("urls") ?: arrayListOf()
        names = intent.getStringArrayListExtra("names") ?: arrayListOf()
        formats = intent.getStringArrayListExtra("formats") ?: arrayListOf()

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

        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            keepScreenOn = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = 4000
        }
        info = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(48, 32, 48, 0)
        }
        banner = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(0xCC000000.toInt()); gravity = Gravity.CENTER
            setPadding(48, 32, 48, 32); visibility = View.GONE
        }
        val root = FrameLayout(this)
        root.addView(view, ViewGroup.LayoutParams(-1, -1))
        root.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        root.addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> info.visibility = v })

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                tryNext("${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')}")
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (videoCheckDone || tracks.groups.isEmpty()) return
                val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                if (video.isEmpty()) return
                videoCheckDone = true
                if (video.none { it.isSupported }) {
                    val fmt = video.firstOrNull()?.getTrackFormat(0)?.sampleMimeType ?: "unknown"
                    tryNext("this TV can’t decode the picture ($fmt)")
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) = updateInfo()
            override fun onPlaybackStateChanged(state: Int) {
                updateInfo()
                if (state == Player.STATE_READY) banner.visibility = View.GONE
                if (state == Player.STATE_ENDED) prefs.edit().remove(fileId).apply()
            }
        })
        val from = intent.getLongExtra("startMs", -1L)
        if (from >= 0) start(0, resume = false, fromMs = from) else start(0, resume = true)
    }

    private fun start(i: Int, resume: Boolean, fromMs: Long = 0L) {
        index = i
        videoCheckDone = false
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
            showBanner("This tape won’t play on this TV.\n\n" + problems.joinToString("\n"))
            view.showController()
        }
    }

    private fun showBanner(text: String) { banner.text = text; banner.visibility = View.VISIBLE }

    private fun updateInfo() {
        val v = player.videoSize
        val res = if (v.width > 0) "${v.width}×${v.height}" else null
        val state = when (player.playbackState) {
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> if (player.isPlaying) "playing" else "paused"
            Player.STATE_ENDED -> "ended"
            else -> null
        }
        val copy = if (index == 0) "marked file" else "backup copy $index of ${urls.size - 1}"
        info.text = "$title  ·  $label\n" + listOfNotNull(names.getOrNull(index), formats.getOrNull(index)?.takeIf { it.isNotBlank() },
            res, copy, state).joinToString("  ·  ")
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
