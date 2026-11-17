package com.rkacast.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rkacast.cast.MediaResolver
import com.rkacast.data.MediaUtils
import com.rkacast.data.Settings

/** What the app should do with a piece of text the user pasted or shared. */
fun handleInput(app: AppState, raw: String, onPhone: Boolean = false) {
    val text = raw.trim()
    if (text.isEmpty()) return
    val ctx = app.context
    val sub = app.nextSubtitle?.source
    when {
        MediaUtils.isMagnet(text) || MediaUtils.isInfoHash(text) -> {
            Settings.addHistory(text, "Torrent")
            val resolve = suspend { MediaResolver.fromTorrent(ctx, text, null, subtitleUrl = sub) }
            if (onPhone) app.playOnPhone("Starting torrent…", resolve) else app.cast("Starting torrent…", resolve = resolve)
        }
        text.startsWith("stremio://") || text.endsWith("manifest.json") -> {
            app.pendingStremioAddon = text
            app.tab = Tab.STREMIO
        }
        MediaUtils.looksLikeMedia(text) -> {
            val name = text.substringBefore('?').substringAfterLast('/')
            Settings.addHistory(text, name)
            val resolve = suspend { MediaResolver.fromUrl(ctx, text, subtitleUrl = sub) }
            val pctx = com.rkacast.playback.PlaybackContext(title = name, url = text)
            val resumeAt = com.rkacast.playback.PlaybackTracker.resumePositionFor(pctx.key!!)
            if (resumeAt > 0) app.message("Resuming at ${MediaUtils.formatTime(resumeAt)}")
            if (onPhone) app.playOnPhone("Opening…", resolve) else app.cast("Sending to TV…", pctx, resumeAt, resolve)
        }
        else -> {
            // A web page: open it in the built-in browser so videos on it can be detected.
            val url = if (text.startsWith("http")) text
            else if (' ' !in text && '.' in text) "https://$text"
            else "https://duckduckgo.com/?q=" + Uri.encode(text)
            app.pendingBrowserUrl = url
            app.tab = Tab.BROWSER
        }
    }
}

@Composable
fun HomeScreen(app: AppState) {
    var input by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val history by Settings.history.collectAsState()

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val sub = app.nextSubtitle?.source
            app.cast("Preparing local video…") {
                MediaResolver.fromLocal(app.context, uri).let { m ->
                    if (sub != null) m.copy(subtitleUrl = MediaResolver.subtitleFor(app.context, sub)) else m
                }
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Cast anything to your TV", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Paste a video link, web page, magnet link or Stremio addon URL.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://… or magnet:?xt=…") },
                maxLines = 4,
                trailingIcon = {
                    IconButton(onClick = { clipboard.getText()?.text?.let { input = it } }) {
                        Icon(Icons.Default.ContentPaste, "Paste")
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { handleInput(app, input) }, enabled = input.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Cast, null); Spacer(Modifier.width(8.dp)); Text("Cast", maxLines = 1)
                }
                OutlinedButton(onClick = { handleInput(app, input, onPhone = true) }, enabled = input.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhoneAndroid, null); Spacer(Modifier.width(8.dp)); Text("Play here", maxLines = 1, softWrap = false)
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { app.subtitleOptions = emptyList(); app.showSubtitlePicker = true }) {
                Icon(Icons.Default.ClosedCaption, null)
                Spacer(Modifier.width(8.dp))
                Text(app.nextSubtitle?.let { "Subtitles: ${it.label}" } ?: "Add subtitles (optional)")
            }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth().clickable { pickVideo.launch("video/*") }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FolderOpen, null)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Cast a video from this phone", style = MaterialTheme.typography.titleMedium)
                        Text("Gallery, Downloads, SD card…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (history.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { Settings.clearHistory() }) { Text("Clear") }
                }
            }
        }
        items(history, key = { it.url }) { h ->
            Column(Modifier.fillMaxWidth().clickable { handleInput(app, h.url) }.padding(vertical = 10.dp)) {
                Text(h.title.ifBlank { h.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(h.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider()
        }
    }
}
