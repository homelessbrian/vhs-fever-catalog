package org.vhsfever.review

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
fun SettingsScreen(m: AppModel, installApk: (File) -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { m.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.text) }
            Text("Settings", color = C.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Card {
                SectionLabel("Save reviews to GitHub", C.gold)
                if (m.token.isNotBlank()) {
                    Text("Signed in as ${m.githubUser}", color = C.text)
                    Text(m.syncStatus, color = C.muted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { m.syncNow() }, colors = ButtonDefaults.buttonColors(containerColor = C.gold, contentColor = Color(0xFF1A1206))) { Text("Sync now") }
                        TextButton(onClick = { m.signOut() }) { Text("Remove token", color = C.muted) }
                    }
                } else {
                    var token by remember { mutableStateOf("") }
                    var err by remember { mutableStateOf<String?>(null) }
                    var busy by remember { mutableStateOf(false) }
                    Text("Paste a GitHub token once. Your reviews are always kept on this phone; the token lets the app back them up to your repo and update itself.",
                        color = C.muted, fontSize = 13.sp)
                    Text("How to make one ↗", color = C.gold, fontSize = 13.sp, modifier = Modifier.clickable {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/personal-access-tokens/new")))
                    }.padding(vertical = 4.dp))
                    Text("Fine-grained token → Repository access: only vhs-fever-catalog → Permissions: Contents “Read and write”, Actions “Read-only”.",
                        color = C.muted, fontSize = 12.sp)
                    OutlinedTextField(token, { token = it }, singleLine = true, label = { Text("GitHub token") },
                        visualTransformation = PasswordVisualTransformation(), colors = fieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    err?.let { Text(it, color = C.bad, fontSize = 13.sp) }
                    Button(enabled = token.isNotBlank() && !busy, onClick = { busy = true; m.saveToken(token) { e -> err = e; busy = false } },
                        colors = ButtonDefaults.buttonColors(containerColor = C.gold, contentColor = Color(0xFF1A1206))) { Text(if (busy) "Checking…" else "Save token") }
                }
            }

            Card {
                SectionLabel("App updates", C.gold)
                Text("This is version ${BuildConfig.VERSION_NAME}", color = C.text)
                m.updateStatus?.let { Text(it, color = C.muted, fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (m.pendingUpdate != null) Button(onClick = { m.downloadUpdate(installApk) },
                        colors = ButtonDefaults.buttonColors(containerColor = C.good, contentColor = Color(0xFF0D2416))) { Text("Install update") }
                    TextButton(onClick = { m.checkForUpdate() }) { Text("Check for update", color = C.gold) }
                }
            }

            Card {
                SectionLabel("Movie list")
                m.listInfo?.let { Text(it, color = C.text, fontSize = 14.sp) }
                val movies = m.files.map { it.titleId }.distinct().size
                Text("$movies movies · ${m.files.size} files · ${m.audio.size} sound checks", color = C.muted, fontSize = 13.sp)
                Text("Only full-length VHS movies are listed. The bot adds more as it reads new Archive pages.", color = C.muted, fontSize = 12.sp)
                TextButton(onClick = { m.reloadCatalog() }) { Text("Reload the list from GitHub", color = C.gold) }
            }
        }
    }
}
