package com.rkacast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rkacast.data.MediaUtils
import com.rkacast.data.PhonePlayer
import com.rkacast.data.Settings
import com.rkacast.data.TorrentMode
import com.rkacast.server.StreamService
import com.rkacast.torrent.TorrentEngine

@Composable
fun SettingsScreen(app: AppState) {
    val mode by Settings.torrentMode.collectAsState()
    val server by Settings.stremioServer.collectAsState()
    val proxy by Settings.proxyByDefault.collectAsState()
    val subScale by Settings.subtitleScale.collectAsState()
    val subLang by Settings.subtitleLang.collectAsState()
    val torrent by TorrentEngine.status.collectAsState()
    val phonePlayer by Settings.phonePlayer.collectAsState()
    val autoNext by Settings.autoNext.collectAsState()
    val audioLang by Settings.audioLang.collectAsState()
    var audioInput by remember(audioLang) { mutableStateOf(audioLang) }
    val vlcInstalled = remember { com.rkacast.player.VlcLauncher.isInstalled(app.context) }
    var serverInput by remember(server) { mutableStateOf(server) }
    var showLog by remember { mutableStateOf(false) }
    var langInput by remember(subLang) { mutableStateOf(subLang) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Torrent streaming", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().clickable { Settings.setTorrentMode(TorrentMode.BUILT_IN) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(mode == TorrentMode.BUILT_IN, { Settings.setTorrentMode(TorrentMode.BUILT_IN) })
            Column {
                Text("Built-in engine")
                Text("Downloads on this phone and streams to the TV. Keep the phone on Wi-Fi and plugged in.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth().clickable { Settings.setTorrentMode(TorrentMode.STREMIO_SERVER) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(mode == TorrentMode.STREMIO_SERVER, { Settings.setTorrentMode(TorrentMode.STREMIO_SERVER) })
            Column {
                Text("Stremio streaming server")
                Text("Uses a Stremio server running on a PC or NAS on your network (port 11470).", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (mode == TorrentMode.STREMIO_SERVER) {
            OutlinedTextField(
                serverInput, { serverInput = it }, label = { Text("Server URL") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { Settings.setStremioServer(serverInput) }, modifier = Modifier.padding(top = 4.dp)) { Text("Save") }
        }
        torrent?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                "Active: ${it.fileName} · ${(it.progress * 100).toInt()}% · ${it.peers} peers · ${MediaUtils.formatBytes(it.downloadRate)}/s",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { TorrentEngine.stopAll(); StreamService.stop(app.context) }) { Text("Stop torrent & free space") }
        }

        Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
        Text("Subtitles", style = MaterialTheme.typography.titleMedium)
        Text("Text size on TV: ${(subScale * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(value = subScale, onValueChange = { Settings.setSubtitleScale(it) }, valueRange = 0.5f..2.0f, steps = 5)
        OutlinedTextField(
            langInput, { langInput = it.lowercase().take(3) },
            label = { Text("Preferred language code (eng, spa, fre, ger…)") },
            supportingText = { Text("Currently: ${languageName(subLang)}") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { Settings.setSubtitleLang(langInput) }, enabled = langInput.length == 3) { Text("Save language") }

        Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
        Text("Playback", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-play next episode")
                Text("Stremio series: plays the next episode from the same source after a 10 s countdown.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(autoNext, { Settings.setAutoNext(it) })
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            audioInput, { audioInput = it.lowercase().take(3) },
            label = { Text("Preferred audio language (eng, spa, jpn…, blank = default)") },
            supportingText = { Text(if (audioLang.isBlank()) "Using each video's default audio" else "Currently: ${languageName(audioLang)}") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { Settings.setAudioLang(audioInput) }, enabled = audioInput.isEmpty() || audioInput.length == 3) { Text("Save audio language") }
        Spacer(Modifier.height(12.dp))
        Text("\"Play here\" player", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().clickable { Settings.setPhonePlayer(PhonePlayer.BUILT_IN) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(phonePlayer == PhonePlayer.BUILT_IN, { Settings.setPhonePlayer(PhonePlayer.BUILT_IN) })
            Text("Built-in player")
        }
        Row(Modifier.fillMaxWidth().clickable { Settings.setPhonePlayer(PhonePlayer.VLC) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(phonePlayer == PhonePlayer.VLC, { Settings.setPhonePlayer(PhonePlayer.VLC) })
            Column {
                Text("VLC" + if (!vlcInstalled) " (not installed)" else "")
                Text(
                    "Opens streams, torrents and subtitles in VLC. VLC's cast button can send them to Chromecast/Android TV. " +
                        "VLC isn't available for Samsung TVs, so casting to your Samsung TV uses its built-in player.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
        Text("Web videos", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Always relay through phone")
                Text("Fixes sites that block TVs. Uses more battery.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(proxy, { Settings.setProxyByDefault(it) })
        }

        Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
        Text("Troubleshooting", style = MaterialTheme.typography.titleMedium)
        Text("If something doesn't work, open the debug log, copy it and paste it into the chat.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { showLog = true }) { Text("Open debug log") }
        if (showLog) DebugLogDialog(app) { showLog = false }

        Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(16.dp))
        Text("About", style = MaterialTheme.typography.titleMedium)
        Text(
            "RKACast ${com.rkacast.BuildConfig.VERSION_NAME}. Supports Chromecast / Google TV and DLNA TVs. Only stream content you have the rights to watch.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Phone address: ${MediaUtils.localIpAddress() ?: "not on Wi-Fi"}", style = MaterialTheme.typography.bodySmall)
    }
}
