package org.vhsfever.review

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C as MediaC
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

/**
 * One file, everything on one screen, no scrolling:
 * video · file line · bot line · the movie (poster, genres, story) · Bad / Good.
 * Bad swaps the movie card for the reasons; one tap on a reason saves and moves on.
 */
@Composable
fun ReviewScreen(m: AppModel, fileId: String) {
    val f = m.byId[fileId] ?: run { Text("File not found", color = C.text, modifier = Modifier.padding(24.dp)); return }
    val d = m.decisionFor(f)
    var picking by remember(fileId) { mutableStateOf(false) }      // showing the bad reasons
    var notes by remember(fileId) { mutableStateOf(d?.notes ?: "") }
    var editNotes by remember(fileId) { mutableStateOf(false) }
    var otherReason by remember(fileId) { mutableStateOf<String?>(null) }

    fun goNext(onlyUnreviewed: Boolean = true) {
        val n = m.nextAfter(f.id, onlyUnreviewed)
        if (n != null) m.replaceTop(Screen.Review(n.id)) else m.back()
    }
    fun saveBad(reasons: List<String>, other: String) {
        m.markBad(f, reasons, other, notes)
        picking = false
        goNext()
    }

    Column(Modifier.fillMaxSize()) {
        Box { VideoPlayer(m, f); IconButton(onClick = { m.back() }, modifier = Modifier.padding(2.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x88000000)).padding(5.dp)) } }
        ListStatusBar(m)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FileLine(m, f)
            // While a newer list is coming in, the robot's call here may be out of date: don't offer it.
            if (m.updating == null) m.robotFor(f)?.let { g -> RobotLine(g, d?.status, d?.robot) { m.agreeWithRobot(f.id); goNext() } }
            // The card takes what's left; on a very small phone it can scroll on its own.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (picking) BadReasons(m, f, onPick = { saveBad(listOf(it), "") }, onOther = { otherReason = "" },
                    onCancel = { picking = false })
                else MovieCard(m, f) { m.open(Screen.MovieEdit(f.id)) }
            }
        }
        // ---- bottom bar
        Column(Modifier.background(C.bg).padding(horizontal = 12.dp).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { picking = !picking }, shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, C.bad),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (picking) C.bad else Color(0xFF3D1F1B),
                        contentColor = if (picking) Color(0xFF2A0C08) else Color(0xFFFFB3AA)),
                    modifier = Modifier.weight(1f).height(52.dp)) {
                    Text(if (picking) "Pick a reason ↑" else "✕  Bad", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Button(onClick = { m.markGood(f, notes); goNext() }, shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.good, contentColor = Color(0xFF0D2416)),
                    modifier = Modifier.weight(1f).height(52.dp)) {
                    Text("✓  Good", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallLink("‹ Prev") { m.previousBefore(f.id)?.let { m.replaceTop(Screen.Review(it.id)) } }
                val (i, n) = remember(f.id, m.files) { m.siblingsCount(f) }
                Text(if (n > 1) "$i of $n" else "", color = C.muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                SmallLink(if (notes.isNotBlank()) "Notes (1)" else "Notes", C.gold) { editNotes = true }
                // Which copy the TV plays first for this movie
                if (d?.status != "bad") SmallLink(if (d?.playFirst == true) "★ Plays first" else "☆ Play first",
                    if (d?.playFirst == true) C.good else C.gold) { m.togglePlayFirst(f) }
                if (m.lastUndoLabel != null) SmallLink("Undo", C.gold) { m.undoLast()?.let { id -> m.replaceTop(Screen.Review(id)) } }
                Spacer(Modifier.weight(1f))
                // In "To review", Skip goes to the next file that still needs a decision.
                SmallLink("Skip ›") { goNext(onlyUnreviewed = m.mode == QueueMode.REVIEW) }
            }
        }
    }
    if (editNotes) {
        var text by remember { mutableStateOf(notes) }
        AlertDialog(onDismissRequest = { editNotes = false }, containerColor = C.panel,
            title = { Text("Notes", color = C.text) },
            text = { OutlinedTextField(text, { text = it }, colors = fieldColors(), placeholder = { Text("Timestamps, quality, anything to remember") },
                modifier = Modifier.fillMaxWidth().height(120.dp)) },
            confirmButton = { TextButton(onClick = {
                notes = text; editNotes = false
                if (notes != (m.decisionFor(f)?.notes ?: "")) m.update(f.id) { it.copy(notes = notes) }
            }) { Text("Save", color = C.gold) } },
            dismissButton = { TextButton(onClick = { editNotes = false }) { Text("Cancel", color = C.muted) } })
    }
    otherReason?.let { start ->
        var text by remember { mutableStateOf(start) }
        AlertDialog(onDismissRequest = { otherReason = null }, containerColor = C.panel,
            title = { Text("What’s wrong with it?", color = C.text) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, colors = fieldColors(), placeholder = { Text("Other reason") }) },
            confirmButton = { TextButton(onClick = { otherReason = null; if (text.isNotBlank()) saveBad(emptyList(), text.trim()) }) {
                Text("Save · next", color = C.gold) } },
            dismissButton = { TextButton(onClick = { otherReason = null }) { Text("Cancel", color = C.muted) } })
    }
}

@Composable
private fun SmallLink(label: String, color: Color = C.muted, onClick: () -> Unit) {
    Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp))
}

/** File name, Archive link, and the warnings that matter, in two short lines. */
@Composable
private fun FileLine(m: AppModel, f: FileItem) {
    val ctx = LocalContext.current
    val d = m.decisionFor(f)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.name, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("Page ↗", color = C.gold, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://archive.org/details/${f.archiveItem}")))
            }.padding(horizontal = 6.dp, vertical = 2.dp))
        }
        val warn = listOfNotNull(
            m.foreignReason(f)?.let { "Non-English? $it" },
            // the bot's tape name says year, shape and sound; the uploader's "Version 2" says nothing
            f.tapeName ?: listOfNotNull(f.versionLabel?.let { "“Version $it”" }, f.releaseYear?.let { "$it tape" }).joinToString(" · ").ifEmpty { null },
            mins(f.durationSec),
            when (d?.status) { "good" -> "Marked good"; "bad" -> "Marked bad: " + (d.reasons + listOfNotNull(d.otherReason.ifBlank { null })).joinToString(", "); else -> null },
        )
        if (warn.isNotEmpty()) Text(warn.joinToString(" · "), color = if (d?.status == "good") C.good else C.warn, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** What the bot thinks, in one line, with one-tap Agree when it's sure. */
@Composable
fun RobotLine(g: RobotGuess, myStatus: String?, myRobot: String?, onAgree: () -> Unit) {
    val color = when (g.verdict) { "good" -> C.good; "bad" -> C.bad; else -> C.warn }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel).border(1.dp, color, RoundedCornerShape(12.dp))
        .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("Bot: " + g.label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val why = if (g.verdict == "good") g.formatClues.firstOrNull() else g.why.firstOrNull()
            why?.let { Text(it, color = C.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (myStatus == null && g.verdict in setOf("good", "bad")) Button(onClick = onAgree, shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color(0xFF111111)),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
            modifier = Modifier.height(36.dp)) { Text("Agree", fontWeight = FontWeight.Bold) }
        else if (myStatus != null) Text(if (myStatus == g.verdict) "Agreed" + (if (myRobot == "agreed_all") " (all)" else "") else "You disagreed",
            color = if (myStatus == g.verdict) C.good else C.warn, fontSize = 12.sp)
    }
}

/** The movie this file is: poster, name, genres and the story. Tap to pick a different one. */
@Composable
private fun MovieCard(m: AppModel, f: FileItem, onFix: () -> Unit) {
    val (movie, confirmed) = m.movieFor(f)
    val genres = m.genresFor(f)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel).clickable(onClick = onFix).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(84.dp, 126.dp).clip(RoundedCornerShape(6.dp)).background(C.panel2)) {
            movie?.poster?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (movie == null || movie.isNoMatch) f.titleName else movie.heading, color = C.text, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(when {
                movie?.isNoMatch == true -> "No match (your call)"
                movie?.isManual == true -> "Your own name"
                confirmed -> "You confirmed this"
                movie != null && f.botMovie != null && movie.tmdbId == f.botMovie.tmdbId -> "The bot is sure"
                movie != null -> "The bot’s best guess — check it"
                else -> "Not matched yet — tap to find it"
            }, color = if (confirmed || (f.botMovie != null && movie?.tmdbId == f.botMovie.tmdbId)) C.good else C.warn, fontSize = 12.sp)
            if (genres.isNotEmpty()) Text(genres.joinToString(" · "), color = C.gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            (movie?.overview ?: f.botMovie?.overview ?: f.guess?.overview)?.let {
                Text(it, color = C.muted, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Text("Change movie ›", color = C.gold, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

/** Bad reasons in place of the grid. One tap = saved, next file. */
@Composable
private fun BadReasons(m: AppModel, f: FileItem, onPick: (String) -> Unit, onOther: () -> Unit, onCancel: () -> Unit) {
    val sound = m.soundFor(f)?.verdict
    val hint = when (sound) { "no_audio_track", "silent_audio" -> "No audio"; "no_video_track" -> "No video"; "could_not_open" -> "Won’t play"; else -> null }
        ?: m.robotFor(f)?.reason
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("What’s wrong?", C.bad)
        Spacer(Modifier.weight(1f))
        Text("Kept and hidden, never deleted", color = C.muted, fontSize = 11.sp)
    }
    Grid((BAD_REASONS + "Other…").map { it to it }, columns = 3) { id, label ->
        Cell(label, selected = false, guess = id == hint, color = C.bad, height = 44.dp,
            onClick = { if (id == "Other…") onOther() else onPick(id) })
    }
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = C.muted) }
}

@Composable
private fun Grid(items: List<Pair<String, String>>, columns: Int, cell: @Composable (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (id, label) -> Box(Modifier.weight(1f)) { cell(id, label) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A small square button. [guess] outlines the robot's guess when nothing is picked yet. */
@Composable
private fun Cell(label: String, selected: Boolean, guess: Boolean, onClick: () -> Unit, color: Color = C.gold,
                 modifier: Modifier = Modifier, height: Dp = 36.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(9.dp))
        .background(if (selected) color else C.panel)
        .border(if (guess && !selected) 2.dp else 1.dp, if (selected || guess) color else C.line, RoundedCornerShape(9.dp))
        .clickable(onClick = onClick).padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) Color(0xFF1A1206) else C.text, fontSize = 12.sp, lineHeight = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(m: AppModel, f: FileItem) {
    val ctx = LocalContext.current
    // Play the uploaded file when the phone can; otherwise use the Archive's MP4 copy of it.
    val playable = remember(f.id) {
        if (f.phonePlayable) f else f.copyIds.mapNotNull { m.byId[it] }.firstOrNull { it.phonePlayable } ?: f
    }
    val sound = m.soundFor(f)
    var error by remember(f.id) { mutableStateOf<String?>(null) }
    var liveAudio by remember(f.id) { mutableStateOf<Boolean?>(null) }
    val player = remember(f.id) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(playable.url)); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { error = "Can’t play this file on the phone (${e.errorCodeName})" }
            override fun onTracksChanged(t: Tracks) { liveAudio = t.groups.any { it.type == MediaC.TRACK_TYPE_AUDIO } }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    Column {
        Box(Modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            Box(Modifier.heightIn(max = 210.dp).aspectRatio(16f / 9f)) {
                AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } },
                    update = { it.player = player }, modifier = Modifier.fillMaxSize())
                error?.let { Text(it, color = C.warn, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center).padding(16.dp)) }
            }
        }
        Row(Modifier.fillMaxWidth().background(C.panel).padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Start" to 0.02, "Mid" to 0.5, "End" to 0.95).forEach { (label, frac) ->
                Text(label, color = C.gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .clickable { val dur = player.duration; if (dur > 0) player.seekTo((dur * frac).toLong()) }
                    .background(C.panel2).padding(horizontal = 9.dp, vertical = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            val bits = listOfNotNull(
                when {
                    sound == null -> null
                    sound.verdict in SOUND_PROBLEMS -> soundLabel(sound.verdict)
                    liveAudio == false -> "No sound track"
                    else -> "♪ OK"
                },
                sound?.width?.let { "${it}×${sound.height}" },
                if (playable.id != f.id) "MP4 copy" else null,
            )
            val bad = sound?.verdict in SOUND_PROBLEMS || liveAudio == false
            Text(bits.joinToString(" · "), color = if (bad) C.warn else C.muted, fontSize = 11.sp, maxLines = 1)
        }
    }
}
