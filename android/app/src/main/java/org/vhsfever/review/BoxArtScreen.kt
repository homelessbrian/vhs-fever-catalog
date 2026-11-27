package org.vhsfever.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage

@Composable
private fun TopBar(title: String, sub: String?, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
        Column(Modifier.weight(1f)) {
            Text(title, color = C.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, color = C.muted, fontSize = 12.sp) }
        }
        action?.invoke()
    }
}

/** Every shelf movie and its box, sorted into what needs a look. */
@Composable
fun BoxArtListScreen(m: AppModel) {
    val b = m.boxArt
    LaunchedEffect(Unit) { if (!b.loaded) b.load() }
    val list = remember(b.titles, b.picks, b.onTv, b.filter, b.search) { b.list() }
    Column(Modifier.fillMaxSize()) {
        TopBar("Box art", "Pick the box each movie shows on the TV", { m.back() }) {
            IconButton(onClick = { b.load() }) { Icon(Icons.Filled.Refresh, "Reload", tint = C.text) }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = b.search, onValueChange = { b.search = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = C.muted) }, placeholder = { Text("Search movies") },
                colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArtFilter.entries.forEach { f -> Chip("${f.label} · ${b.count(f)}", b.filter == f, { b.filter = f }) }
            }
            if (b.status.isNotEmpty()) Text(b.status, color = C.muted, fontSize = 13.sp)
            when (b.filter) {
                ArtFilter.REVIEW -> "The bot found boxes for these but isn't sure which is right."
                ArtFilter.ON_TV -> "These already show a real box on the TV. Tap one to see other versions the bot found, or search for a better one."
                ArtFilter.NONE -> "Nothing found yet. These show the TMDB poster on a VHS sleeve. The bot looks again every two weeks."
                else -> null
            }?.let { Text(it, color = C.muted, fontSize = 12.sp) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(list, key = { it.key }) { t -> ArtRow(m, t) }
        }
    }
}

@Composable
private fun ArtRow(m: AppModel, t: ArtTitle) {
    val b = m.boxArt
    val shown = b.shown(t)
    Row(Modifier.fillMaxWidth().clickable { m.open(Screen.BoxArtPick(t.key)) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(46.dp, 72.dp).clip(RoundedCornerShape(4.dp)).background(C.panel2), contentAlignment = Alignment.Center) {
            if (shown != null) AsyncImage(model = shown.thumb, contentDescription = null, contentScale = ContentScale.Crop,
                alignment = frontSide(shown, b.isFlipped(t.key, shown)), modifier = Modifier.fillMaxSize())
            else Text("TMDB", color = C.muted, fontSize = 10.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(t.name, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 3.dp)) {
                t.year?.let { Tag(it) }
                val n = b.candidatesOf(t).size
                Tag(if (n == 1) "1 version" else "$n versions")
                when (b.stateOf(t)) {
                    ArtFilter.MINE -> Tag(if (b.picks[t.key] == "none") "You: TMDB poster" else "Your pick", C.blueBg, C.blue)
                    ArtFilter.ON_TV -> Tag(b.whoChose(t) ?: "On TV", C.panel2, C.good)
                    ArtFilter.REVIEW -> Tag("Needs a look", C.warnBg, C.warn)
                    else -> {}
                }
            }
        }
    }
}

/** All the boxes found for one movie; tap one to show it on the TV. */
@Composable
fun BoxArtPickScreen(m: AppModel, key: String) {
    val b = m.boxArt
    val t = b.titles.firstOrNull { it.key == key } ?: run { LaunchedEffect(key) { m.back() }; return }
    val picked = b.picks[key]
    val onTv = b.onTv[key]
    var big by remember { mutableStateOf<ArtCandidate?>(null) }
    var query by remember(key) { mutableStateOf(t.name) }
    LaunchedEffect(key) { b.clearSearch(); b.loadInfo(t) }
    val listed = b.candidatesOf(t)
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        TopBar(t.name, listOfNotNull(t.year, if (listed.size == 1) "1 version" else "${listed.size} versions").joinToString(" · "), { m.back() })
        if (b.status.isNotEmpty()) Text(b.status, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // which movie and which tape this is, so the right version's box gets picked
            item(span = { GridItemSpan(2) }) { MovieContext(m, t) }
            item(span = { GridItemSpan(2) }) { Column {
                Text(when {
                    picked == "none" -> "You picked the TMDB poster for this one."
                    picked != null -> "You picked this movie's box. Tap another to change it."
                    onTv != null -> "The box marked ON TV is showing now. Tap another to use it instead, or search below for a better one."
                    t.sure -> "The bot picked the box marked BOT. Tap any box to use it instead."
                    listed.isEmpty() -> "No boxes found yet; the TV shows the TMDB poster. Try searching below."
                    else -> "The bot isn't sure. Tap the right box (a full wrap gives the TV a real back and spine too)."
                } + if (listed.any { it.kind != "wrap" }) " Front and back scanned as two pictures? Tap the front, then \"Use as the back\" on the other. " +
                    "Spine attached to the front or back? Tap that picture and pick \"Use as spine\"." else "", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                // straighten up the box that's showing: spine lines and trimmed edges
                if (b.shown(t) != null && picked != "none") TextButton(onClick = { m.open(Screen.BoxAlign(key)) }) {
                    Text("Line up this box (spine and edges)", color = C.gold)
                }
                b.spines[key]?.let { (sp, _) ->
                    TextButton(onClick = { m.open(Screen.BoxSpine(key, sp.id)) }) { Text("Change where the spine is", color = C.gold) }
                }
            } }
            items(listed, key = { it.id }) { c ->
                val chosen = picked == c.id || (picked == null && onTv == c.id)
                ArtCard(c, chosen = chosen, bot = t.botPick == c.id && t.sure, onTv = onTv == c.id, flipped = b.isFlipped(key, c),
                    isBack = b.backs[key]?.id == c.id, isSpine = b.spines[key]?.first?.id == c.id,
                    onPick = { if (!chosen) b.pick(key, c.id, c.takeIf { it.source == "manual" }) }, onLook = { big = c },
                    onBack = if (chosen || c.kind == "wrap") null else ({ b.setBack(key, if (b.backs[key]?.id == c.id) null else c) }))
            }
            item {
                // keep the TMDB poster instead of any box
                val chosen = picked == "none"
                Column(Modifier.clip(RoundedCornerShape(10.dp)).background(C.panel)
                    .border(if (chosen) 3.dp else 1.dp, if (chosen) C.gold else C.line, RoundedCornerShape(10.dp))
                    .clickable { b.pick(key, "none") }.padding(10.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.62f).clip(RoundedCornerShape(6.dp)).background(C.panel2),
                        contentAlignment = Alignment.Center) { Text("TMDB poster\non a VHS sleeve", color = C.muted, fontSize = 13.sp) }
                    Text("No box: use the poster", color = C.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (picked != null) item(span = { GridItemSpan(2) }) {
                TextButton(onClick = { b.pick(key, null) }) { Text("Let the bot choose again", color = C.gold) }
            }
            // look for a box yourself: any words, or paste an Archive link or item id
            item(span = { GridItemSpan(2) }) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Search the Archive yourself")
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Words, or paste an Archive link") },
                        trailingIcon = { IconButton(onClick = { b.searchArchive(query) }, enabled = !b.searching) {
                            Icon(Icons.Filled.Search, "Search", tint = C.gold) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { b.searchArchive(query) }),
                        colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    if (b.searchNote.isNotEmpty()) Text(b.searchNote, color = C.muted, fontSize = 12.sp)
                    if (b.searchResults.isNotEmpty()) Text("Tap a picture to use it. A tall picture becomes the front; " +
                        "a wide scan of the whole sleeve gives the back and spine too.", color = C.muted, fontSize = 12.sp)
                }
            }
            items(b.searchResults.filter { r -> listed.none { it.id == r.id } }, key = { "s-" + it.id }) { c ->
                ArtCard(c, chosen = picked == c.id, bot = false, onTv = false, isBack = b.backs[key]?.id == c.id,
                    isSpine = b.spines[key]?.first?.id == c.id,
                    onPick = { b.pick(key, c.id, c) }, onLook = { big = c },
                    onBack = { b.setBack(key, if (b.backs[key]?.id == c.id) null else c) })
            }
        }
    }
    big?.let { c ->
        Dialog(onDismissRequest = { big = null }) {
            Column(Modifier.clip(RoundedCornerShape(12.dp)).background(C.panel).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AsyncImage(model = c.url, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(460.dp))
                Text(c.label, color = C.text, fontSize = 13.sp)
                Text(c.why.joinToString(" · "), color = C.muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { b.pick(key, c.id, c.takeIf { it.source == "manual" }); big = null }) { Text("Use as front", color = C.gold) }
                    if (c.kind != "wrap") TextButton(onClick = { b.setBack(key, c); big = null }) { Text("Use as back", color = C.gold) }
                    // mark the spine on this picture (a spine scanned attached to the front or the back)
                    if (c.kind != "wrap") TextButton(onClick = { big = null; m.open(Screen.BoxSpine(key, c.id)) }) { Text("Use as spine", color = C.gold) }
                }
                // a whole wrap scanned the other way round (front on the left): swap the front and the back
                if (c.kind == "wrap") {
                    val flipped = b.isFlipped(key, c)
                    Text(if (flipped) "The TV uses the left half as the front." else "The TV uses the right half as the front.",
                        color = C.muted, fontSize = 12.sp)
                    TextButton(onClick = { b.flip(key, c); big = null }) {
                        Text(if (flipped) "Flip: use the right half as the front" else "Flip: use the left half as the front", color = C.gold)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    c.link?.let { l -> TextButton(onClick = { uri.openUri(l) }) { Text("Open on Archive", color = C.blue) } }
                    TextButton(onClick = { big = null }) { Text("Close", color = C.muted) }
                }
            }
        }
    }
}

@Composable
private fun ArtCard(c: ArtCandidate, chosen: Boolean, bot: Boolean, onTv: Boolean, onPick: () -> Unit, onLook: () -> Unit,
                    isBack: Boolean = false, onBack: (() -> Unit)? = null, isSpine: Boolean = false, flipped: Boolean = false) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(C.panel)
        .border(if (chosen) 3.dp else 1.dp, if (chosen) C.gold else C.line, RoundedCornerShape(10.dp))
        .clickable(onClick = onPick).padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.62f).clip(RoundedCornerShape(6.dp)).background(Color.Black)
            .clickable(onClick = onLook)) {
            // a full wrap shows its front half here; tap the picture to see all of it
            AsyncImage(model = c.thumb, contentDescription = null, contentScale = ContentScale.Crop,
                alignment = frontSide(c, flipped), modifier = Modifier.fillMaxSize())
            Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (bot) Tag("BOT", C.panel2, C.good)
                if (onTv) Tag("ON TV", C.blueBg, C.blue)
                if (isBack) Tag("BACK", C.warnBg, C.warn)
                if (isSpine) Tag("SPINE", C.warnBg, C.warn)
            }
        }
        Text(c.label, color = C.text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Tag(c.sourceName)
            Tag(when (c.kind) { "wrap" -> "Full wrap"; "front" -> if (c.hasBack) "Front + back" else "Front"; else -> "Picture" })
        }
        // a separate picture for the back (for fronts and backs scanned as two pictures)
        if (onBack != null) Text(if (isBack) "Remove as back" else "Use as the back", color = C.gold, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onBack).padding(vertical = 4.dp))
        if (c.score > 0) Text("Match ${c.score}", color = if (c.score >= 70) C.good else C.muted, fontSize = 11.sp)
    }
}

/** Where a box's front is in its picture: the right half of a wrap, or the left half of one scanned the other way round. */
private fun frontSide(c: ArtCandidate, flipped: Boolean) =
    if (c.kind != "wrap") Alignment.Center else if (flipped) Alignment.CenterStart else Alignment.CenterEnd

/** The movie (TMDB) and its tape(s) on file, with screenshots from the tape. */
@Composable
private fun MovieContext(m: AppModel, t: ArtTitle) {
    val b = m.boxArt
    val info = b.info[t.key]
    val tapes = remember(t.key, m.files, m.decisions) { m.tapesFor(t.key) }
    val shots = b.shots[t.key].orEmpty()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val uri = LocalUriHandler.current
    var more by remember(t.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(70.dp, 105.dp).clip(RoundedCornerShape(6.dp)).background(C.panel2), contentAlignment = Alignment.Center) {
                if (info?.poster != null) AsyncImage(model = info.poster, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
                else Text(if (t.tmdb == null) "No TMDB\nmatch" else "…", color = C.muted, fontSize = 10.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SectionLabel("The movie")
                Text(listOfNotNull(info?.title ?: t.name, (info?.year ?: t.year)?.let { "($it)" }).joinToString(" "),
                    color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                val facts = listOfNotNull(info?.runtime?.let { "$it min" }, info?.genres?.take(3)?.joinToString(", ")?.ifBlank { null })
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = C.muted, fontSize = 12.sp)
                info?.director?.let { Text("Directed by $it", color = C.muted, fontSize = 12.sp) }
                info?.cast?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", "), color = C.muted, fontSize = 12.sp, maxLines = 2) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    t.tmdb?.let { id -> Text("TMDB", color = C.blue, fontSize = 12.sp,
                        modifier = Modifier.clickable { uri.openUri("https://www.themoviedb.org/movie/$id") }) }
                    info?.imdbId?.let { id -> Text("IMDb", color = C.blue, fontSize = 12.sp,
                        modifier = Modifier.clickable { uri.openUri("https://www.imdb.com/title/$id/") }) }
                }
            }
        }
        info?.overview?.let {
            Text(it, color = C.text, fontSize = 13.sp, maxLines = if (more) 20 else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { more = !more })
        }
        if (tapes.isNotEmpty() || shots.isNotEmpty()) {
            SectionLabel(if (tapes.size > 1) "Your tapes (${tapes.size})" else "Your tape")
            tapes.take(3).forEach { f ->
                Text(listOfNotNull(f.name, f.releaseYear, mins(f.durationSec)).joinToString(" · "), color = C.muted, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { uri.openUri("https://archive.org/details/${f.archiveItem}") })
            }
            if (shots.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shots.take(3).forEach { p ->
                    AsyncImage(model = b.privateImage(ctx, p), contentDescription = "Screenshot from the tape",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).aspectRatio(4f / 3).clip(RoundedCornerShape(4.dp)).background(Color.Black))
                }
            }
        }
    }
}
