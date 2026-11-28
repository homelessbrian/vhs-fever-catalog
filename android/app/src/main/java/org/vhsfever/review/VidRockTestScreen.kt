package org.vhsfever.review

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VidRockTestScreen(m: AppModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var imdb by remember { mutableStateOf("tt0096895") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = { m.back() }) { androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
            Text("VidRock source test", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text("Phone-side test. Only VidRock is queried; no VHS Vault or BackWhen services.", color = C.muted, fontSize = 12.sp)
        OutlinedTextField(value = imdb, onValueChange = { imdb = it.trim().take(20); result = null; candidates = emptyList() },
            label = { Text("IMDb ID") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            busy = true; result = null; candidates = emptyList()
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { VidRock.inspect(imdb) } }
                r.onSuccess { result = it.message; candidates = it.mediaUrls }.onFailure { result = "Request failed: ${it.message}" }
                busy = false
            }
        }, enabled = !busy && Regex("tt\\d{5,10}").matches(imdb.trim()),
            colors = ButtonDefaults.buttonColors(containerColor = C.gold, contentColor = Color(0xFF1A1206)), modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Checking VidRock…" else "Check VidRock page", fontWeight = FontWeight.Bold)
        }
        result?.let { Text(it, color = C.text, fontSize = 14.sp) }
        if (candidates.isNotEmpty()) {
            Text("Direct media candidates found in page HTML", color = C.good, fontWeight = FontWeight.Bold)
            candidates.forEach { Text(it, color = C.blue, fontSize = 12.sp) }
        } else if (result != null && !busy) {
            Text("No direct .m3u8 or .mp4 URL was exposed in the page HTML. Sources may load through JavaScript/API calls; this is not a playable stream.", color = C.warn, fontSize = 13.sp)
        }
        Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://vidrock.to/movie/${imdb.trim()}"))) },
            colors = ButtonDefaults.buttonColors(containerColor = C.panel2, contentColor = C.text), modifier = Modifier.fillMaxWidth()) {
            Text("Open official VidRock embed page")
        }
    }
}