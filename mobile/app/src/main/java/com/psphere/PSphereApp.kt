package com.psphere

import android.app.Application
import android.os.Environment
import com.psphere.p2p.PeerSyncManager

class PSphereApp : Application() {

    lateinit var peerSyncManager: PeerSyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val syncDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        peerSyncManager = PeerSyncManager(this, syncDir)
        peerSyncManager.start()
    }

    override fun onTerminate() {
        peerSyncManager.stop()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: PSphereApp
            private set

        val isInitialized: Boolean get() = ::instance.isInitialized
    }
}
