package com.rkacast.playback

import android.content.Context
import com.rkacast.cast.CastManager
import com.rkacast.cast.MediaResolver
import com.rkacast.data.DebugLog
import com.rkacast.data.Settings
import com.rkacast.stremio.AddonRepository
import com.rkacast.stremio.AddonStream
import com.rkacast.stremio.StremioClient
import com.rkacast.stremio.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What is being watched, set by the screen that starts playback (before casting). */
data class PlaybackContext(
    val title: String,
    val poster: String? = null,
    // Stremio
    val type: String? = null,
    val metaId: String? = null,
    val videoId: String? = null,
    val metaName: String? = null,
    val videos: List<Video> = emptyList(),
    val addonName: String? = null,
    val bingeGroup: String? = null,
    // Plain URL
    val url: String? = null,
) {
    val key: String? get() = videoId?.let { WatchHistory.stremioKey(it) } ?: url?.let { WatchHistory.urlKey(it) }
}

data class UpNext(val title: String, val secondsLeft: Int)

/**
 * Watches CastManager state independent of the UI: saves resume positions, marks items watched, and
 * auto-plays the next episode of a Stremio series when the current one finishes.
 */
object PlaybackTracker {
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var context: PlaybackContext? = null
    private var contextMediaUrl: String? = null
    private var lastSave = 0L
    private var lastPos = 0L
    private var lastDur = 0L
    private var handledFinishFor: String? = null
    private var countdownJob: Job? = null

    private val _upNext = MutableStateFlow<UpNext?>(null)
    val upNext: StateFlow<UpNext?> = _upNext

    fun init(context: Context) {
        appContext = context.applicationContext
        scope.launch {
            CastManager.state.collect { st ->
                val ctx = this@PlaybackTracker.context ?: return@collect
                val media = st.media ?: return@collect
                if (media.url != contextMediaUrl) return@collect
                if (st.positionMs > 0) lastPos = st.positionMs
                if (st.durationMs > 0) lastDur = st.durationMs
                val now = System.currentTimeMillis()
                if (now - lastSave > 10_000 && lastPos > 0) {
                    lastSave = now
                    save(ctx)
                }
                if (st.finished && handledFinishFor != media.url) {
                    handledFinishFor = media.url
                    DebugLog.i("Playback", "Finished '${ctx.title}'")
                    save(ctx, finished = true)
                    maybeAutoNext(ctx)
                }
            }
        }
    }

    /**
     * Call right before CastManager.cast(media): binds [ctx] (or nothing, for untracked media)
     * to that exact media URL so positions from the previous video are never attributed to it.
     */
    fun start(ctx: PlaybackContext?, mediaUrl: String) {
        cancelUpNext()
        context?.let { if (lastPos > 0) save(it) }
        context = ctx
        contextMediaUrl = mediaUrl
        lastPos = 0
        lastDur = 0
        lastSave = System.currentTimeMillis()
        handledFinishFor = null
    }

    /** Called when the user presses Stop: remember the position, but don't treat it as finished. */
    fun onUserStop() {
        cancelUpNext()
        context?.let { if (lastPos > 0) save(it) }
    }

    fun resumePositionFor(key: String): Long = WatchHistory.get(key)?.takeIf { it.resumable }?.positionMs ?: 0

    private fun save(ctx: PlaybackContext, finished: Boolean = false) {
        val key = ctx.key ?: return
        val watched = finished || (lastDur > 0 && lastPos > lastDur * 0.92)
        WatchHistory.upsert(
            WatchEntry(
                key = key, title = ctx.title, poster = ctx.poster,
                positionMs = if (watched) 0 else lastPos, durationMs = lastDur,
                updated = System.currentTimeMillis(), watched = watched,
                type = ctx.type, metaId = ctx.metaId, videoId = ctx.videoId, metaName = ctx.metaName, url = ctx.url,
            ),
        )
    }

    fun cancelUpNext() {
        countdownJob?.cancel()
        countdownJob = null
        _upNext.value = null
    }

    private fun nextVideo(ctx: PlaybackContext): Video? {
        val list = ctx.videos.filter { (it.season ?: 1) > 0 }
            .sortedWith(compareBy<Video>({ it.season ?: 0 }, { it.episode ?: 0 }))
        val i = list.indexOfFirst { it.id == ctx.videoId }
        return if (i >= 0) list.getOrNull(i + 1) else null
    }

    private fun maybeAutoNext(ctx: PlaybackContext) {
        if (!Settings.autoNext.value || ctx.type != "series") return
        val next = nextVideo(ctx) ?: return
        val label = "${ctx.metaName ?: ""} S${next.season}E${next.episode} · ${next.label}".trim()
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (s in 10 downTo 1) {
                _upNext.value = UpNext(label, s)
                delay(1000)
            }
            _upNext.value = UpNext(label, 0)
            runCatching { playNext(ctx, next, label) }
                .onFailure { DebugLog.e("Playback", "Auto-next failed", it) }
            _upNext.value = null
        }
    }

    /** Skips the countdown. */
    fun playNextNow() {
        val job = countdownJob ?: return
        val ctx = context ?: return
        val next = nextVideo(ctx) ?: return
        job.cancel()
        val label = "${ctx.metaName ?: ""} S${next.season}E${next.episode} · ${next.label}".trim()
        countdownJob = scope.launch {
            _upNext.value = UpNext(label, 0)
            runCatching { playNext(ctx, next, label) }.onFailure { DebugLog.e("Playback", "Next failed", it) }
            _upNext.value = null
        }
    }

    private suspend fun playNext(ctx: PlaybackContext, next: Video, label: String) {
        val type = ctx.type ?: return
        val addons = AddonRepository.addons.value
        val streams = StremioClient.streams(addons, type, next.id)
        val pick = chooseStream(streams, ctx.addonName, ctx.bingeGroup)
            ?: throw IllegalStateException("No streams for next episode")
        DebugLog.i("Playback", "Auto-next: ${next.id} via ${pick.addonName} binge=${pick.stream.behaviorHints?.bingeGroup}")
        val subs = runCatching { StremioClient.subtitles(addons, type, next.id) }.getOrDefault(emptyList())
        val sub = subs.firstOrNull { it.lang == Settings.subtitleLang.value }?.url
        val newCtx = ctx.copy(
            title = label, poster = next.thumbnail ?: ctx.poster, videoId = next.id,
            addonName = pick.addonName, bingeGroup = pick.stream.behaviorHints?.bingeGroup,
        )
        val media = MediaResolver.fromStremio(appContext, pick.stream, label, newCtx.poster, sub)
            ?: throw IllegalStateException("Stream can't be cast")
        start(newCtx, media.url)
        CastManager.cast(media)
    }

    fun chooseStream(streams: List<AddonStream>, addonName: String?, bingeGroup: String?) =
        StreamPicker.choose(streams, addonName, bingeGroup)
}
