// Path: android/app/src/main/java/app/stash/android/Banner.kt
package app.stash.android

import android.os.Handler
import android.os.Looper

/** In-app status messages. The screen that is visible registers a listener; delivery is on the main thread. */
object Events {
    enum class Kind { INFO, PROGRESS, SUCCESS, ERROR }

    class Msg(val tag: String, val kind: Kind, val text: String, val pct: Int = -1, val ttlMs: Long = 4_000)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listener: ((Msg) -> Unit)? = null

    fun hasListener(): Boolean = listener != null

    fun listen(l: (Msg) -> Unit) { listener = l }

    /** Only removes [l], so a screen that stops late does not remove the next screen's listener. */
    fun unlisten(l: (Msg) -> Unit) { if (listener === l) listener = null }

    fun post(m: Msg) {
        main.post { listener?.invoke(m) }
    }
}
