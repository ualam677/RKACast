package com.rkacast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rkacast.remote.SamsungDiscovery
import com.rkacast.remote.SamsungRemote
import com.rkacast.remote.SamsungTv
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun RemoteScreen(app: AppState) {
    val status by SamsungRemote.status.collectAsState()
    val tv by SamsungRemote.tv.collectAsState()
    val message by SamsungRemote.message.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        if (status != SamsungRemote.Status.CONNECTED) {
            ConnectCard(app, status, tv, message)
        } else {
            ConnectedHeader(tv)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            ControlSurface()
        }
        Spacer(Modifier.height(24.dp))
    }
}

// --------------------------------------------------------------------------------- connection

@Composable
private fun ConnectCard(app: AppState, status: SamsungRemote.Status, current: SamsungTv?, message: String?) {
    val scope = rememberCoroutineScope()
    val saved by SamsungRemote.saved.collectAsState()
    var found by remember { mutableStateOf<List<SamsungTv>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var manualIp by rememberSaveable { mutableStateOf("") }

    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch {
            found = SamsungDiscovery.discover(app.context)
            scanning = false
        }
    }
    LaunchedEffect(Unit) { scan() }

    Spacer(Modifier.height(8.dp))
    Text("TV Remote", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Control your Samsung TV: touchpad pointer, scrolling, fast typing and remote buttons. The phone and TV must be on the same Wi-Fi.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    when (status) {
        SamsungRemote.Status.CONNECTING -> StatusCard("Connecting to ${current?.name}…", spinner = true)
        SamsungRemote.Status.WAITING_FOR_APPROVAL -> StatusCard(
            "A prompt should appear on ${current?.name}. Press Allow with your TV remote (first time only).", spinner = true,
        )
        SamsungRemote.Status.ERROR -> message?.let { StatusCard(it, error = true) }
        else -> {}
    }

    val all = (saved + found).distinctBy { it.ip }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Samsung TVs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        IconButton(onClick = { scan() }) { Icon(Icons.Default.Refresh, "Scan") }
    }
    if (all.isEmpty() && !scanning) {
        Text(
            "No TVs found yet. Turn the TV on, or type its IP address below (TV Settings → General → Network → Network Status → IP Settings).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    all.forEach { t ->
        Row(Modifier.fillMaxWidth().clickable { SamsungRemote.connect(t) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tv, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name)
                Text(listOf(t.model, t.ip).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            if (t.mac != null) TextButton(onClick = { SamsungRemote.wake(t) }) { Text("Turn on") }
            if (saved.any { it.ip == t.ip }) IconButton(onClick = { SamsungRemote.forget(t) }) { Icon(Icons.Default.Delete, "Forget") }
        }
        HorizontalDivider()
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            manualIp, { manualIp = it.trim() }, singleLine = true, modifier = Modifier.weight(1f),
            placeholder = { Text("TV IP, e.g. 192.168.1.50") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            val ip = manualIp
            scope.launch {
                val t = SamsungDiscovery.probe(ip) ?: SamsungTv(ip, "Samsung TV ($ip)")
                SamsungRemote.connect(t)
            }
        }, enabled = manualIp.count { it == '.' } == 3) { Text("Connect") }
    }
}

@Composable
private fun StatusCard(text: String, spinner: Boolean = false, error: Boolean = false) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (spinner) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }
            Text(text)
        }
    }
}

@Composable
private fun ConnectedHeader(tv: SamsungTv?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Tv, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(tv?.name ?: "TV", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { SamsungRemote.key("KEY_POWER") }) {
            Icon(Icons.Default.PowerSettingsNew, "Power", tint = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = { SamsungRemote.disconnect() }) { Text("Disconnect") }
    }
}

// --------------------------------------------------------------------------------- controls

@Composable
private fun ControlSurface() {
    var mode by rememberSaveable { mutableStateOf("touchpad") }
    Row {
        FilterChip(mode == "touchpad", { mode = "touchpad" }, { Text("Touchpad") }, Modifier.padding(end = 6.dp))
        FilterChip(mode == "buttons", { mode = "buttons" }, { Text("Buttons") }, Modifier.padding(end = 6.dp))
        FilterChip(mode == "numbers", { mode = "numbers" }, { Text("123") })
    }
    Spacer(Modifier.height(8.dp))
    when (mode) {
        "touchpad" -> TouchpadPanel()
        "buttons" -> ButtonsPanel()
        else -> NumberPad()
    }
    Spacer(Modifier.height(12.dp))
    KeyboardPanel()
    Spacer(Modifier.height(12.dp))
    AppsPanel()
}

@Composable
private fun TouchpadPanel() {
    var sensitivity by rememberSaveable { mutableStateOf(1.6f) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    if (fullscreen) FullscreenTouchpad(sensitivity) { fullscreen = false }
    FilledTonalButton(onClick = { fullscreen = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Icon(Icons.Default.Fullscreen, null); Spacer(Modifier.width(8.dp)); Text("Fullscreen touchpad")
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().height(300.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
    ) {
        Box(
            Modifier.fillMaxSize().tvTouchpad(sensitivity, haptics),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Drag to move the pointer · Tap to click\nTwo-finger drag to scroll · Two-finger tap for right-click\nHold, then drag, to drag things",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        RemoteKey(Icons.AutoMirrored.Filled.ArrowBack, "Back", "KEY_RETURN")
        RemoteKey(Icons.Default.Home, "Home", "KEY_HOME")
        RemoteKey(Icons.Default.KeyboardArrowUp, "Page up", "KEY_CHUP", repeat = true)
        RemoteKey(Icons.Default.KeyboardArrowDown, "Page down", "KEY_CHDOWN", repeat = true)
        RemoteKey(Icons.Default.PlayArrow, "Play/Pause", "KEY_PLAY")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Pointer speed", style = MaterialTheme.typography.bodySmall)
        androidx.compose.material3.Slider(sensitivity, { sensitivity = it }, valueRange = 0.6f..3.5f, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ButtonsPanel() {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    if (fullscreen) FullscreenRemote { fullscreen = false }
    FilledTonalButton(onClick = { fullscreen = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Icon(Icons.Default.Fullscreen, null); Spacer(Modifier.width(8.dp)); Text("Fullscreen remote")
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RemoteKey(Icons.Default.Input, "Source", "KEY_SOURCE")
            RemoteKey(Icons.Default.Menu, "Menu", "KEY_MENU")
            RemoteKey(Icons.Default.Apps, "Tools", "KEY_TOOLS")
            RemoteKey(Icons.AutoMirrored.Filled.VolumeOff, "Mute", "KEY_MUTE")
        }
        Spacer(Modifier.height(12.dp))
        // D-pad
        Box(Modifier.size(230.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            RemoteKey(Icons.Default.KeyboardArrowUp, "Up", "KEY_UP", Modifier.align(Alignment.TopCenter).padding(8.dp), repeat = true, big = true)
            RemoteKey(Icons.Default.KeyboardArrowDown, "Down", "KEY_DOWN", Modifier.align(Alignment.BottomCenter).padding(8.dp), repeat = true, big = true)
            RemoteKey(Icons.Default.KeyboardArrowLeft, "Left", "KEY_LEFT", Modifier.align(Alignment.CenterStart).padding(8.dp), repeat = true, big = true)
            RemoteKey(Icons.Default.KeyboardArrowRight, "Right", "KEY_RIGHT", Modifier.align(Alignment.CenterEnd).padding(8.dp), repeat = true, big = true)
            Box(
                Modifier.align(Alignment.Center).size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                    .clickable { SamsungRemote.key("KEY_ENTER") },
                contentAlignment = Alignment.Center,
            ) { Text("OK", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RemoteKey(Icons.AutoMirrored.Filled.ArrowBack, "Back", "KEY_RETURN")
            RemoteKey(Icons.Default.Home, "Home", "KEY_HOME")
            TextButton(onClick = { SamsungRemote.key("KEY_EXIT") }) { Text("Exit") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Rocker("VOL", "KEY_VOLUP", "KEY_VOLDOWN")
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row {
                    RemoteKey(Icons.Default.FastRewind, "Rewind", "KEY_REWIND")
                    RemoteKey(Icons.Default.PlayArrow, "Play", "KEY_PLAY")
                    RemoteKey(Icons.Default.Pause, "Pause", "KEY_PAUSE")
                }
                Row {
                    RemoteKey(Icons.Default.Stop, "Stop", "KEY_STOP")
                    RemoteKey(Icons.Default.FastForward, "Fast forward", "KEY_FF")
                }
            }
            Rocker("CH", "KEY_CHUP", "KEY_CHDOWN")
        }
    }
}

@Composable
private fun Rocker(label: String, up: String, down: String) {
    Column(
        Modifier.clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RemoteKey(Icons.Default.Add, "$label up", up, repeat = true)
        Text(label, style = MaterialTheme.typography.labelMedium)
        RemoteKey(Icons.Default.Remove, "$label down", down, repeat = true)
    }
}

@Composable
private fun NumberPad() {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("-", "0", "⏎"))
    Column(Modifier.fillMaxWidth()) {
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                r.forEach { n ->
                    FilledTonalButton(
                        onClick = {
                            when (n) {
                                "-" -> SamsungRemote.key("KEY_PLUS100")
                                "⏎" -> SamsungRemote.key("KEY_ENTER")
                                else -> SamsungRemote.key("KEY_$n")
                            }
                        },
                        modifier = Modifier.padding(4.dp).width(96.dp).height(56.dp),
                    ) { Text(n, style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
    }
}

/** A remote key; with [repeat] it keeps sending while held (for arrows/volume). */
@Composable
private fun RemoteKey(
    icon: ImageVector,
    label: String,
    code: String,
    modifier: Modifier = Modifier,
    repeat: Boolean = false,
    big: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    Box(
        modifier.size(if (big) 56.dp else 48.dp).clip(CircleShape).pointerInput(code, repeat) {
            detectTapGestures(onPress = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                SamsungRemote.key(code)
                var job: Job? = null
                if (repeat) {
                    job = scope.launch {
                        delay(400)
                        while (true) { SamsungRemote.key(code); delay(110) }
                    }
                }
                tryAwaitRelease()
                job?.cancel()
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(if (big) 36.dp else 26.dp))
    }
}

@Composable
private fun KeyboardPanel() {
    val keyboardOpen by SamsungRemote.keyboardOpen.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Keyboard, null, tint = if (keyboardOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (keyboardOpen) "TV keyboard is open. Type here." else "Select a text box on the TV and your keyboard opens automatically",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            TvTextInput(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AppsPanel() {
    var url by rememberSaveable { mutableStateOf("") }
    Text("Apps", style = MaterialTheme.typography.titleMedium)
    androidx.compose.foundation.lazy.LazyRow {
        items(SamsungRemote.apps.size) { i ->
            val a = SamsungRemote.apps[i]
            OutlinedButton(onClick = { SamsungRemote.launchApp(a.id) }, modifier = Modifier.padding(end = 6.dp)) { Text(a.name) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            url, { url = it }, singleLine = true, modifier = Modifier.weight(1f),
            placeholder = { Text("Open a website on the TV") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { openUrl(url) }),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = { openUrl(url) }, enabled = url.isNotBlank()) { Text("Open") }
    }
}

private fun openUrl(input: String) {
    val u = input.trim()
    if (u.isEmpty()) return
    SamsungRemote.openInTvBrowser(if (u.startsWith("http")) u else "https://$u")
}
