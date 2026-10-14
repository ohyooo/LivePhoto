package livephoto.android

import livephoto.core.DefaultLivePhotoCore
import livephoto.core.LivePhotoCore

/** Pure Core entry point. No desktop FFmpeg process or mobile media backend yet. */
object AndroidCoreFactory {
    fun create(): LivePhotoCore = DefaultLivePhotoCore()
}
