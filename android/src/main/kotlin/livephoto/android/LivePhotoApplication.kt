package livephoto.android

import android.app.Application
import livephoto.core.LivePhotoCore

class LivePhotoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Media operations only run in the default application process.
        if (getProcessName() == packageName) AndroidPrivateCache.reclaimAfterProcessDeath(cacheDir)
    }

    val core: LivePhotoCore by lazy { AndroidCoreFactory.create() }
}
