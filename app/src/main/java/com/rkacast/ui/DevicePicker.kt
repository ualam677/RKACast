package com.rkacast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rkacast.cast.CastManager
import com.rkacast.cast.DeviceKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePickerSheet(app: AppState) {
    val devices by CastManager.devices.collectAsState()
    val selected by CastManager.selected.collectAsState()
    val scanning by CastManager.scanning.collectAsState()

    DisposableEffect(Unit) {
        CastManager.startDiscovery()
        onDispose { CastManager.stopDiscovery() }
    }

    ModalBottomSheet(onDismissRequest = { app.showDevicePicker = false }, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cast to", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                IconButton(onClick = { CastManager.refreshDlna() }) { Icon(Icons.Default.Refresh, "Rescan") }
            }
            if (!CastManager.castAvailable) {
                Text(
                    "Google Cast is unavailable on this phone (Google Play services missing). DLNA TVs still work.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (devices.isEmpty()) {
                Text(
                    if (scanning) "Searching your Wi-Fi for TVs…"
                    else "No devices found. Make sure your phone and TV are on the same Wi-Fi network.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            LazyColumn {
                items(devices, key = { it.id }) { d ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            CastManager.select(d)
                            app.showDevicePicker = false
                        }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (d.kind == DeviceKind.CHROMECAST) Icons.Default.Cast else Icons.Default.Tv, null)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.titleMedium)
                            Text(d.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected?.id == d.id) Icon(Icons.Default.CheckCircle, "Connected", tint = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider()
                }
            }
            if (selected != null) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        CastManager.disconnect()
                        app.showDevicePicker = false
                    }) { Text("Disconnect from ${selected?.name}") }
                }
            }
        }
    }
}
