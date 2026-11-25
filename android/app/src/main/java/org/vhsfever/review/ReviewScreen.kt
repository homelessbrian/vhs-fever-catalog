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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(m: AppModel, fileId: String) {
    val f = m.byId[fileId] ?: run { Text("File not found", color = C.text, modifier = Modifier.padding(24.dp)); return }
    val d = m.decisionFor(f)
    val sound = m.soundFor(f)
    var showBad by remember(fileId) { mutableStateOf(false) }
    var notes by remember(fileId) { mutableStateOf(d?.notes ?: "") }
    var newTag by remember { mutableStateOf<String?>(null) }

    fun saveNotes() { if (notes != (m.decisionFor(f)?.notes ?: "")) m.update(f.id) { it.copy(notes = notes) } }
    fun goNext(onlyUnreviewed: Boolean = true) {
        saveNotes()
        val n = m.nextAfter(f.id, onlyUnreviewed)
        if (n != null) m.replaceTop(Screen.Review(n.id)) else m.back()
    }

    Column(Modifier.fillMaxSize()) {
        Box { VideoPlayer(m, f); IconButton(onClick = { saveNotes(); m.back() }, modifier = Modifier.padding(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x88000000)).padding(6.dp)) } }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    sound == null -> Tag("Sound not checked yet")
                    sound.verdict in SOUND_PROBLEMS -> Tag(soundLabel(sound.verdict) ?: sound.verdict, C.warnBg, C.warn)
                    else -> Tag("Sound OK", Color(0xFF1F3D27), Color(0xFFA8E6B4))
                }
                Tag(listOfNotNull(f.format, sound?.width?.let { "${it}×${sound.height}" }).joinToString(" · ").ifEmpty { f.ext.uppercase() })
                Tag(if (f.isCopy) "Archive copy" else "Uploaded file")
                f.versionLabel?.let { Tag("“Version $it”") }
                f.partLabel?.let { Tag(it) }
                if (f.copyIds.isNotEmpty()) Tag("Decision also covers its Archive copy")
                when (d?.status) {
                    "good" -> Tag("Already marked good", Color(0xFF1F3D27), Color(0xFFA8E6B4))
                    "bad" -> Tag("Already marked bad", C.warnBg, C.warn)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(f.name, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                val ctx = LocalContext.current
                Text("Archive item: ${f.archiveItem} ↗", color = C.gold, fontSize = 12.sp, modifier = Modifier.clickable {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://archive.org/details/${f.archiveItem}")))
                })
                m.foreignReason(f)?.let { Text("Looks non-English: $it", color = C.warn, fontSize = 12.sp) }
                if (f.modelReasons.isNotEmpty()) Text("Why it’s flagged: " + f.modelReasons.joinToString(", "), color = C.muted, fontSize = 12.sp)
            }
            MovieCard(m, f) { saveNotes(); m.open(Screen.MovieEdit(f.id)) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Shelf")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val current = m.shelfFor(f)
                    m.shelves.forEach { s -> Chip(s.name, current == s.id, { m.update(f.id) { it.copy(shelf = s.id) } }, C.blue, Color(0xFF0E2238)) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Tags")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val mine = d?.tags ?: emptyList()
                    m.tags.forEach { t -> Chip("#$t", t in mine, { m.update(f.id) { x -> x.copy(tags = if (t in x.tags) x.tags - t else x.tags + t) } }, C.good) }
                    Chip("+ New tag", false, { newTag = "" })
                }
            }
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") },
                placeholder = { Text("Timestamps, quality, anything to remember") }, colors = fieldColors(),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(96.dp))
            d?.status?.let { st ->
                Text(if (st == "good") "Marked good" else "Marked bad: " + (d.reasons + listOfNotNull(d.otherReason.ifBlank { null })).joinToString(", "),
                    color = if (st == "good") C.good else C.bad, fontSize = 13.sp)
            }
        }
        Column(Modifier.background(C.bg).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { showBad = true }, shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, C.bad),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF3D1F1B), contentColor = Color(0xFFFFB3AA)),
                    modifier = Modifier.weight(1f).height(56.dp)) { Text("✕  Bad", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                Button(onClick = {
                    m.update(f.id, "Marked good") { it.copy(status = "good", reasons = emptyList(), otherReason = "", notes = notes) }
                    goNext()
                }, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = C.good, contentColor = Color(0xFF0D2416)),
                    modifier = Modifier.weight(1f).height(56.dp)) { Text("✓  Good", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { saveNotes(); m.previousBefore(f.id)?.let { m.replaceTop(Screen.Review(it.id)) } }) { Text("‹ Previous", color = C.muted) }
                val (i, n) = remember(f.id, m.files) { m.siblingsCount(f) }
                Text("File $i of $n in this movie", color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (m.lastUndoLabel != null) TextButton(onClick = { m.undoLast()?.let { id -> m.replaceTop(Screen.Review(id)) } }) { Text("Undo", color = C.gold) }
                // In "To review", Skip goes to the next file that still needs a decision, never back to reviewed ones.
                TextButton(onClick = { goNext(onlyUnreviewed = m.mode == QueueMode.REVIEW) }) { Text("Skip ›", color = C.muted) }
            }
        }
    }
    if (showBad) BadSheet(m, f, onDismiss = { showBad = false }) { reasons, other ->
        showBad = false
        m.update(f.id, "Marked bad") { it.copy(status = "bad", reasons = reasons, otherReason = other, notes = notes) }
        goNext()
    }
    newTag?.let { t ->
        var text by remember { mutableStateOf(t) }
        AlertDialog(onDismissRequest = { newTag = null }, containerColor = C.panel,
            title = { Text("New tag", color = C.text) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, colors = fieldColors(), placeholder = { Text("e.g. Disney, Christmas") }) },
            confirmButton = { TextButton(onClick = {
                val clean = text.trim()
                if (clean.isNotEmpty()) { m.addTag(clean); m.update(f.id) { it.copy(tags = (it.tags + clean).distinct()) } }
                newTag = null }) { Text("Add", color = C.gold) } },
            dismissButton = { TextButton(onClick = { newTag = null }) { Text("Cancel", color = C.muted) } })
    }
}

@Composable
fun MovieCard(m: AppModel, f: FileItem, onFix: () -> Unit) {
    val (movie, confirmed) = m.movieFor(f)
    val rel = m.releaseFor(f)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.panel).border(1.dp, C.line, RoundedCornerShape(14.dp))
        .clickable(onClick = onFix).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp, 69.dp).clip(RoundedCornerShape(5.dp)).background(C.panel2)) {
            (movie?.poster ?: movie?.episode?.still)?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val kind = when {
                movie?.isEpisode == true -> "Episode"
                movie?.type == "tv" || (movie == null && f.tv != null) -> "Show"
                else -> "Movie"
            }
            SectionLabel(if (confirmed) "$kind · confirmed" else if (movie != null) "$kind · suggested" else "$kind · not matched", if (confirmed) C.good else C.gold)
            Text(movie?.heading ?: f.titleName, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            movie?.episode?.let { e -> Text(e.label + (e.airDate?.let { " · aired $it" } ?: ""), color = C.gold, fontSize = 13.sp) }
            if (movie?.type == "tv" && movie.episode == null) Text("Episode not picked yet", color = C.muted, fontSize = 12.sp)
            Text("Release: " + listOfNotNull(rel.media, rel.year, rel.region, rel.distributor, rel.notes).joinToString(" · ").ifEmpty { "unknown" },
                color = C.muted, fontSize = 12.sp)
        }
        Text("Fix", color = C.gold, fontWeight = FontWeight.SemiBold)
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
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } },
                update = { it.player = player }, modifier = Modifier.fillMaxSize())
            error?.let { Text(it, color = C.warn, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center).padding(16.dp)) }
        }
        Row(Modifier.fillMaxWidth().background(C.panel).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Jump:", color = C.muted, fontSize = 12.sp)
            listOf("Start" to 0.02, "Middle" to 0.5, "End" to 0.95).forEach { (label, frac) ->
                Text(label, color = C.gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .clickable { val dur = player.duration; if (dur > 0) player.seekTo((dur * frac).toLong()) }
                    .background(C.panel2).padding(horizontal = 10.dp, vertical = 5.dp))
            }
            Spacer(Modifier.weight(1f))
            liveAudio?.let { Text(if (it) "♪ has sound track" else "No sound track", color = if (it) C.muted else C.warn, fontSize = 12.sp) }
        }
        if (playable.id != f.id) Text("Playing the Archive’s MP4 copy — the uploaded file can’t play on a phone.",
            color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BadSheet(m: AppModel, f: FileItem, onDismiss: () -> Unit, onSave: (List<String>, String) -> Unit) {
    val existing = m.decisionFor(f)
    val picked = remember { mutableStateListOf<String>().apply { addAll(existing?.reasons ?: emptyList()) } }
    var other by remember { mutableStateOf(existing?.otherReason ?: "") }
    LaunchedEffect(Unit) {
        if (picked.isEmpty() && m.soundFor(f)?.verdict in setOf("no_audio_track", "silent_audio")) picked.add("No audio")
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.panel) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("What’s wrong with it?", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Pick one or more. The file is kept and hidden from the catalog, never deleted.", color = C.muted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BAD_REASONS.forEach { r -> Chip(r, r in picked, { if (r in picked) picked.remove(r) else picked.add(r) }, C.bad, Color(0xFF2A0C08)) }
            }
            OutlinedTextField(other, { other = it }, singleLine = true, label = { Text("Other reason (optional)") },
                colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
            if (f.copyIds.isNotEmpty()) Text("This also applies to the Archive’s copy of this file, unless the sound check finds the copy is different.",
                color = C.muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Cancel", color = C.text) }
                Button(onClick = { onSave(picked.toList(), other.trim()) }, enabled = picked.isNotEmpty() || other.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = C.bad, contentColor = Color(0xFF2A0C08)),
                    modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Save · next file", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
