package com.rkacast.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rkacast.browser.BrowserController
import com.rkacast.cast.CastManager
import com.rkacast.data.MediaUtils
import com.rkacast.torrent.TorrentEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(app: AppState, browser: BrowserController) {
    val selected by CastManager.selected.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RKACast") },
                actions = {
                    IconButton(onClick = { app.showDevicePicker = true }) {
                        Icon(
                            if (selected != null) Icons.Default.CastConnected else Icons.Default.Cast,
                            contentDescription = "Cast to a device",
                            tint = if (selected != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column {
                BusyBar(app)
                NowPlayingBar(app)
                NavigationBar {
                    NavigationBarItem(app.tab == Tab.HOME, { app.tab = Tab.HOME }, { Icon(Icons.Default.Home, null) }, label = { Text("Cast") })
                    NavigationBarItem(app.tab == Tab.BROWSER, { app.tab = Tab.BROWSER }, { Icon(Icons.Default.Language, null) }, label = { Text("Browser") })
                    NavigationBarItem(app.tab == Tab.STREMIO, { app.tab = Tab.STREMIO }, { Icon(Icons.Default.Movie, null) }, label = { Text("Stremio") })
                    NavigationBarItem(app.tab == Tab.REMOTE, { app.tab = Tab.REMOTE }, { Icon(Icons.Default.SettingsRemote, null) }, label = { Text("Remote") })
                    NavigationBarItem(app.tab == Tab.SETTINGS, { app.tab = Tab.SETTINGS }, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                }
            }
        },
        snackbarHost = { SnackbarHost(app.snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (app.tab) {
                Tab.HOME -> HomeScreen(app)
                Tab.BROWSER -> BrowserScreen(app, browser)
                Tab.STREMIO -> StremioTab(app)
                Tab.REMOTE -> RemoteScreen(app)
                Tab.SETTINGS -> SettingsScreen(app)
            }
        }
    }
    if (app.showDevicePicker) DevicePickerSheet(app)
    if (app.showSubtitlePicker) SubtitlePickerSheet(app)
}

@Composable
private fun BusyBar(app: AppState) {
    val label = app.busy ?: return
    val torrent by TorrentEngine.status.collectAsState()
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                torrent?.let {
                    Text(
                        "${it.name} · ${it.peers} peers · ${MediaUtils.formatBytes(it.downloadRate)}/s",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = { app.cancelBusy() }) { Text("Cancel") }
        }
    }
}
