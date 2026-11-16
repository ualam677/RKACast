package com.rkacast.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rkacast.browser.BrowserController
import com.rkacast.browser.DetectedMedia
import com.rkacast.cast.MediaResolver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(app: AppState, browser: BrowserController) {
    var address by remember { mutableStateOf(browser.currentUrl) }
    var showDetected by remember { mutableStateOf(false) }

    LaunchedEffect(app.pendingBrowserUrl) {
        app.pendingBrowserUrl?.let {
            address = it
            browser.load(it)
            app.pendingBrowserUrl = null
        }
    }
    LaunchedEffect(browser.currentUrl) { address = browser.currentUrl }

    BackHandler(enabled = browser.canGoBack) { browser.goBack() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { browser.goBack() }, enabled = browser.canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Search or enter a website") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { browser.load(address.trim()) }),
                )
                IconButton(onClick = { browser.webView?.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
            }
            if (browser.progress < 100) LinearProgressIndicator(progress = { browser.progress / 100f }, modifier = Modifier.fillMaxWidth())
            AndroidView(
                factory = { ctx ->
                    browser.obtain(ctx).also { wv ->
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        browser.pending?.let { wv.loadUrl(it); browser.pending = null }
                        if (wv.url == null && browser.currentUrl.isBlank()) wv.loadUrl("https://duckduckgo.com")
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (browser.detected.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = { showDetected = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                icon = {
                    BadgedBox(badge = { Badge { Text("${browser.detected.size}") } }) { Icon(Icons.Default.VideoLibrary, null) }
                },
                text = { Text("Videos found") },
            )
        }
    }

    if (showDetected) {
        ModalBottomSheet(onDismissRequest = { showDetected = false }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("Videos on this page", style = MaterialTheme.typography.titleLarge)
                Text(
                    "If a video won't play on the TV, try \"Via phone\". That sends the site's headers along with the request.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(browser.detected, key = { it.url }) { m -> DetectedRow(app, m) { showDetected = false } }
                }
            }
        }
    }
}

@Composable
private fun DetectedRow(app: AppState, m: DetectedMedia, close: () -> Unit) {
    val ctx = app.context
    val title = m.pageTitle.ifBlank { m.url.substringBefore('?').substringAfterLast('/') }
    val sub = app.nextSubtitle?.source
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(m.url.substringBefore('?').substringAfterLast('/').ifBlank { m.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(m.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row {
            TextButton(onClick = { close(); app.cast("Sending to TV…") { MediaResolver.fromUrl(ctx, m.url, title, subtitleUrl = sub) } }) {
                Icon(Icons.Default.Cast, null); Text(" Cast")
            }
            TextButton(onClick = {
                close(); app.cast("Relaying through phone…") { MediaResolver.fromUrl(ctx, m.url, title, m.headers, forceProxy = true, subtitleUrl = sub) }
            }) { Text("Via phone") }
            TextButton(onClick = { close(); app.playOnPhone("Opening…") { MediaResolver.fromUrl(ctx, m.url, title, subtitleUrl = sub) } }) {
                Text("Play here")
            }
        }
    }
    HorizontalDivider()
}
