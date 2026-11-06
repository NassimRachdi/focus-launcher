package fr.nassim.focuslauncher

import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Titre, artiste, durée et état lecture/pause de la musique Bluetooth.
 *
 * Le module Bluetooth de l'autoradio n'est pas celui d'Android et son service ne publie rien
 * (ni session média, ni broadcast) : la seule trace est le journal système, où il écrit chaque
 * ligne reçue du module (voir BtMusicParser). On suit donc ce journal par root, en lecture seule,
 * avec un seul processus « logcat » filtré sur l'étiquette du service.
 *
 * La position dans le morceau n'est pas envoyée par le module : elle est estimée à partir de
 * l'heure d'arrivée du titre et des pauses. Elle est inconnue (0:00, barre vide) pour un titre
 * retrouvé dans l'historique du journal au démarrage. Pas de pochette en Bluetooth.
 */
object BtMusic {

    private const val CMD = "logcat -v raw -s linlin:V"

    /** Au démarrage, logcat rejoue d'abord son historique : ces lignes-là ne datent pas de maintenant. */
    private const val HISTORY_MS = 3_000L
    private const val RETRY_MIN_MS = 5_000L
    private const val RETRY_MAX_MS = 300_000L

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var started = false

    // État Bluetooth, thread UI uniquement
    private var track: BtMusicParser.Track? = null
    private var playing = false
    private var stateSeen = false
    private var posMs = -1L
    private var posAt = 0L

    /** Lance le suivi (une seule fois pour toute la vie du processus). Sans effet si root est absent. */
    fun start() {
        if (started) return
        started = true
        Thread({ loop() }, "bt-music").apply { isDaemon = true }.start()
    }

    private fun loop() {
        var wait = RETRY_MIN_MS
        while (true) {
            val t0 = SystemClock.elapsedRealtime()
            var p: Process? = null
            try {
                p = Runtime.getRuntime().exec(arrayOf("su", "-c", CMD))
                p.inputStream.bufferedReader().useLines { lines ->
                    for (l in lines) {
                        val ev = BtMusicParser.parse(l) ?: continue
                        val live = SystemClock.elapsedRealtime() - t0 > HISTORY_MS
                        ui.post { onEvent(ev, live) }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { p?.destroy() } catch (_: Exception) {}
            }
            // logcat s'est arrêté (root refusé, processus tué) : nouvel essai, de plus en plus espacé
            wait = if (SystemClock.elapsedRealtime() - t0 > 60_000L) RETRY_MIN_MS else (wait * 4).coerceAtMost(RETRY_MAX_MS)
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return }
        }
    }

    private fun onEvent(ev: BtMusicParser.Event, live: Boolean) {
        val now = SystemClock.elapsedRealtime()
        when (ev) {
            is BtMusicParser.Track -> {
                track = ev
                posMs = if (live) 0L else -1L
                posAt = now
                if (!stateSeen) playing = ev.title.isNotEmpty()
                publish(takeOver = true)
            }
            is BtMusicParser.State -> {
                stateSeen = true
                if (ev.playing == playing) return
                if (posMs >= 0 && playing) posMs = advanced(now)
                posAt = now
                playing = ev.playing
                // Une pause Bluetooth ne reprend pas l'affichage à un lecteur Android
                publish(takeOver = ev.playing)
            }
        }
    }

    private fun advanced(now: Long): Long {
        val p = posMs + (now - posAt)
        val d = track?.durationMs ?: 0L
        return if (d > 0) p.coerceAtMost(d) else p
    }

    /** Recopie l'état Bluetooth dans NowPlaying, sauf si un lecteur Android est en train de jouer. */
    private fun publish(takeOver: Boolean) {
        val np = NowPlaying
        val t = track ?: return
        if (!np.bluetooth) {
            if (!takeOver) return
            if (np.controller?.playbackState?.state == PlaybackState.STATE_PLAYING) return
        }
        np.bluetooth = true
        np.source = "Bluetooth"
        np.title = t.title
        np.artist = t.artist
        np.album = ""
        np.art = null
        np.durationMs = t.durationMs
        np.playing = playing
        np.setPosition(posMs, posAt, 1f)
        np.changed()
    }
}
