package com.rkacast

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.IntentCompat
import com.rkacast.browser.BrowserController
import com.rkacast.cast.MediaResolver
import com.rkacast.ui.AppState
import com.rkacast.ui.MainScreen
import com.rkacast.ui.RKACastTheme
import com.rkacast.ui.handleInput

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<Intent?>(null)
    private val browser = BrowserController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        incoming.value = intent
        setContent {
            RKACastTheme {
                val scope = rememberCoroutineScope()
                val snackbar = remember { SnackbarHostState() }
                val app = remember { AppState(this, scope, snackbar) }
                val pending = incoming.value
                LaunchedEffect(pending) {
                    if (pending != null) {
                        handleIntent(app, pending)
                        incoming.value = null
                    }
                }
                var crash by remember { mutableStateOf(com.rkacast.data.DebugLog.pendingCrash()) }
                MainScreen(app, browser)
                crash?.let { report ->
                    com.rkacast.ui.CrashReportDialog(app, report) {
                        com.rkacast.data.DebugLog.clearCrash()
                        crash = null
                    }
                }
            }
        }
    }

    /** While casting (or connected to the Samsung remote), the phone's volume buttons control the TV. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            val casting = com.rkacast.cast.CastManager.selected.value != null && com.rkacast.cast.CastManager.state.value.media != null
            val remote = com.rkacast.remote.SamsungRemote.status.value == com.rkacast.remote.SamsungRemote.Status.CONNECTED
            if (casting || remote) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    com.rkacast.cast.CastManager.adjustVolume(code == android.view.KeyEvent.KEYCODE_VOLUME_UP)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent
    }

    override fun onDestroy() {
        browser.destroy()
        super.onDestroy()
    }

    private fun handleIntent(app: AppState, intent: Intent) {
        // Used by the CI screenshot job: am start ... --es tab REMOTE
        if (BuildConfig.DEBUG) {
            intent.getStringExtra("tab")?.let { t ->
                runCatching { com.rkacast.ui.Tab.valueOf(t) }.getOrNull()?.let { app.tab = it }
                return
            }
        }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) {
                    app.cast("Preparing video…") { MediaResolver.fromLocal(this, stream) }
                    return
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                // Shared text often contains a title plus the link; pull out the link.
                val link = Regex("""(magnet:\?\S+|https?://\S+)""").find(text)?.value ?: text
                handleInput(app, link)
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                when (data.scheme) {
                    "content", "file" -> app.cast("Preparing video…") { MediaResolver.fromLocal(this, data) }
                    "stremio" -> handleInput(app, data.toString())
                    else -> handleInput(app, data.toString())
                }
            }
        }
    }
}
