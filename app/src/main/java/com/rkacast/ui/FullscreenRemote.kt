package com.rkacast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rkacast.remote.SamsungRemote
import kotlin.math.abs

/**
 * Swipe pad for D-pad navigation: swipe = arrow (keep swiping to move several steps),
 * swipe then hold = keeps moving in that direction, tap = OK.
 */
private fun Modifier.swipePad(haptics: HapticFeedback, onFlash: (String) -> Unit): Modifier = pointerInput(Unit) {
    val step = 70f
    awaitEachGesture {
        val down = awaitFirstDown()
        val startTime = System.currentTimeMillis()
        var origin = down.position
        var lastDir: String? = null
        var lastMoveTime = System.currentTimeMillis()
        var swiped = false
        while (true) {
            val wait = if (lastDir == null) Long.MAX_VALUE else 120L
            val event = withTimeoutOrNull(wait) { awaitPointerEvent() }
            if (event == null) {
                // Finger held still after a swipe: auto-repeat once the hold passes 400 ms.
                if (lastDir != null && System.currentTimeMillis() - lastMoveTime > 400) {
                    SamsungRemote.key(lastDir!!)
                }
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
            if (!change.pressed) break
            val dx = change.position.x - origin.x
            val dy = change.position.y - origin.y
            if (abs(dx) > step || abs(dy) > step) {
                val dir = if (abs(dx) > abs(dy)) (if (dx > 0) "KEY_RIGHT" else "KEY_LEFT") else (if (dy > 0) "KEY_DOWN" else "KEY_UP")
                SamsungRemote.key(dir)
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onFlash(dir.removePrefix("KEY_"))
                origin = change.position
                lastDir = dir
                lastMoveTime = System.currentTimeMillis()
                swiped = true
            }
            change.consume()
        }
        if (!swiped && System.currentTimeMillis() - startTime < 400) {
            SamsungRemote.key("KEY_ENTER")
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onFlash("OK")
        }
    }
}

@Composable
fun FullscreenRemote(onClose: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val keyboardOpen by SamsungRemote.keyboardOpen.collectAsState()
    var showKeyboard by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0C10)).safeDrawingPadding().imePadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Btn(Icons.AutoMirrored.Filled.ArrowBack, "Back") { SamsungRemote.key("KEY_RETURN") }
                    Btn(Icons.Default.Home, "Home") { SamsungRemote.key("KEY_HOME") }
                    Btn(Icons.Default.Input, "Source") { SamsungRemote.key("KEY_SOURCE") }
                    Btn(Icons.Default.Menu, "Menu") { SamsungRemote.key("KEY_MENU") }
                    Btn(Icons.AutoMirrored.Filled.VolumeOff, "Mute") { SamsungRemote.key("KEY_MUTE") }
                    Spacer(Modifier.weight(1f))
                    Btn(Icons.Default.Keyboard, "Keyboard", active = showKeyboard || keyboardOpen) { showKeyboard = !showKeyboard }
                    Btn(Icons.Default.PowerSettingsNew, "Power", tint = MaterialTheme.colorScheme.error) { SamsungRemote.key("KEY_POWER") }
                    Btn(Icons.Default.Close, "Exit fullscreen") { onClose() }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val landscape = maxWidth > maxHeight
                    val pad: @Composable (Modifier) -> Unit = { m ->
                        Box(
                            m.padding(8.dp).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                                .swipePad(haptics) { flash = it },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (flash.isNotEmpty()) flash else "Swipe to move · Tap for OK\nSwipe then hold to keep moving",
                                color = if (flash.isNotEmpty()) MaterialTheme.colorScheme.primary else Color(0x66FFFFFF),
                                style = if (flash.isNotEmpty()) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    if (landscape) {
                        Row(Modifier.fillMaxSize()) {
                            SideControls(Modifier.fillMaxHeight().width(150.dp))
                            pad(Modifier.weight(1f).fillMaxHeight())
                            MediaColumn(Modifier.fillMaxHeight().width(150.dp))
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            pad(Modifier.weight(1f).fillMaxWidth())
                            Row(Modifier.fillMaxWidth().height(170.dp)) {
                                SideControls(Modifier.weight(1f).fillMaxHeight())
                                MediaColumn(Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    }
                }
                if (keyboardOpen || showKeyboard) {
                    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
                        TvTextInput(Modifier.fillMaxWidth()) { showKeyboard = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun SideControls(modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        RockerFs("VOL", "KEY_VOLUP", "KEY_VOLDOWN")
        RockerFs("CH", "KEY_CHUP", "KEY_CHDOWN")
    }
}

@Composable
private fun MediaColumn(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Row {
            Btn(Icons.Default.FastRewind, "Rewind") { SamsungRemote.key("KEY_REWIND") }
            Btn(Icons.Default.PlayArrow, "Play") { SamsungRemote.key("KEY_PLAY") }
            Btn(Icons.Default.FastForward, "Fast forward") { SamsungRemote.key("KEY_FF") }
        }
        Row {
            Btn(Icons.Default.Pause, "Pause") { SamsungRemote.key("KEY_PAUSE") }
            Btn(Icons.Default.Stop, "Stop") { SamsungRemote.key("KEY_STOP") }
        }
    }
}

@Composable
private fun RockerFs(label: String, up: String, down: String) {
    Column(
        Modifier.background(Color(0x1FFFFFFF), RoundedCornerShape(28.dp)).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Btn(Icons.Default.Add, "$label up") { SamsungRemote.key(up) }
        Text(label, color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelMedium)
        Btn(Icons.Default.Remove, "$label down") { SamsungRemote.key(down) }
    }
}

@Composable
private fun Btn(icon: ImageVector, label: String, active: Boolean = false, tint: Color? = null, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }, modifier = Modifier.size(52.dp)) {
        Icon(icon, label, tint = tint ?: if (active) MaterialTheme.colorScheme.primary else Color(0xDDFFFFFF), modifier = Modifier.size(28.dp))
    }
}
