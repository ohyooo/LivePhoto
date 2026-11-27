package livephoto.desktop

import kotlin.math.abs

/** Tracks one asynchronous JavaFX seek; all calls are confined to the FX thread. */
internal class DesktopSeekState {
    class Request(val seconds: Double)

    var pending: Request? = null
        private set
    private var confirmed = 0.0

    fun request(seconds: Double): Request = Request(seconds).also { pending = it }

    /** Ignore late clock ticks until the player position agrees with the latest request. */
    fun observe(seconds: Double): Double? {
        if (!seconds.isFinite() || seconds < 0) return null
        if (pending?.let { abs(seconds - it.seconds) > 0.1 } == true) return null
        pending = null
        confirmed = seconds
        return confirmed
    }

    /** A timeout from an older request may not clear a newer one. */
    fun expire(request: Request, seconds: Double): Double? {
        if (pending !== request) return null
        pending = null
        if (seconds.isFinite() && seconds >= 0) confirmed = seconds
        return confirmed
    }

    fun cancel() { pending = null }
}
