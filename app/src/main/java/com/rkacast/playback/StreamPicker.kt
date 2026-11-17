package com.rkacast.playback

import com.rkacast.stremio.AddonStream

object StreamPicker {
    /** Same addon + same bingeGroup (same quality/source) first, then same addon, then anything playable. */
    fun choose(streams: List<AddonStream>, addonName: String?, bingeGroup: String?): AddonStream? {
        val playable = streams.filter { it.stream.url != null || it.stream.infoHash != null }
        return playable.firstOrNull { bingeGroup != null && it.addonName == addonName && it.stream.behaviorHints?.bingeGroup == bingeGroup }
            ?: playable.firstOrNull { it.addonName == addonName }
            ?: playable.firstOrNull()
    }
}
