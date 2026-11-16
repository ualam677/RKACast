package com.rkacast

import android.app.Application
import com.rkacast.cast.CastManager
import com.rkacast.data.Settings
import com.rkacast.server.LocalMediaServer
import com.rkacast.stremio.AddonRepository
import com.rkacast.torrent.TorrentEngine

class RKACastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Settings.init(this)
        AddonRepository.init(this)
        LocalMediaServer.init(this)
        TorrentEngine.init(this)
        CastManager.init(this)
    }

    companion object {
        lateinit var instance: RKACastApp
            private set
    }
}
