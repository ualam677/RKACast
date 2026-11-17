package com.rkacast.ui

import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rkacast.cast.CastManager
import com.rkacast.cast.CastMedia
import com.rkacast.player.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class Tab { HOME, BROWSER, STREMIO, REMOTE, SETTINGS }

/** [source] is a remote URL or a content:// Uri string; it is converted to VTT by the local server. */
data class SubtitlePick(val label: String, val source: String)

/** UI-level state shared by all screens: current tab, busy indicator, device picker, snackbars. */
class AppState(val context: Context, val scope: CoroutineScope, val snackbar: SnackbarHostState) {
    var tab by mutableStateOf(Tab.HOME)
    var showDevicePicker by mutableStateOf(false)
    var busy by mutableStateOf<String?>(null)
    var pendingBrowserUrl by mutableStateOf<String?>(null)
    var pendingStremioAddon by mutableStateOf<String?>(null)

    /** Subtitle chosen for the next thing the user casts from the Cast tab or browser. */
    var nextSubtitle by mutableStateOf<SubtitlePick?>(null)
    /** Subtitles offered by Stremio addons for whatever is currently selected/playing. */
    var subtitleOptions by mutableStateOf<List<com.rkacast.stremio.Subtitle>>(emptyList())
    var showSubtitlePicker by mutableStateOf(false)
    private var job: Job? = null

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    /**
     * Resolves media (which may involve starting a torrent) and sends it to the TV. If no TV is
     * connected yet, the device picker opens and the media plays as soon as one is chosen.
     */
    fun cast(label: String, resolve: suspend () -> CastMedia?) {
        job?.cancel()
        job = scope.launch {
            busy = label
            try {
                val media = resolve() ?: throw IllegalStateException("This stream type can't be cast")
                CastManager.cast(media)
                if (CastManager.selected.value == null) showDevicePicker = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.rkacast.data.DebugLog.e("App", "Cast failed: $label", e)
                snackbar.showSnackbar(e.message ?: "Something went wrong")
            } finally {
                busy = null
            }
        }
    }

    /** Same as [cast] but plays on the phone instead. */
    fun playOnPhone(label: String, resolve: suspend () -> CastMedia?) {
        job?.cancel()
        job = scope.launch {
            busy = label
            try {
                val media = resolve() ?: throw IllegalStateException("This stream type can't be played")
                if (com.rkacast.data.Settings.phonePlayer.value == com.rkacast.data.PhonePlayer.VLC) {
                    if (!com.rkacast.player.VlcLauncher.play(context, media)) {
                        snackbar.showSnackbar("VLC isn't installed. Opening the Play Store.")
                    }
                    return@launch
                }
                context.startActivity(
                    Intent(context, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_URL, media.url)
                        .putExtra(PlayerActivity.EXTRA_TITLE, media.title)
                        .putExtra(PlayerActivity.EXTRA_SUBTITLE, media.subtitleUrl),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Something went wrong")
            } finally {
                busy = null
            }
        }
    }

    fun cancelBusy() {
        job?.cancel()
        busy = null
    }
}
