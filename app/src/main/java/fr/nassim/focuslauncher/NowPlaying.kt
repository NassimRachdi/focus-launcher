package fr.nassim.focuslauncher

import android.graphics.Bitmap
import android.media.session.MediaController
import android.os.SystemClock

/** État partagé de la lecture en cours (rempli par MediaListener ou par les broadcasts). Thread UI uniquement. */
object NowPlaying {
    var title: String = ""
    var artist: String = ""
    var album: String = ""
    var art: Bitmap? = null
    var durationMs: Long = 0
    var source: String = ""
    var playing: Boolean = false

    private var posMs: Long = 0
    private var posAt: Long = 0
    private var speed: Float = 1f

    /** Session active (si l'accès aux notifications est accordé) */
    var controller: MediaController? = null

    private val listeners = ArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { for (l in listeners.toTypedArray()) l() }

    fun setPosition(pos: Long, updatedAt: Long, spd: Float) {
        posMs = pos; posAt = updatedAt; speed = if (spd == 0f) 1f else spd
    }

    fun position(): Long {
        if (posMs < 0) return 0
        val p = if (playing && posAt > 0) posMs + ((SystemClock.elapsedRealtime() - posAt) * speed).toLong() else posMs
        return if (durationMs > 0) p.coerceAtMost(durationMs) else p
    }

    fun clear() {
        title = ""; artist = ""; album = ""; art = null; durationMs = 0; playing = false
        posMs = 0; posAt = 0; controller = null; source = ""
    }
}
