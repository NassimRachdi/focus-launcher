package fr.nassim.focuslauncher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Lit la session média active (titre, artiste, pochette, progression) de n'importe quelle appli :
 * lecteur Bluetooth de l'autoradio, lecteur SD/USB, etc.
 * Nécessite l'accès aux notifications (une seule fois, voir README).
 */
class MediaListener : NotificationListenerService(), MediaSessionManager.OnActiveSessionsChangedListener {

    private val ui = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var current: MediaController? = null

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { ui.post { readMeta(metadata); NowPlaying.changed() } }
        override fun onPlaybackStateChanged(state: PlaybackState?) { ui.post { readState(state); NowPlaying.changed() } }
        override fun onSessionDestroyed() { ui.post { attach(null) } }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            val m = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm = m
            val cn = ComponentName(this, MediaListener::class.java)
            m.addOnActiveSessionsChangedListener(this, cn, ui)
            onActiveSessionsChanged(m.getActiveSessions(cn))
        } catch (_: SecurityException) {
            // accès aux notifications retiré
        }
    }

    override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
        val list = controllers ?: return
        val pick = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        ui.post { attach(pick) }
    }

    private fun attach(c: MediaController?) {
        if (c != null && current != null && c.sessionToken == current?.sessionToken) return
        current?.unregisterCallback(cb)
        current = c
        if (c == null) {
            NowPlaying.controller = null
            NowPlaying.playing = false
            NowPlaying.changed()
            return
        }
        c.registerCallback(cb, ui)
        NowPlaying.controller = c
        NowPlaying.source = sourceName(c.packageName)
        readMeta(c.metadata)
        readState(c.playbackState)
        NowPlaying.changed()
    }

    private fun readMeta(m: MediaMetadata?) {
        if (m == null) return
        NowPlaying.title = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        NowPlaying.artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        NowPlaying.album = m.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        NowPlaying.durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION)
        NowPlaying.art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
    }

    private fun readState(s: PlaybackState?) {
        if (s == null) return
        NowPlaying.playing = s.state == PlaybackState.STATE_PLAYING
        NowPlaying.setPosition(s.position, s.lastPositionUpdateTime, s.playbackSpeed)
    }

    private fun sourceName(pkg: String): String = try {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(ai).toString()
    } catch (_: Exception) { pkg }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    override fun onDestroy() {
        try { msm?.removeOnActiveSessionsChangedListener(this) } catch (_: Exception) {}
        current?.unregisterCallback(cb)
        super.onDestroy()
    }

    companion object {
        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(ctx.packageName)
        }
    }
}
