package org.vhsfever.tv

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * The old CRT on the counter. It plays the selected tape itself, silently, starting a fifth of the way in,
 * so you see the actual picture quality of that tape. Until the picture arrives it shows the title.
 */
@Composable
fun CrtTv(t: Title, tape: Tape?, playPreview: Boolean) {
    val c = colorsFor(t.key)
    var showing by remember(tape?.fileId) { mutableStateOf(false) }
    var position by remember(tape?.fileId) { mutableLongStateOf(0L) }
    val anim = rememberInfiniteTransition(label = "crt")
    val roll by anim.animateFloat(-60f, 200f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "roll")
    val drift by anim.animateFloat(0f, 6f, infiniteRepeatable(tween(250, easing = LinearEasing)), label = "drift")
    val flick by anim.animateFloat(1f, 0.94f, infiniteRepeatable(tween(180), RepeatMode.Reverse), label = "flick")
    val blink by anim.animateFloat(1f, 0.2f, infiniteRepeatable(keyframes { durationMillis = 1200; 1f at 0; 1f at 720; 0.2f at 732; 0.2f at 1200 }), label = "blink")
    val track by anim.animateFloat(0f, 1f, infiniteRepeatable(tween(400, easing = LinearEasing)), label = "track")

    Column(Modifier.width(270.px), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("BE KIND, REWIND", modifier = Modifier.rotate(-3f).shadow(4.px).background(Color(0xFFE9E3D0))
            .padding(horizontal = 12.px, vertical = 5.px),
            style = TextStyle(fontFamily = Type.Mono, fontSize = 12.fs, letterSpacing = 1.2f.fs, color = Color(0xFF2A2A2A)))
        Spacer(Modifier.height(12.px))
        Box(Modifier.size(270.px, 214.px)) {
            Box(Modifier.offset((-30).px, (-20).px).size(330.px, 254.px).drawBehind {
                drawRect(Brush.radialGradient(listOf(c.c3.alpha(0.33f), Color.Transparent), radius = size.width * 0.5f))
            })
            Row(Modifier.fillMaxSize().shadow(14.px, RoundedCornerShape(20.px, 20.px, 12.px, 12.px))
                .background(Brush.linearGradient(listOf(Color(0xFF47474C), Color(0xFF1C1C1F)), Offset.Zero, Offset(300f, 500f)),
                    RoundedCornerShape(20.px, 20.px, 12.px, 12.px))
                .padding(14.px), horizontalArrangement = Arrangement.spacedBy(10.px)) {
                Box(Modifier.weight(1f).height(184.px).background(Color(0xFF060607), RoundedCornerShape(18.px)).padding(8.px)) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(28.px)).graphicsLayer { alpha = flick }) {
                        // title card under the picture
                        Box(Modifier.fillMaxSize().drawBehind {
                            drawRect(Brush.radialGradient(listOf(c.c1.alpha(0.8f), c.c2), Offset(size.width / 2, size.height * .38f), size.width * .8f))
                        })
                        if (!showing) Text(coverText(t).uppercase(), modifier = Modifier.align(Alignment.Center).padding(horizontal = 10.px),
                            style = TextStyle(fontFamily = Type.Oswald, fontWeight = FontWeight.Bold, fontSize = 26.fs, lineHeight = 26.fs,
                                letterSpacing = 1f.fs, color = Ink.CoverCream, textAlign = TextAlign.Center,
                                shadow = Shadow(Color(0xB3FF285A), Offset(2f, 0f), 2f)), maxLines = 3)
                        if (tape != null && playPreview) PreviewVideo(tape, onShowing = { showing = it }, onPosition = { position = it })
                        // phosphor stripes, scan lines, rolling bar, vignette, glass
                        Box(Modifier.fillMaxSize().drawBehind {
                            var x = 0f
                            val cols = listOf(Color(0x14FF0000), Color(0x1400FF00), Color(0x140000FF))
                            var i = 0
                            while (x < size.width) { drawRect(cols[i % 3], Offset(x, 0f), Size(1.5f, size.height)); x += 1.5f; i++ }
                            var y = -drift
                            while (y < size.height) { drawRect(Color(0x4D000000), Offset(0f, y), Size(size.width, 1.5f)); y += 4f }
                            val by = roll / 200f * size.height
                            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x29FFFFFF), Color.Transparent), by, by + 46f * 1.5f),
                                Offset(0f, by), Size(size.width, 46f * 1.5f))
                            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0xB3000000)), radius = size.maxDimension * .7f))
                            drawRect(Brush.linearGradient(listOf(Color(0x24FFFFFF), Color.Transparent), Offset.Zero, Offset(size.width * .45f, size.height * .6f)))
                        })
                        Text("PLAY ▶", modifier = Modifier.padding(start = 10.px, top = 6.px),
                            style = TextStyle(fontFamily = Type.Vt, fontSize = 20.fs, color = Color.White, shadow = Shadow(Color.Black, Offset(1f, 1f), 0f)))
                        Text("SP " + clock(position), modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.px, bottom = 18.px),
                            style = TextStyle(fontFamily = Type.Vt, fontSize = 20.fs, color = Color.White, shadow = Shadow(Color.Black, Offset(1f, 1f), 0f)))
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.px).fillMaxWidth().height(8.px)
                            .graphicsLayer { translationX = (if (track < .33f) 0f else if (track < .66f) 5f else -4f) * 1.5f }
                            .drawBehind {
                                var x = 0f; var i = 0
                                val pat = listOf(3f to Color(0x8CFFFFFF), 2f to Color(0x66000000), 4f to Color(0x40FFFFFF))
                                while (x < size.width) { val (w, col) = pat[i % 3]; drawRect(col, Offset(x, 0f), Size(w * 1.5f, size.height)); x += w * 1.5f; i++ }
                            })
                    }
                }
                Column(Modifier.width(32.px).padding(top = 6.px), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.px)) {
                    repeat(2) {
                        Box(Modifier.size(24.px).border(2.px, Color(0xFF111111), CircleShape)
                            .background(Brush.radialGradient(listOf(Color(0xFF8A8A92), Color(0xFF2C2C31))), CircleShape))
                    }
                    Box(Modifier.size(22.px, 40.px).drawBehind {
                        var y = 0f
                        while (y < size.height) { drawRect(Color(0xFF0B0B0C), Offset(0f, y), Size(size.width, 3f)); y += 9f }
                    })
                    Box(Modifier.size(7.px).alpha(blink).background(Color(0xFFFF3B3B), CircleShape))
                }
            }
        }
        // the VCR under the TV, flashing 12:00 like every VCR ever
        Row(Modifier.padding(top = 6.px).size(246.px, 38.px).shadow(5.px, RoundedCornerShape(3.px))
            .background(Brush.verticalGradient(listOf(Color(0xFF2E2E32), Color(0xFF151518))), RoundedCornerShape(3.px))
            .padding(horizontal = 12.px), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(124.px, 9.px).background(Color(0xFF030303), RoundedCornerShape(2.px)))
            Text("12:00", modifier = Modifier.alpha(blink), style = TextStyle(fontFamily = Type.Vt, fontSize = 22.fs,
                color = Color(0xFF6DFF8A), shadow = Shadow(Color(0xFF33FF66), Offset.Zero, 6f)))
        }
        Spacer(Modifier.height(10.px))
        Text(if (showing) "NOW SHOWING · THIS TAPE" else "NOW SHOWING", style = TextStyle(fontFamily = Type.Mono, fontSize = 12.fs,
            letterSpacing = 1.4f.fs, color = Ink.Soft))
    }
}

fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}

@OptIn(UnstableApi::class)
@Composable
private fun PreviewVideo(tape: Tape, onShowing: (Boolean) -> Unit, onPosition: (Long) -> Unit) {
    if (LocalInspectionMode.current) return
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember(tape.fileId) {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory()
                .setUserAgent("VHS-FEVER-TV").setAllowCrossProtocolRedirects(true)))
            .build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
            }
    }
    DisposableEffect(tape.fileId) {
        val startMs = ((tape.minutes ?: 0) * 60_000L * 0.2).toLong()
        player.setMediaItem(MediaItem.Builder().setUri(tape.sources.first().url).build(), startMs)
        player.prepare()
        player.playWhenReady = true
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() = onShowing(true)
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                onShowing(false)
                // try the Archive's copy if the marked file won't play here
                val next = tape.sources.getOrNull(1) ?: return
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
        while (true) { onPosition(player.currentPosition); delay(500) }
    }
    AndroidView(factory = { TextureView(it).also { v -> player.setVideoTextureView(v) } }, modifier = Modifier.fillMaxSize())
}
