package org.vhsfever.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Header(val id: String, val title: String, val count: Int)

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit, selectedColor: Color = C.gold, selectedText: Color = Color(0xFF1A1206)) {
    Box(
        Modifier.height(38.dp).clip(RoundedCornerShape(19.dp))
            .background(if (selected) selectedColor else C.panel)
            .border(1.dp, if (selected) selectedColor else C.line, RoundedCornerShape(19.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) selectedText else C.text, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

@Composable
fun Tag(label: String, bg: Color = C.panel2, fg: Color = C.muted) {
    Text(label, color = fg, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp))
}

@Composable
fun SectionLabel(text: String, color: Color = C.muted) {
    Text(text.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
}

fun mins(sec: Double?) = sec?.let { "${(it / 60).toInt()} min" } ?: "length ?"

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = C.text, unfocusedTextColor = C.text, focusedBorderColor = C.gold, unfocusedBorderColor = C.line,
    focusedContainerColor = C.panel, unfocusedContainerColor = C.panel, cursorColor = C.gold,
    focusedLabelColor = C.gold, unfocusedLabelColor = C.muted, focusedPlaceholderColor = C.muted, unfocusedPlaceholderColor = C.muted)

@Composable
fun QueueScreen(m: AppModel) {
    val list = remember(m.files, m.decisions, m.audio, m.mode, m.shelfFilter, m.tagFilter, m.soundOnly, m.search, m.englishOnly, m.foreign, m.wholeOnly, m.split, m.robot, m.robotFilter) { m.queue() }
    var confirmAll by remember { mutableStateOf(false) }
    val (good, bad, total) = remember(m.files, m.decisions, m.audio, m.englishOnly, m.foreign, m.wholeOnly, m.split) { m.progress() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("VHS", color = Color(0xFF1A1206), fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(C.gold).padding(horizontal = 7.dp, vertical = 3.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Review", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(if (m.source == "main") "Main catalog" else "New pages" + (m.batchName?.let { " · $it" } ?: ""),
                        color = C.muted, fontSize = 12.sp)
                }
                IconButton(onClick = { m.open(Screen.Settings) }) { Icon(Icons.Filled.Settings, "Settings", tint = C.text) }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.panel).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { Text("Reviewed", color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("${good + bad} of $total files", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.panel2)) {
                    if (total > 0) {
                        if (good > 0) Box(Modifier.weight(good.toFloat()).height(8.dp).background(C.good))
                        if (bad > 0) Box(Modifier.weight(bad.toFloat()).height(8.dp).background(C.bad))
                        if (total - good - bad > 0) Box(Modifier.weight((total - good - bad).toFloat()).height(8.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("● $good good", color = C.good, fontSize = 12.sp)
                    Text("● $bad bad", color = C.bad, fontSize = 12.sp)
                    Text(m.syncStatus, color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedTextField(value = m.search, onValueChange = { m.search = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = C.muted) },
                placeholder = { Text("Search movies, filenames, Archive IDs") },
                colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QueueMode.entries.forEach { q -> Chip(q.label, m.mode == q, { m.mode = q }) }
                Chip("English only", m.englishOnly, { m.changeEnglishOnly(!m.englishOnly) }, C.good)
                Chip("Whole videos only", m.wholeOnly, { m.changeWholeOnly(!m.wholeOnly) }, C.good)
                Chip("Sound problems", m.soundOnly, { m.soundOnly = !m.soundOnly }, C.warn)
            }
            if (m.robot.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val counts = remember(m.robot) { m.robot.values.groupingBy { it.verdict }.eachCount() }
                Chip("Robot: any", m.robotFilter == null, { m.robotFilter = null }, C.gold)
                Chip("Robot says good · ${counts["good"] ?: 0}", m.robotFilter == "good", { m.robotFilter = if (m.robotFilter == "good") null else "good" }, C.good)
                Chip("Robot says bad · ${counts["bad"] ?: 0}", m.robotFilter == "bad", { m.robotFilter = if (m.robotFilter == "bad") null else "bad" }, C.bad, Color(0xFF2A0C08))
                Chip("Robot unsure · ${counts["review"] ?: 0}", m.robotFilter == "review", { m.robotFilter = if (m.robotFilter == "review") null else "review" }, C.warn, Color(0xFF2A1A06))
            }
            if (m.englishOnly && m.foreign.isNotEmpty()) Text("${m.foreign.size} files that look non-English are hidden. Tap “English only” to show them.",
                color = C.muted, fontSize = 12.sp)
            if (m.wholeOnly && m.split.isNotEmpty()) Text("${m.split.size} files that are pieces of a split video are hidden. Tap “Whole videos only” to show them.",
                color = C.muted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Any shelf", m.shelfFilter == null, { m.shelfFilter = null }, C.blue, Color(0xFF0E2238))
                m.shelves.forEach { s -> Chip(s.name, m.shelfFilter == s.id, { m.shelfFilter = if (m.shelfFilter == s.id) null else s.id }, C.blue, Color(0xFF0E2238)) }
            }
            if (m.tags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.tags.forEach { t -> Chip("#$t", m.tagFilter == t, { m.tagFilter = if (m.tagFilter == t) null else t }, C.good) }
            }
        }
        if (m.loadStatus.isNotEmpty()) Text(m.loadStatus, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        val rows = remember(list) {
            val counts = list.groupingBy { it.titleId }.eachCount()
            val out = ArrayList<Any>(list.size + counts.size)
            var last = ""
            for (f in list) {
                if (f.titleId != last) { out.add(Header(f.titleId, f.titleName, counts[f.titleId] ?: 0)); last = f.titleId }
                out.add(f)
            }
            out
        }
        if (!m.firstSyncDone && m.files.isNotEmpty()) {
            // Don't show anything to review until saved reviews are loaded, or reviewed files look new again.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Loading your saved reviews…", color = C.muted, fontSize = 14.sp)
            }
        } else {
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { r -> if (r is FileItem) r.id else "h:" + (r as Header).id }) { r ->
                if (r is FileItem) FileRow(m, r) else {
                    val h = r as Header
                    Text("${h.title} · ${h.count} file${if (h.count == 1) "" else "s"}".uppercase(), color = C.muted, fontSize = 11.sp, letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
                }
            }
            if (list.isEmpty() && m.files.isNotEmpty()) item { Text("Nothing here. Try another filter.", color = C.muted, modifier = Modifier.padding(24.dp)) }
        }
        val agreeable = list.count { m.decisionFor(it)?.status == null && m.robotFor(it)?.verdict.let { v -> v == "good" || v == "bad" } }
        if (m.robotFilter in setOf("good", "bad") && agreeable > 0) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { confirmAll = true }) { Text("Agree with the robot on all $agreeable shown", color = C.gold, fontWeight = FontWeight.SemiBold) }
        }
        if (m.lastBulk.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Agreed on ${m.lastBulk.size} files", color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { m.undoBulk() }) { Text("Undo", color = C.gold) }
        }
        val first = list.firstOrNull { m.decisionFor(it)?.status == null }
        if (first != null) Button(onClick = { m.open(Screen.Review(first.id)) }, shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.gold, contentColor = Color(0xFF1A1206)),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) { Text("Start reviewing · next unreviewed file", fontWeight = FontWeight.Bold) }
        }
    }
    if (confirmAll) {
        val target = list
        val n = target.count { m.decisionFor(it)?.status == null && m.robotFor(it)?.verdict.let { v -> v == "good" || v == "bad" } }
        AlertDialog(onDismissRequest = { confirmAll = false }, containerColor = C.panel,
            title = { Text("Agree on $n files?", color = C.text) },
            text = { Text("Each one gets the robot's call (" + (if (m.robotFilter == "bad") "bad, with its reason" else "good") +
                "). You can undo this right after, or change any file later. These don't count toward the robot's fair test.",
                color = C.muted) },
            confirmButton = { TextButton(onClick = { m.agreeWithRobotAll(target); confirmAll = false }) { Text("Agree on $n", color = C.gold) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel", color = C.muted) } })
    }
}

@Composable
private fun FileRow(m: AppModel, f: FileItem) {
    val d = m.decisionFor(f)
    val sound = m.soundFor(f)
    Row(Modifier.fillMaxWidth().clickable { m.open(Screen.Review(f.id)) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(width = 60.dp, height = 42.dp).clip(RoundedCornerShape(6.dp)).background(C.panel2), contentAlignment = Alignment.Center) {
            Text(mins(f.durationSec), color = C.muted, fontSize = 11.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(f.name, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val rel = m.releaseFor(f)
                if (rel.media != null) Tag("${rel.media} ${rel.year ?: ""}".trim(), if (rel.media == "VHS") C.blueBg else C.panel2, if (rel.media == "VHS") C.blue else C.text)
                soundLabel(sound?.verdict)?.takeIf { sound?.verdict in SOUND_PROBLEMS }?.let { Tag(it, C.warnBg, C.warn) }
                when (d?.status) {
                    "good" -> Tag("Good", Color(0xFF1F3D27), Color(0xFFA8E6B4))
                    "bad" -> Tag("Bad", Color(0xFF3D1F1B), Color(0xFFFFB3AA))
                }
                if (d?.status == null) m.robotFor(f)?.let { g ->
                    when (g.verdict) {
                        "good" -> Tag("Robot: good", C.panel2, C.good)
                        "bad" -> Tag("Robot: " + (g.reason ?: "bad"), C.panel2, C.bad)
                        else -> Tag("Robot: unsure", C.panel2, C.warn)
                    }
                }
                f.partLabel?.let { Tag(it) }
            }
        }
    }
}
