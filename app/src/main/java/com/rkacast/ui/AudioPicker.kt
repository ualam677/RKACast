package com.rkacast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rkacast.cast.CastDevice
import com.rkacast.cast.CastManager
import com.rkacast.cast.DeviceKind
import com.rkacast.cast.PlayerState
import com.rkacast.data.LanguageUtils
import com.rkacast.remote.SamsungRemote

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPickerSheet(app: AppState, state: PlayerState, device: CastDevice?, onClose: () -> Unit) {
    val remoteStatus by SamsungRemote.status.collectAsState()
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Audio track", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            when {
                device?.kind == DeviceKind.CHROMECAST && state.audioTracks.size > 1 -> {
                    state.audioTracks.forEachIndexed { i, t ->
                        val lang = LanguageUtils.toIso3(t.language)
                        val label = listOfNotNull(lang?.let { languageName(it) }, t.name?.takeIf { it.isNotBlank() })
                            .distinct().joinToString(" · ").ifBlank { "Track ${i + 1}" }
                        Row(
                            Modifier.fillMaxWidth().clickable { CastManager.selectAudio(t); onClose() }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, Modifier.weight(1f))
                            if (t.id == state.activeAudioId) Icon(Icons.Default.CheckCircle, "Current", tint = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider()
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your choice is kept when you change subtitles or the next episode starts.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                device?.kind == DeviceKind.CHROMECAST -> Text(
                    "The TV reports only one audio track for this video. Some MKV/MP4 files don't expose their tracks to Chromecast. " +
                        "If you know it has more, try \"Play here\" or VLC, where every track can be chosen.",
                )
                else -> {
                    Text(
                        "Your TV plays this through its own player (DLNA), which can't be told to switch audio over the network. " +
                            "Change it on the TV: during playback open the player's options (Tools/⋯ on the Samsung remote) and pick Audio language.",
                    )
                    Spacer(Modifier.height(12.dp))
                    if (remoteStatus == SamsungRemote.Status.CONNECTED) {
                        Button(onClick = {
                            SamsungRemote.key("KEY_TOOLS")
                            app.message("Sent Tools to the TV. Use the Remote tab to pick the audio option.")
                            onClose()
                        }, modifier = Modifier.fillMaxWidth()) { Text("Open the TV's player options") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { app.tab = Tab.REMOTE; onClose() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Go to Remote")
                        }
                    } else {
                        OutlinedButton(onClick = { app.tab = Tab.REMOTE; onClose() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Connect the Samsung remote")
                        }
                    }
                }
            }
        }
    }
}
