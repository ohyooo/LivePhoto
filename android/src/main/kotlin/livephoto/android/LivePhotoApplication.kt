package livephoto.android

import android.app.Application
import livephoto.core.LivePhotoCore

/** Headless initial application: instrumentation can exercise the real app entry. */
class LivePhotoApplication : Application() {
    val core: LivePhotoCore by lazy { AndroidCoreFactory.create() }
}
