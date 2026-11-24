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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovieScreen(m: AppModel, fileId: String) {
    val f = m.byId[fileId] ?: return
    val scope = rememberCoroutineScope()
    val (current, _) = m.movieFor(f)
    val rel0 = m.releaseFor(f)
    var chosen by remember(fileId) { mutableStateOf(current) }
    var query by remember(fileId) { mutableStateOf(f.titleName.replace(Regex("\\s*\\(.*?\\)"), "")) }
    var type by remember(fileId) { mutableStateOf(if (f.suggestions.firstOrNull()?.type == "tv") "tv" else "movie") }
    var results by remember(fileId) { mutableStateOf(f.suggestions) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Int?>(null) }
    val details = remember { mutableStateMapOf<Int, Tmdb.Details?>() }
    var media by remember(fileId) { mutableStateOf(rel0.media ?: "UNKNOWN") }
    var year by remember(fileId) { mutableStateOf(rel0.year ?: "") }
    var region by remember(fileId) { mutableStateOf(rel0.region ?: "") }
    var distributor by remember(fileId) { mutableStateOf(rel0.distributor ?: "") }
    var notes by remember(fileId) { mutableStateOf(rel0.notes ?: "") }
    var allInRelease by remember(fileId) { mutableStateOf(false) }
    val ctx = LocalContext.current
    val ceiling = year.toIntOrNull() ?: f.tmdbCeiling

    fun runSearch() {
        if (!Tmdb.available) { searchError = "This build has no TMDB key"; return }
        searching = true; searchError = null; expanded = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Tmdb.search(query, type, ceiling) } }
            r.onSuccess { results = it; if (it.isEmpty()) searchError = "No results" }.onFailure { searchError = it.message }
            searching = false
        }
    }

    fun loadDetails(mv: Movie) {
        if (details.containsKey(mv.tmdbId)) return
        details[mv.tmdbId] = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Tmdb.details(mv) } }
            r.onSuccess { details[mv.tmdbId] = it }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { m.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
            Column(Modifier.weight(1f)) {
                Text("Movie & release", color = C.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(f.name, color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("Movie", C.gold)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.panel).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp, 69.dp).clip(RoundedCornerShape(5.dp)).background(C.panel2)) {
                    chosen?.poster?.let { AsyncImage(it, null, Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f)) {
                    Text(chosen?.let { it.title + (it.year?.let { y -> " ($y)" } ?: "") } ?: "No movie chosen", color = C.text, fontWeight = FontWeight.SemiBold)
                    Text(if (chosen == null) "Pick one below, or leave unmatched" else "Tap a result below to change it", color = C.muted, fontSize = 12.sp)
                }
                if (chosen != null) Text("Clear", color = C.muted, modifier = Modifier.clickable { chosen = null }.padding(6.dp))
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Title, “Title 1961”, or IMDb ID / link") }, colors = fieldColors(), shape = RoundedCornerShape(12.dp),
                trailingIcon = { IconButton(onClick = { runSearch() }) { Icon(Icons.Filled.Search, "Search", tint = C.gold) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("Movies", type == "movie", { type = "movie"; runSearch() })
                Chip("TV", type == "tv", { type = "tv"; runSearch() })
                if (searching) CircularProgressIndicator(Modifier.size(20.dp), color = C.gold, strokeWidth = 2.dp)
            }
            ceiling?.let { Text("This is a $it release — anything released after $it is marked too new.", color = C.muted, fontSize = 12.sp) }
            searchError?.let { Text(it, color = C.warn, fontSize = 13.sp) }
            if (results === f.suggestions && results.isNotEmpty()) SectionLabel("Suggested by the catalog build")
            results.forEach { mv ->
                val open = expanded == mv.tmdbId
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (chosen?.tmdbId == mv.tmdbId) Color(0xFF1A2A1F) else C.panel)
                    .border(1.dp, if (chosen?.tmdbId == mv.tmdbId) C.good else C.line, RoundedCornerShape(12.dp))
                    .clickable { expanded = if (open) null else mv.tmdbId; if (!open) loadDetails(mv) }.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.alpha(if (mv.tooNew) 0.65f else 1f)) {
                        Box(Modifier.size(if (open) 70.dp else 46.dp, if (open) 105.dp else 69.dp).clip(RoundedCornerShape(5.dp)).background(C.panel2)) {
                            mv.poster?.let { AsyncImage(it, null, Modifier.fillMaxSize()) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(mv.title, color = C.text, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(mv.year, if (mv.type == "tv") "TV" else "Movie").joinToString(" · "), color = C.muted, fontSize = 12.sp)
                            if (mv.tooNew) Text("Too new for a ${ceiling ?: ""} release", color = C.bad, fontSize = 12.sp)
                            if (open && !mv.overview.isNullOrBlank()) Text(mv.overview, color = C.muted, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (open) {
                        val det = details[mv.tmdbId]
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("TMDB ↗", color = C.gold, modifier = Modifier.clickable {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/${mv.type}/${mv.tmdbId}"))) })
                            (det?.imdbId ?: mv.imdbId)?.let { id -> Text("IMDb ↗", color = C.gold, modifier = Modifier.clickable {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.imdb.com/title/$id/"))) }) }
                            det?.runtime?.let { Text("$it min", color = C.muted) }
                        }
                        if (det == null) Text("Loading release dates…", color = C.muted, fontSize = 12.sp)
                        else if (mv.type == "movie") {
                            SectionLabel("VHS / DVD releases on TMDB")
                            if (det.physical.isEmpty()) Text("None listed", color = C.muted, fontSize = 12.sp)
                            det.physical.take(8).forEach { (where, date) ->
                                Row { Text(where, color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(date, color = C.text, fontSize = 12.sp) }
                            }
                        }
                        Button(onClick = { chosen = mv.copy(imdbId = det?.imdbId ?: mv.imdbId, overview = null, tooNew = false); expanded = null },
                            colors = ButtonDefaults.buttonColors(containerColor = C.good, contentColor = Color(0xFF0D2416)),
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(46.dp)) { Text("Use this movie", fontWeight = FontWeight.Bold) }
                    }
                }
            }

            SectionLabel("Release", Color(0xFF69A7E8))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("VHS", "DVD", "LASERDISC", "BETAMAX", "BROADCAST", "UNKNOWN").forEach { x ->
                    Chip(x.lowercase().replaceFirstChar { it.uppercase() }.replace("Vhs", "VHS").replace("Dvd", "DVD").replace("Laserdisc", "LaserDisc").replace("Broadcast", "TV broadcast"),
                        media == x, { media = x }, C.blue, Color(0xFF0E2238))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Release year") }, singleLine = true,
                    colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                OutlinedTextField(region, { region = it }, label = { Text("Country / region") }, singleLine = true,
                    colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f))
            }
            OutlinedTextField(distributor, { distributor = it }, label = { Text("Distributor / line") }, placeholder = { Text("e.g. Walt Disney Classics") },
                singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Edition notes") }, placeholder = { Text("e.g. Canadian copy, clamshell, rental") },
                singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())

            SectionLabel("Apply to")
            ScopeOption("This file" + if (f.copyIds.isNotEmpty()) " and its Archive copy" else "", !allInRelease) { allInRelease = false }
            if (f.editionId != null) {
                val n = m.files.count { it.editionId == f.editionId && !it.isCopy }
                ScopeOption("All $n uploaded files of “${f.titleName} (${f.releaseYear} ${f.media})”", allInRelease) { allInRelease = true }
            }
        }
        Button(onClick = {
            val rel = ReleaseInfo(media.takeIf { it != "UNKNOWN" }, year.ifBlank { null }, region.ifBlank { null },
                distributor.ifBlank { null }, notes.ifBlank { null })
            if (allInRelease && f.editionId != null) m.setReleaseDecision(f.editionId, chosen, rel)
            else m.update(f.id) { it.copy(movie = chosen, release = rel) }
            m.back()
        }, colors = ButtonDefaults.buttonColors(containerColor = C.gold, contentColor = Color(0xFF1A1206)), shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) { Text("Save", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ScopeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel)
        .border(1.dp, if (selected) C.gold else C.line, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = C.gold))
        Text(label, color = C.text, fontSize = 14.sp)
    }
}
