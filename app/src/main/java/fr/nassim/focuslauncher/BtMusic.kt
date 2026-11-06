package fr.nassim.focuslauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

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

    /** Vrai si le module annonce une lecture en cours, même quand un lecteur Android tient l'affichage. */
    val isPlaying: Boolean get() = playing

    /**
     * Commandes de lecture. Le module Bluetooth ne réagit pas aux touches média Android : le service
     * MTC (android.microntek.mtcser) écoute ces broadcasts, prévus pour le widget du launcher d'origine,
     * et les traduit en commandes vers le téléphone (module de type 6 : AT-MA lecture/pause,
     * AT-MD suivant, AT-ME précédent). Lu dans BlueToothManager.apk ; aucune permission requise.
     * Le service les ignore tant que le téléphone n'est pas connecté en musique.
     */
    private const val ACTION_PLAY_PAUSE = "com.microntek.widget.btplaypause"
    private const val ACTION_NEXT = "com.microntek.widget.next"
    private const val ACTION_PREV = "com.microntek.widget.btprev"

    /** Envoie lecture/pause, suivant ou précédent au téléphone (code = KeyEvent.KEYCODE_MEDIA_*). Thread UI. */
    fun command(ctx: Context, keyCode: Int) {
        val action = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> ACTION_NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> ACTION_PREV
            else -> ACTION_PLAY_PAUSE
        }
        try { ctx.sendBroadcast(Intent(action)) } catch (_: Exception) { return }
        // « Précédent » ramène au début du morceau (ou au morceau d'avant) : la position repart de zéro.
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS && posMs >= 0) {
            posMs = 0L
            posAt = SystemClock.elapsedRealtime()
            publish(takeOver = false)
        }
    }

    // ------------------------------------------------------------ bascule automatique du son

    private const val TAG = "FocusBT"
    private const val BOOTCHECK = "com.microntek.bootcheck"
    private const val BT_APP = "com.microntek.bluetooth"

    /** Regroupe les lignes d'un même changement (MA, MB, MI se suivent) et l'historique du démarrage. */
    private const val AUTO_DELAY_MS = 600L

    /** Durée après le démarrage de l'autoradio pendant laquelle l'historique du journal compte aussi. */
    private const val BOOT_WINDOW_MS = 300_000L

    private var appCtx: Context? = null

    /** Dernière source MTC (radio, lecteur SD, DVD…) qui s'est annoncée depuis notre dernière prise du son. */
    private var otherSource: String? = null

    /**
     * Les applis MTC qui prennent le son l'annoncent par ce broadcast (extra « class » = leur paquet).
     * Tant qu'une autre source s'est annoncée, on ne lui reprend pas le son automatiquement.
     */
    private val sourceRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val cls = i.getStringExtra("class") ?: return
            otherSource = if (cls == BT_APP || cls.startsWith("phonecall")) null else cls
            Log.i(TAG, "source MTC annoncée : $cls")
        }
    }

    private val autoSwitch = Runnable { switchToBluetooth() }

    /**
     * Après un redémarrage, l'autoradio reste sur le son Android : rien ne passe sur l'entrée Bluetooth
     * tant qu'une commande ne l'a pas demandé (l'appli Bluetooth d'origine le fait à son ouverture).
     * Dès que le module annonce une lecture, on envoie donc la même commande que le bouton « Musique BT »,
     * sauf si un lecteur Android joue ou si une autre source MTC s'est annoncée entre-temps.
     */
    private fun switchToBluetooth() {
        val ctx = appCtx ?: return
        if (!playing) return
        if (NowPlaying.controller?.playbackState?.state == PlaybackState.STATE_PLAYING) {
            Log.i(TAG, "pas de bascule auto : un lecteur Android joue"); return
        }
        val other = otherSource
        if (other != null) { Log.i(TAG, "pas de bascule auto : source $other annoncée"); return }
        val r = MtcAudio.enterBluetooth(ctx)
        Log.i(TAG, "bascule auto du son sur Bluetooth : code $r, canal relu « ${MtcAudio.channel(ctx)} »")
    }

    private fun scheduleAutoSwitch() {
        ui.removeCallbacks(autoSwitch)
        ui.postDelayed(autoSwitch, AUTO_DELAY_MS)
    }

    /** À appeler quand l'utilisateur prend lui-même le son Bluetooth (bouton « Musique BT »). Thread UI. */
    fun sourceTaken() { otherSource = null }

    /** Lance le suivi (une seule fois pour toute la vie du processus). Sans effet si root est absent. */
    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        appCtx = app
        try { app.registerReceiver(sourceRx, IntentFilter(BOOTCHECK)) } catch (_: Exception) {}
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
                        // L'historique ne vaut pour la bascule du son que juste après le démarrage de
                        // l'autoradio (le journal est alors celui de ce démarrage, donc récent).
                        val auto = live || t0 < BOOT_WINDOW_MS
                        ui.post { onEvent(ev, live, auto) }
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

    private fun onEvent(ev: BtMusicParser.Event, live: Boolean, auto: Boolean) {
        val now = SystemClock.elapsedRealtime()
        when (ev) {
            is BtMusicParser.Track -> {
                track = ev
                posMs = if (live) 0L else -1L
                posAt = now
                if (!stateSeen) playing = ev.title.isNotEmpty()
                publish(takeOver = true)
                if (auto && playing) scheduleAutoSwitch()
            }
            is BtMusicParser.State -> {
                stateSeen = true
                if (ev.playing == playing) return
                if (posMs >= 0 && playing) posMs = advanced(now)
                posAt = now
                playing = ev.playing
                // Une pause Bluetooth ne reprend pas l'affichage à un lecteur Android
                publish(takeOver = ev.playing)
                if (!ev.playing) ui.removeCallbacks(autoSwitch) else if (auto) scheduleAutoSwitch()
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
