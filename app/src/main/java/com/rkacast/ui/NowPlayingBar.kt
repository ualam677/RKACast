package com.rkacast.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rkacast.cast.CastManager
import com.rkacast.data.MediaUtils
import com.rkacast.torrent.TorrentEngine

/** Remote control for whatever is playing on the TV. */
@Composable
fun NowPlayingBar(app: AppState) {
    val state by CastManager.state.collectAsState()
    val device by CastManager.selected.collectAsState()
    val torrent by TorrentEngine.status.collectAsState()
    val media = state.media
    if (device == null || (media == null && state.error == null)) return

    var dragging by remember { mutableStateOf<Float?>(null) }

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(media?.title ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildString {
                append(device?.name ?: "")
                if (state.status.isNotBlank()) append(" · ").append(state.status)
                torrent?.let { if (media?.url?.contains("/torrent/") == true) append(" · ${it.peers} peers · ${MediaUtils.formatBytes(it.downloadRate)}/s") }
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (state.durationMs > 0) {
                val progress = dragging ?: (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                Slider(
                    value = progress,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { CastManager.seekTo((it * state.durationMs).toLong()) }
                        dragging = null
                    },
                )
                Row {
                    Text(MediaUtils.formatTime(state.positionMs), style = MaterialTheme.typography.labelSmall)
                    Text("", Modifier.weight(1f))
                    Text(MediaUtils.formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { CastManager.seekBy(-10_000) }) { Icon(Icons.Default.Replay10, "Back 10s") }
                IconButton(onClick = { CastManager.togglePlayPause() }) {
                    Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause")
                }
                IconButton(onClick = { CastManager.seekBy(30_000) }) { Icon(Icons.Default.Forward30, "Forward 30s") }
                Text("", Modifier.weight(1f))
                IconButton(onClick = { app.showSubtitlePicker = true }) {
                    Icon(
                        Icons.Default.ClosedCaption, "Subtitles",
                        tint = if (media?.subtitleUrl != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { CastManager.stopMedia() }) { Icon(Icons.Default.Stop, "Stop") }
            }
        }
    }
}
