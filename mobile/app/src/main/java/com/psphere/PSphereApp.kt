package com.psphere

import android.app.Application
import com.psphere.backup.BackupScheduler
import com.psphere.backup.Engine
import com.psphere.backup.Notifications
import com.psphere.backup.Prefs
import com.psphere.backup.ReadyService

class PSphereApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        Engine.start(this)
        BackupScheduler.schedule(this)
        if (Prefs.stayReady(this)) runCatching { ReadyService.start(this) }
    }
}
