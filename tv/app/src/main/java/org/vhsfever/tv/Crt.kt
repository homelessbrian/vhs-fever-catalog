package org.vhsfever.tv

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Where a muted preview starts: a fifth of the way in, past the warnings and trailers. */
fun previewStartMs(tape: Tape) = ((tape.minutes ?: 0) * 60_000L * 0.2).toLong()

/**
 * Plays [tape] silently, filling its space. Moves to the Archive's copy if the marked file won't play.
 * Pauses when the app is in the background. Reports when the picture appears and where it is.
 */
@OptIn(UnstableApi::class)
@Composable
fun TapeVideo(tape: Tape, onShowing: (Boolean) -> Unit, onPosition: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (LocalInspectionMode.current) {
        // screen pictures / previews: a stand-in where the video would be
        LaunchedEffect(tape.fileId) { onShowing(true) }
        Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A6A9A), Color(0xFF1A2A4A)))), contentAlignment = Alignment.Center) {
            Text("VIDEO", style = TextStyle(fontFamily = Type.Vt, fontSize = 28.fs, color = Color.White))
        }
        return
    }
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val showing by rememberUpdatedState(onShowing)
    val position by rememberUpdatedState(onPosition)
    val player = remember(tape.fileId) {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory()
                .setUserAgent("VHS-FEVER-TV").setAllowCrossProtocolRedirects(true)))
            .build().apply { volume = 0f; repeatMode = Player.REPEAT_MODE_ONE }
    }
    DisposableEffect(tape.fileId) {
        val startMs = previewStartMs(tape)
        var source = 0
        player.setMediaItem(MediaItem.fromUri(tape.sources.first().url), startMs)
        player.prepare()
        player.playWhenReady = true
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() = showing(true)
            override fun onPlayerError(error: PlaybackException) {
                showing(false)
                val next = tape.sources.getOrNull(++source) ?: return
                player.setMediaItem(MediaItem.fromUri(next.url), startMs); player.prepare()
            }
        }
        player.addListener(l)
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) player.pause()
            if (e == Lifecycle.Event.ON_RESUME) player.play()
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            player.removeListener(l)
            player.release()
        }
    }
    LaunchedEffect(tape.fileId) {
        while (true) { position(player.currentPosition); delay(500) }
    }
    AndroidView(factory = { TextureView(it).also { v -> player.setVideoTextureView(v) } }, modifier = modifier.fillMaxSize())
}

/** Scan lines, phosphor stripes, a rolling bar and a vignette, laid over a picture to make it look like an old tube. */
@Composable
fun CrtGlass(modifier: Modifier = Modifier, k: Float = 1f, moving: Boolean = true) {
    if (!moving) { CrtGlassDrawing(modifier, k, { 70f }, { 0f }); return }
    val anim = rememberInfiniteTransition(label = "glass")
    val roll by anim.animateFloat(-60f, 200f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "roll")
    val drift by anim.animateFloat(0f, 6f, infiniteRepeatable(tween(250, easing = LinearEasing)), label = "drift")
    CrtGlassDrawing(modifier, k, { roll }, { drift })
}

/**
 * The glass itself. [moving] off keeps it still: the store's TVs sit in the middle of the shelves, and a glass
 * that moves every frame makes the TV redraw the whole store 60 times a second, which slows the remote down.
 */
@Composable
private fun CrtGlassDrawing(modifier: Modifier, k: Float, rollAt: () -> Float, driftAt: () -> Float) {
    Box(modifier.fillMaxSize().drawBehind {
        val roll = rollAt(); val drift = driftAt()
        val u = 1.5f * k
        var x = 0f
        val cols = listOf(Color(0x14FF0000), Color(0x1400FF00), Color(0x140000FF))
        var i = 0
        while (x < size.width) { drawRect(cols[i % 3], Offset(x, 0f), Size(u, size.height)); x += u; i++ }
        var y = -drift * k
        while (y < size.height) { drawRect(Color(0x40000000), Offset(0f, y), Size(size.width, u)); y += 3 * u }
        val bh = size.height * 0.23f
        val by = roll / 200f * size.height
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x24FFFFFF), Color.Transparent), by, by + bh), Offset(0f, by), Size(size.width, bh))
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0xB3000000)), radius = size.maxDimension * .7f))
        drawRect(Brush.linearGradient(listOf(Color(0x24FFFFFF), Color.Transparent), Offset.Zero, Offset(size.width * .45f, size.height * .6f)))
    })
}

/**
 * The real TV on the counter in the store photo of the tape page, [w]×[h] mockup pixels over its screen.
 * It plays the movie's YouTube trailer silently (the tape itself only if YouTube won't play a trailer here),
 * and shows the photo's own screen until the picture starts. Select it to watch full screen from the start.
 */
@Composable
fun CounterTv(t: Title, tape: Tape?, playPreview: Boolean, w: Float, h: Float, onFullScreen: (Long) -> Unit) {
    var showing by remember(t.key, tape?.fileId) { mutableStateOf(false) }
    var position by remember(tape?.fileId) { mutableLongStateOf(if (tape != null) previewStartMs(tape) else 0L) }
    var focused by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val trailers = rememberTrailers(t)
    var trailerFailed by remember(t.key) { mutableStateOf(false) }
    val trailerKeys = trailers?.takeIf { it.isNotEmpty() && !trailerFailed }
    var yt by remember { mutableStateOf<YouTubePlayer?>(null) }
    var trailerTime by remember(t.key) { mutableStateOf(0f) }
    val showsTrailer = trailerKeys != null
    val vt = TextStyle(fontFamily = Type.Vt, fontSize = 24.fs, color = Color.White, shadow = Shadow(Color.Black, Offset(1f, 1f), 0f))
    val shape = RoundedCornerShape(24.px)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = {
                val keys = trailerKeys
                if (keys != null) {
                    val i = yt?.key?.let { keys.indexOf(it) }?.coerceAtLeast(0) ?: 0
                    ctx.startActivity(TrailerActivity.intent(ctx, t.name, keys, i, 0f))
                } else onFullScreen(position)
            },
            modifier = Modifier.size(w.px, h.px).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.px, Ink.Gold), shape = shape)),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(Ink.Gold.alpha(0.3f), 18.px)),
        ) {
            // hidden (the photo's own screen shows through) until the picture starts
            Box(Modifier.fillMaxSize().clip(shape).graphicsLayer { alpha = if (showing) 1f else 0f }.background(Color.Black)) {
                if (playPreview && trailerKeys != null) TrailerVideo(trailerKeys, onShowing = { showing = it },
                    onTime = { trailerTime = it }, onFailed = { showing = false; trailerFailed = true }, onPlayer = { yt = it })
                else if (tape != null && playPreview && trailers != null) TapeVideo(tape, onShowing = { showing = it }, onPosition = { position = it })
                CrtGlass()
                Text("PLAY ▶", modifier = Modifier.padding(start = 16.px, top = 10.px), style = vt)
                Text((if (showsTrailer) "TRAILER " else "SP ") + clock(if (showsTrailer) (trailerTime * 1000).toLong() else position),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.px, bottom = 10.px), style = vt)
            }
        }
        // a paper note stuck on the counter under the TV
        Text(if (showsTrailer) "SELECT THE TV · TRAILER FULL SCREEN" else "SELECT THE TV · WATCH FULL SCREEN",
            modifier = Modifier.padding(top = 22.px).rotate(-2f).shadow(4.px)
                .background(if (focused) Ink.Gold else Color(0xFFE9E3D0)).padding(horizontal = 10.px, vertical = 4.px),
            style = TextStyle(fontFamily = Type.Mono, fontSize = 12.fs, letterSpacing = 1f.fs, color = Color(0xFF2A2A2A)))
    }
}

fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}
