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
    if (LocalInspectionMode.current) return
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
fun CrtGlass(modifier: Modifier = Modifier, k: Float = 1f) {
    val anim = rememberInfiniteTransition(label = "glass")
    val roll by anim.animateFloat(-60f, 200f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "roll")
    val drift by anim.animateFloat(0f, 6f, infiniteRepeatable(tween(250, easing = LinearEasing)), label = "drift")
    Box(modifier.fillMaxSize().drawBehind {
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
 * The old CRT on the counter of the tape page. It plays the selected tape silently so you can judge the picture.
 * [width] is in mockup pixels (the mockup's TV was 270 wide); everything inside grows with it.
 * Select it to watch full screen from the same spot.
 */
@Composable
fun CrtTv(t: Title, tape: Tape?, playPreview: Boolean, width: Int, onFullScreen: (Long) -> Unit) {
    val c = colorsFor(t.key)
    val k = width / 270f
    fun u(n: Int): Dp = (n * k).px
    fun f(n: Int) = (n * k).fs
    var showing by remember(tape?.fileId) { mutableStateOf(false) }
    var position by remember(tape?.fileId) { mutableLongStateOf(if (tape != null) previewStartMs(tape) else 0L) }
    var focused by remember { mutableStateOf(false) }
    val anim = rememberInfiniteTransition(label = "crt")
    val flick by anim.animateFloat(1f, 0.94f, infiniteRepeatable(tween(180), RepeatMode.Reverse), label = "flick")
    val blink by anim.animateFloat(1f, 0.2f, infiniteRepeatable(keyframes { durationMillis = 1200; 1f at 0; 1f at 720; 0.2f at 732; 0.2f at 1200 }), label = "blink")
    val track by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(400, easing = LinearEasing)), label = "track")
    val vt = TextStyle(fontFamily = Type.Vt, fontSize = f(20), color = Color.White, shadow = Shadow(Color.Black, Offset(1f, 1f), 0f))

    Column(Modifier.width(u(270)), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("BE KIND, REWIND", modifier = Modifier.rotate(-3f).shadow(4.px).background(Color(0xFFE9E3D0))
            .padding(horizontal = u(12), vertical = u(5)),
            style = TextStyle(fontFamily = Type.Mono, fontSize = f(12), letterSpacing = (1.2f * k).fs, color = Color(0xFF2A2A2A)))
        Spacer(Modifier.height(u(12)))
        Box(Modifier.size(u(270), u(214))) {
            Box(Modifier.offset(-u(30), -u(20)).size(u(330), u(254)).drawBehind {
                drawRect(Brush.radialGradient(listOf(c.c3.alpha(if (focused) 0.55f else 0.33f), Color.Transparent), radius = size.width * 0.5f))
            })
            val bodyShape = RoundedCornerShape(u(20), u(20), u(12), u(12))
            Surface(
                onClick = { onFullScreen(position) },
                modifier = Modifier.fillMaxSize().onFocusChanged { focused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(bodyShape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.px, Ink.Gold), shape = bodyShape)),
                glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(Ink.Gold.alpha(0.45f), 28.px)),
            ) {
                Row(Modifier.fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color(0xFF47474C), Color(0xFF1C1C1F)), Offset.Zero, Offset(300f * k, 500f * k)), bodyShape)
                    .padding(u(14)), horizontalArrangement = Arrangement.spacedBy(u(10))) {
                    Box(Modifier.weight(1f).height(u(184)).background(Color(0xFF060607), RoundedCornerShape(u(18))).padding(u(8))) {
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(u(28))).graphicsLayer { alpha = flick }) {
                            Box(Modifier.fillMaxSize().drawBehind {
                                drawRect(Brush.radialGradient(listOf(c.c1.alpha(0.8f), c.c2), Offset(size.width / 2, size.height * .38f), size.width * .8f))
                            })
                            if (!showing) FitText(coverText(t).uppercase(), modifier = Modifier.align(Alignment.Center).padding(horizontal = u(12)),
                                style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = f(26), lineHeight = f(26),
                                    letterSpacing = (1f * k).fs, color = Ink.CoverCream, textAlign = TextAlign.Center,
                                    shadow = Shadow(Color(0xB3FF285A), Offset(2f, 0f), 2f)), maxLines = 3)
                            if (tape != null && playPreview) TapeVideo(tape, onShowing = { showing = it }, onPosition = { position = it })
                            CrtGlass(k = k)
                            Text("PLAY ▶", modifier = Modifier.padding(start = u(10), top = u(6)), style = vt)
                            Text("SP " + clock(position), modifier = Modifier.align(Alignment.BottomEnd).padding(end = u(10), bottom = u(18)), style = vt)
                            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = u(6)).fillMaxWidth().height(u(8))
                                .graphicsLayer { translationX = (if (track < .33f) 0f else if (track < .66f) 5f else -4f) * 1.5f * k }
                                .drawBehind {
                                    var x = 0f; var i = 0
                                    val pat = listOf(3f to Color(0x8CFFFFFF), 2f to Color(0x66000000), 4f to Color(0x40FFFFFF))
                                    while (x < size.width) { val (w, col) = pat[i % 3]; drawRect(col, Offset(x, 0f), Size(w * 1.5f * k, size.height)); x += w * 1.5f * k; i++ }
                                })
                        }
                    }
                    Column(Modifier.width(u(32)).padding(top = u(6)), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(u(10))) {
                        repeat(2) {
                            Box(Modifier.size(u(24)).border(u(2), Color(0xFF111111), CircleShape)
                                .background(Brush.radialGradient(listOf(Color(0xFF8A8A92), Color(0xFF2C2C31))), CircleShape))
                        }
                        Box(Modifier.size(u(22), u(40)).drawBehind {
                            var y = 0f
                            while (y < size.height) { drawRect(Color(0xFF0B0B0C), Offset(0f, y), Size(size.width, 3f * k)); y += 9f * k }
                        })
                        Box(Modifier.size(u(7)).alpha(blink).background(Color(0xFFFF3B3B), CircleShape))
                    }
                }
            }
        }
        // the VCR under the TV, flashing 12:00 like every VCR ever
        Row(Modifier.padding(top = u(6)).size(u(246), u(38)).shadow(5.px, RoundedCornerShape(u(3)))
            .background(Brush.verticalGradient(listOf(Color(0xFF2E2E32), Color(0xFF151518))), RoundedCornerShape(u(3)))
            .padding(horizontal = u(12)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(u(124), u(9)).background(Color(0xFF030303), RoundedCornerShape(u(2))))
            Text("12:00", modifier = Modifier.alpha(blink), style = TextStyle(fontFamily = Type.Vt, fontSize = f(22),
                color = Color(0xFF6DFF8A), shadow = Shadow(Color(0xFF33FF66), Offset.Zero, 6f)))
        }
        Spacer(Modifier.height(u(10)))
        Text("NOW SHOWING · THIS TAPE", style = TextStyle(fontFamily = Type.Mono, fontSize = 13.fs, letterSpacing = 1.4f.fs, color = Ink.Soft))
        Text("Select the TV to watch full screen", modifier = Modifier.padding(top = 4.px),
            style = TextStyle(fontFamily = Type.Barlow, fontSize = 15.fs, color = if (focused) Ink.Gold else Ink.Muted))
    }
}

fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}
