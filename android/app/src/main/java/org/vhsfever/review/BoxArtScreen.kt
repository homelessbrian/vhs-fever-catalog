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
                ArtFilter.BOT -> "The bot picked these by itself. Tap one to check it or pick another."
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
                alignment = if (shown.kind == "wrap") Alignment.CenterEnd else Alignment.Center, modifier = Modifier.fillMaxSize())
            else Text("TMDB", color = C.muted, fontSize = 10.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(t.name, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 3.dp)) {
                t.year?.let { Tag(it) }
                Tag("${t.candidates.size} found")
                when (b.stateOf(t)) {
                    ArtFilter.MINE -> Tag(if (b.picks[t.key] == "none") "You: TMDB poster" else "Your pick", C.blueBg, C.blue)
                    ArtFilter.BOT -> Tag("Bot picked", C.panel2, C.good)
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
    LaunchedEffect(key) { b.clearSearch() }
    val listed = b.candidatesOf(t)
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        TopBar(t.name, listOfNotNull(t.year, "${listed.size} boxes found").joinToString(" · "), { m.back() })
        if (b.status.isNotEmpty()) Text(b.status, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(span = { GridItemSpan(2) }) {
                Text(when {
                    picked == "none" -> "You picked the TMDB poster for this one."
                    picked != null -> "You picked this movie's box. Tap another to change it."
                    t.sure -> "The bot picked the box marked BOT. Tap any box to use it instead."
                    t.candidates.isEmpty() -> "No boxes found yet; the TV shows the TMDB poster."
                    else -> "The bot isn't sure. Tap the right box (a full wrap gives the TV a real back and spine too)."
                }, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            }
            items(listed, key = { it.id }) { c ->
                ArtCard(c, chosen = picked == c.id || (picked == null && onTv == c.id),
                    bot = t.botPick == c.id && t.sure, onTv = onTv == c.id,
                    onPick = { b.pick(key, c.id, c.takeIf { it.source == "manual" }) }, onLook = { big = c })
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
                ArtCard(c, chosen = picked == c.id, bot = false, onTv = false,
                    onPick = { b.pick(key, c.id, c) }, onLook = { big = c })
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
                    TextButton(onClick = { b.pick(key, c.id, c.takeIf { it.source == "manual" }); big = null }) { Text("Use this box", color = C.gold) }
                    c.link?.let { l -> TextButton(onClick = { uri.openUri(l) }) { Text("Open on Archive", color = C.blue) } }
                    TextButton(onClick = { big = null }) { Text("Close", color = C.muted) }
                }
            }
        }
    }
}

@Composable
private fun ArtCard(c: ArtCandidate, chosen: Boolean, bot: Boolean, onTv: Boolean, onPick: () -> Unit, onLook: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(C.panel)
        .border(if (chosen) 3.dp else 1.dp, if (chosen) C.gold else C.line, RoundedCornerShape(10.dp))
        .clickable(onClick = onPick).padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.62f).clip(RoundedCornerShape(6.dp)).background(Color.Black)
            .clickable(onClick = onLook)) {
            // a full wrap shows its front half here; tap the picture to see all of it
            AsyncImage(model = c.thumb, contentDescription = null, contentScale = ContentScale.Crop,
                alignment = if (c.kind == "wrap") Alignment.CenterEnd else Alignment.Center, modifier = Modifier.fillMaxSize())
            Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (bot) Tag("BOT", C.panel2, C.good)
                if (onTv) Tag("ON TV", C.blueBg, C.blue)
            }
        }
        Text(c.label, color = C.text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Tag(c.sourceName)
            Tag(when (c.kind) { "wrap" -> "Full wrap"; "front" -> if (c.hasBack) "Front + back" else "Front"; else -> "Picture" })
        }
        if (c.source != "manual") Text("Match ${c.score}", color = if (c.score >= 70) C.good else C.muted, fontSize = 11.sp)
    }
}
