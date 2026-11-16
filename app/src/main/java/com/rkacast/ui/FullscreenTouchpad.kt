package com.rkacast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rkacast.remote.SamsungRemote
import kotlinx.coroutines.delay

/**
 * Text box that mirrors what you type into the TV's focused text field. When the TV opens its
 * on-screen keyboard (you clicked a search box / address bar), this focuses itself and pops up
 * the phone keyboard; when the TV closes it, the phone keyboard goes away too.
 */
@Composable
fun TvTextInput(modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val keyboardOpen by SamsungRemote.keyboardOpen.collectAsState()
    var text by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(keyboardOpen) {
        if (keyboardOpen) {
            text = ""; typed = false
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else {
            text = ""; typed = false
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    // Live typing: send the whole text shortly after each change.
    LaunchedEffect(text) {
        if (typed) {
            delay(90)
            SamsungRemote.sendText(text)
        }
    }

    fun submit() {
        SamsungRemote.sendText(text)
        SamsungRemote.submitText()
        text = ""; typed = false
        onDone()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it; typed = true },
        modifier = modifier.focusRequester(focus),
        singleLine = true,
        placeholder = { Text(if (keyboardOpen) "Type, it appears on the TV" else "Type to send to TV") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { submit() }),
        trailingIcon = { IconButton(onClick = { submit() }) { Icon(Icons.AutoMirrored.Filled.Send, "Send and press Enter") } },
    )
}

@Composable
fun FullscreenTouchpad(sensitivity: Float, onClose: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val keyboardOpen by SamsungRemote.keyboardOpen.collectAsState()
    var showKeyboard by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0C10)).safeDrawingPadding().imePadding()) {
            // The whole screen is the touchpad.
            Box(Modifier.fillMaxSize().tvTouchpad(sensitivity, haptics) { dragging = it }, contentAlignment = Alignment.Center) {
                Text(
                    if (dragging) "Dragging…"
                    else "Drag: move · Tap: click · Two fingers: scroll\nTwo-finger tap: right-click · Hold then drag: drag",
                    color = Color(0x66FFFFFF), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                )
            }
            // Top controls
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PadButton(Icons.AutoMirrored.Filled.ArrowBack, "Back") { SamsungRemote.key("KEY_RETURN") }
                PadButton(Icons.Default.Home, "Home") { SamsungRemote.key("KEY_HOME") }
                PadButton(Icons.Default.KeyboardArrowUp, "Page up") { SamsungRemote.key("KEY_CHUP") }
                PadButton(Icons.Default.KeyboardArrowDown, "Page down") { SamsungRemote.key("KEY_CHDOWN") }
                PadButton(Icons.Default.PlayArrow, "Play/Pause") { SamsungRemote.key("KEY_PLAY") }
                Spacer(Modifier.weight(1f))
                PadButton(Icons.Default.Keyboard, "Keyboard", active = showKeyboard || keyboardOpen) { showKeyboard = !showKeyboard }
                PadButton(Icons.Default.Close, "Exit fullscreen") { onClose() }
            }
            // Keyboard bar: shows itself when the TV asks for text, or when toggled.
            if (keyboardOpen || showKeyboard) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface).padding(8.dp),
                ) {
                    TvTextInput(Modifier.fillMaxWidth()) { showKeyboard = false }
                }
            } else {
                // Keep a hidden input alive so the TV keyboard event can still pop the phone keyboard.
                Box(Modifier.align(Alignment.BottomCenter).width(1.dp)) { }
            }
        }
    }
}

@Composable
private fun PadButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, label, tint = if (active) MaterialTheme.colorScheme.primary else Color(0xCCFFFFFF))
    }
}
