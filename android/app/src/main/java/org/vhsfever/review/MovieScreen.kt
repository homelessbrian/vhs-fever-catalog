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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.OutlinedButton
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
    val looksTv = f.tv != null || f.suggestions.firstOrNull()?.type == "tv" || current?.type == "tv"
    var chosen by remember(fileId) { mutableStateOf(current) }
    var type by remember(fileId) { mutableStateOf(if (looksTv) "tv" else "movie") }
    var query by remember(fileId) {
        mutableStateOf(if (looksTv && !f.tv?.show.isNullOrBlank()) f.tv!!.show!! else f.titleName.replace(Regex("\\s*\\(.*?\\)"), ""))
    }
    var results by remember(fileId) { mutableStateOf(f.suggestions) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchNote by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    val details = remember { mutableStateMapOf<String, Tmdb.Details?>() }
    var media by remember(fileId) { mutableStateOf(rel0.media ?: "UNKNOWN") }
    var year by remember(fileId) { mutableStateOf(rel0.year ?: "") }
    var region by remember(fileId) { mutableStateOf(rel0.region ?: "") }
    var distributor by remember(fileId) { mutableStateOf(rel0.distributor ?: "") }
    var notes by remember(fileId) { mutableStateOf(rel0.notes ?: "") }
    var allInRelease by remember(fileId) { mutableStateOf(false) }
    // Episode picker: which show it is open for, and that show's episodes.
    var pickerShow by remember(fileId) { mutableStateOf<Movie?>(null) }
    var pickerFrom by remember(fileId) { mutableStateOf<String?>(null) }   // result row it opened from
    var episodes by remember(fileId) { mutableStateOf<List<Episode>?>(null) }
    var episodesError by remember(fileId) { mutableStateOf<String?>(null) }
    var season by remember(fileId) { mutableStateOf<Int?>(null) }
    val ctx = LocalContext.current
    val ceiling = year.toIntOrNull() ?: f.tmdbCeiling

    fun tooNew(y: String?) = ceiling != null && y?.take(4)?.toIntOrNull()?.let { it > ceiling } == true

    fun runSearch() {
        searchError = null; searchNote = null; expanded = null; pickerShow = null
        if (type == "tvdb" && !Tvdb.available) { searchError = "This build has no TVDB key"; return }
        if (type != "tvdb" && !Tmdb.available) { searchError = "This build has no TMDB key"; return }
        searching = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    when (type) {
                        "tvdb" -> Tvdb.searchShows(query, ceiling)
                        "tv" -> {
                            val t = Tmdb.search(query, "tv", ceiling)
                            if (t.isEmpty() && Tvdb.available) Tvdb.searchShows(query, ceiling).also { if (it.isNotEmpty()) searchNote = "Not on TMDB — showing TheTVDB results" }
                            else t
                        }
                        else -> Tmdb.search(query, "movie", ceiling)
                    }
                }
            }
            r.onSuccess { results = it; if (it.isEmpty()) searchError = "No results" }.onFailure { searchError = it.message }
            searching = false
        }
    }

    fun loadDetails(mv: Movie) {
        if (details.containsKey(mv.key)) return
        details[mv.key] = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Tmdb.details(mv) } }
            r.onSuccess { details[mv.key] = it }
        }
    }

    /** Opens the episode list for [show], jumping to the season the file name points at. */
    fun openPicker(show: Movie, fromRow: String) {
        pickerShow = show; pickerFrom = fromRow; episodes = null; episodesError = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { if (show.source == "tvdb") Tvdb.episodes(show.tvdbId ?: 0) else Tmdb.episodes(show.tmdbId) }
            }
            r.onSuccess { eps ->
                episodes = eps
                val best = EpisodeMatch.match(eps, f.tv).firstOrNull()?.episode
                season = show.episode?.season ?: best?.season ?: f.tv?.season ?: eps.firstOrNull { (it.season ?: 0) >= 1 }?.season ?: eps.firstOrNull()?.season
            }.onFailure { episodesError = it.message }
        }
    }

    /** TMDB doesn't have it: find the same show on TheTVDB and open its episodes. */
    fun tryTvdb(show: Movie, fromRow: String) {
        episodesError = null; episodes = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Tvdb.searchShows(show.title, ceiling).maxByOrNull { EpisodeMatch.similarity(show.title, it.title) } }
            }
            r.onSuccess { s ->
                if (s == null || EpisodeMatch.similarity(show.title, s.title) < 0.8) { episodesError = "TheTVDB doesn’t have “${show.title}” either"; episodes = emptyList() }
                else openPicker(s, fromRow)
            }.onFailure { episodesError = "TheTVDB: ${it.message}"; episodes = emptyList() }
        }
    }

    fun pick(show: Movie, e: Episode?) {
        chosen = show.copy(episode = e, overview = null, imdbId = details[show.key]?.imdbId ?: show.imdbId, tooNew = false)
        expanded = null; pickerShow = null
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { m.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
            Column(Modifier.weight(1f)) {
                Text("Movie or show & release", color = C.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(f.name, color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel(if (chosen?.isEpisode == true) "Episode" else "Movie or show", C.gold)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.panel).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp, 69.dp).clip(RoundedCornerShape(5.dp)).background(C.panel2)) {
                    (chosen?.poster ?: chosen?.episode?.still)?.let { AsyncImage(it, null, Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f)) {
                    Text(chosen?.heading ?: "Nothing chosen", color = C.text, fontWeight = FontWeight.SemiBold)
                    chosen?.episode?.let { Text(it.label + (it.airDate?.let { d -> " · aired $d" } ?: ""), color = C.gold, fontSize = 13.sp) }
                    Text(when {
                        chosen == null -> "Pick one below, or leave unmatched"
                        chosen?.type == "tv" && chosen?.episode == null -> "Whole show — tap it below to pick the episode"
                        else -> "Tap a result below to change it"
                    }, color = C.muted, fontSize = 12.sp)
                    if (chosen?.source == "tvdb") Text("From TheTVDB", color = C.muted, fontSize = 11.sp)
                }
                if (chosen != null) Text("Clear", color = C.muted, modifier = Modifier.clickable { chosen = null }.padding(6.dp))
            }
            f.tv?.let { h ->
                val bits = listOfNotNull(h.show, listOfNotNull(h.season?.let { "Season $it" }, h.episode?.let { "Episode $it" }).joinToString(" ").ifEmpty { null },
                    h.episodeTitle?.let { "“$it”" }, h.airDate?.let { "aired $it" })
                Text((if (h.fromDash) "File name may be Show – Episode: " else "File name says: ") + bits.joinToString(" · "),
                    color = C.muted, fontSize = 12.sp)
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text(if (type == "movie") "Title, “Title 1961”, or IMDb ID / link" else "Show name, or IMDb ID / link") },
                colors = fieldColors(), shape = RoundedCornerShape(12.dp),
                trailingIcon = { IconButton(onClick = { runSearch() }) { Icon(Icons.Filled.Search, "Search", tint = C.gold) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("Movies", type == "movie", {
                    if (type != "movie" && f.tv != null && query == f.tv.show) query = f.titleName.replace(Regex("\\s*\\(.*?\\)"), "")
                    type = "movie"; runSearch() })
                Chip("TV", type == "tv", {
                    if (type == "movie" && !f.tv?.show.isNullOrBlank()) query = f.tv!!.show!!
                    type = "tv"; runSearch() })
                if (Tvdb.available) Chip("TheTVDB", type == "tvdb", {
                    if (type == "movie" && !f.tv?.show.isNullOrBlank()) query = f.tv!!.show!!
                    type = "tvdb"; runSearch() })
                if (searching) CircularProgressIndicator(Modifier.size(20.dp), color = C.gold, strokeWidth = 2.dp)
            }
            ceiling?.let { Text("This is a $it release — anything after $it is marked too new.", color = C.muted, fontSize = 12.sp) }
            searchNote?.let { Text(it, color = C.gold, fontSize = 12.sp) }
            searchError?.let { Text(it, color = C.warn, fontSize = 13.sp) }
            if (results === f.suggestions && results.isNotEmpty()) SectionLabel("Suggested by the catalog build")
            results.forEachIndexed { idx, mv ->
                val rowKey = "$idx:${mv.key}:${mv.episode?.code}"
                val open = expanded == rowKey
                val isChosen = chosen?.key == mv.key && chosen?.episode?.code == mv.episode?.code
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (isChosen) Color(0xFF1A2A1F) else C.panel)
                    .border(1.dp, if (isChosen) C.good else C.line, RoundedCornerShape(12.dp))
                    .clickable { expanded = if (open) null else rowKey; if (!open) loadDetails(mv) }.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.alpha(if (mv.tooNew) 0.65f else 1f)) {
                        Box(Modifier.size(if (open) 70.dp else 46.dp, if (open) 105.dp else 69.dp).clip(RoundedCornerShape(5.dp)).background(C.panel2)) {
                            mv.poster?.let { AsyncImage(it, null, Modifier.fillMaxSize()) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(mv.title, color = C.text, fontWeight = FontWeight.SemiBold)
                            mv.episode?.let { e -> Text(e.label, color = C.gold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                            Text(listOfNotNull(mv.year, if (mv.type == "tv") "TV" else "Movie", mv.episode?.airDate?.let { "aired $it" },
                                if (mv.source == "tvdb") "TheTVDB" else null).joinToString(" · "), color = C.muted, fontSize = 12.sp)
                            mv.episode?.basis?.takeIf { it.isNotEmpty() }?.let { b ->
                                Text("Matched by " + b.joinToString(", ") { it.replace('_', ' ') }, color = C.muted, fontSize = 11.sp)
                            }
                            if (mv.tooNew) Text("Too new for a ${ceiling ?: ""} release", color = C.bad, fontSize = 12.sp)
                            if (open && !mv.overview.isNullOrBlank()) Text(mv.overview, color = C.muted, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (open) {
                        val det = details[mv.key]
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(if (mv.source == "tvdb") "TheTVDB ↗" else "TMDB ↗", color = C.gold, modifier = Modifier.clickable {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mv.pageUrl))) })
                            (det?.imdbId ?: mv.imdbId)?.let { id -> Text("IMDb ↗", color = C.gold, modifier = Modifier.clickable {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.imdb.com/title/$id/"))) }) }
                            det?.runtime?.let { Text("$it min", color = C.muted) }
                        }
                        if (mv.type == "movie") {
                            if (det == null) Text("Loading release dates…", color = C.muted, fontSize = 12.sp)
                            else {
                                SectionLabel("VHS / DVD releases on TMDB")
                                if (det.physical.isEmpty()) Text("None listed", color = C.muted, fontSize = 12.sp)
                                det.physical.take(8).forEach { (where, date) ->
                                    Row { Text(where, color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(date, color = C.text, fontSize = 12.sp) }
                                }
                            }
                            GoldButton("Use this movie", C.good) { pick(mv, null) }
                        } else {
                            mv.episode?.let { e ->
                                e.still?.let { AsyncImage(it, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) }
                                GoldButton("Use this episode", C.good) { pick(mv, e) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { openPicker(mv, rowKey) }, shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).height(46.dp)) {
                                    Text(if (mv.episode != null) "Other episode" else "Pick episode", color = C.gold, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(onClick = { pick(mv, null) }, shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).height(46.dp)) { Text("Whole show", color = C.text) }
                            }
                        }
                    }
                }
                if (pickerFrom == rowKey && pickerShow != null) {
                    EpisodePicker(pickerShow!!, episodes, episodesError, season, f.tv, ::tooNew,
                        onSeason = { season = it }, onPick = { e -> pick(pickerShow!!, e) },
                        onTvdb = if (Tvdb.available && pickerShow!!.source == "tmdb") ({ tryTvdb(pickerShow!!, rowKey) }) else null,
                        onClose = { pickerShow = null })
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

@Composable
private fun GoldButton(label: String, color: Color, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color(0xFF0D2416)),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(46.dp)) { Text(label, fontWeight = FontWeight.Bold) }
}

/** A show's episodes by season, with the ones that fit the file name at the top. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodePicker(
    show: Movie, episodes: List<Episode>?, error: String?, season: Int?, hint: TvHint?, tooNew: (String?) -> Boolean,
    onSeason: (Int?) -> Unit, onPick: (Episode) -> Unit, onTvdb: (() -> Unit)?, onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF15121A))
        .border(1.dp, C.gold, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Episodes of ${show.title}", color = C.text, fontWeight = FontWeight.SemiBold)
                Text(if (show.source == "tvdb") "From TheTVDB" else "From TMDB", color = C.muted, fontSize = 11.sp)
            }
            Text("Close", color = C.muted, modifier = Modifier.clickable(onClick = onClose).padding(6.dp))
        }
        error?.let { Text(it, color = C.warn, fontSize = 13.sp) }
        if (episodes == null && error == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = C.gold, strokeWidth = 2.dp)
            Text("Loading episodes…", color = C.muted, fontSize = 13.sp)
        }
        if (episodes != null) {
            val hits = remember(episodes, hint) { EpisodeMatch.match(episodes, hint) }
            if (episodes.isEmpty()) Text("No episode list for this show", color = C.muted, fontSize = 13.sp)
            if (hits.isNotEmpty()) {
                SectionLabel(if (hits.first().strong) "Fits the file name" else "Might fit the file name", C.good)
                hits.forEach { h -> EpisodeRow(h.episode, "Matched by " + h.basis.joinToString(", "), tooNew(h.episode.airDate)) { onPick(h.episode) } }
            } else if (hint != null && episodes.isNotEmpty()) {
                Text("Nothing here fits the file name — pick it from the list.", color = C.muted, fontSize = 12.sp)
            }
            if (onTvdb != null && (episodes.isEmpty() || hits.none { it.strong }))
                Text("Not here? Look on TheTVDB ›", color = C.gold, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onTvdb).padding(vertical = 4.dp))
            val seasons = episodes.map { it.season }.distinct()
            if (seasons.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                seasons.forEach { s -> Chip(if (s == null || s == 0) "Specials" else "Season $s", s == season, { onSeason(s) }) }
            }
            episodes.filter { seasons.size <= 1 || it.season == season }.forEach { e -> EpisodeRow(e, null, tooNew(e.airDate)) { onPick(e) } }
        }
    }
}

@Composable
private fun EpisodeRow(e: Episode, why: String?, tooNew: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.panel).clickable(onClick = onClick).padding(8.dp)
        .alpha(if (tooNew) 0.6f else 1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(5.dp)).background(C.panel2)) {
            e.still?.let { AsyncImage(it, null, Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.code.ifEmpty { "Episode" }, color = C.gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(e.name.ifBlank { "Untitled" }, color = C.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            e.airDate?.let { Text("Aired $it" + if (tooNew) " · after this release" else "", color = if (tooNew) C.bad else C.muted, fontSize = 11.sp) }
            why?.let { Text(it, color = C.muted, fontSize = 11.sp) }
        }
    }
}
