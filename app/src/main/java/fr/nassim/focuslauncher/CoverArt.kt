package fr.nassim.focuslauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Pochettes de la musique Bluetooth.
 *
 * Le module Bluetooth de l'autoradio ne transmet aucune image : on cherche la pochette sur Internet
 * (recherche publique de Deezer, sans compte ni clé) à partir du titre et de l'artiste. Il faut donc
 * que l'autoradio ait une connexion (partage de connexion du téléphone). Sans réseau, ou si aucun
 * résultat ne correspond (voir CoverMatch), l'emplacement garde simplement la note de musique.
 *
 * Seuls le titre et l'artiste du morceau sont envoyés. Les images trouvées sont gardées sur le
 * stockage de l'appli (dossier cache) : un morceau déjà vu s'affiche tout de suite, même hors ligne.
 */
object CoverArt {

    private const val TAG = "FocusCover"
    private const val SEARCH = "https://api.deezer.com/search?limit=8&q="

    /** Laisse passer les changements de morceau en rafale (suivant, suivant, suivant) sans rien demander. */
    private const val DEBOUNCE_MS = 400L
    private const val RETRY_AFTER_MS = 30_000L
    private const val MAX_FILES = 300
    private const val MAX_IMAGE_BYTES = 600_000
    private const val MAX_JSON_BYTES = 200_000

    private class Req(val ctx: Context, val key: String, val title: String, val artist: String, val cb: (String, Bitmap?) -> Unit)

    private val ui = Handler(Looper.getMainLooper())
    private val lock = Object()
    private var pending: Req? = null
    private var worker: Thread? = null

    // Thread « cover » uniquement
    private val notFound = HashSet<String>()
    private var failKey = ""
    private var failAt = 0L
    private var lastKey = ""
    private var lastBmp: Bitmap? = null

    /**
     * Demande la pochette d'un morceau. [cb] est appelé sur le thread UI avec la clé du morceau
     * (CoverMatch.key) et l'image, ou null si rien n'a été trouvé. Une nouvelle demande remplace
     * celle qui attendait encore.
     */
    fun request(ctx: Context, title: String, artist: String, cb: (String, Bitmap?) -> Unit) {
        val key = CoverMatch.key(title, artist)
        if (CoverMatch.query(title, artist).isEmpty()) return
        synchronized(lock) {
            pending = Req(ctx.applicationContext, key, title, artist, cb)
            if (worker == null) worker = Thread({ loop() }, "cover").apply { isDaemon = true; start() }
            lock.notifyAll()
        }
    }

    private fun loop() {
        while (true) {
            try {
                synchronized(lock) {
                    while (pending == null) lock.wait()
                }
                Thread.sleep(DEBOUNCE_MS)
            } catch (_: InterruptedException) {
                return
            }
            // Après l'attente : on ne garde que la dernière demande reçue.
            val r = synchronized(lock) { val p = pending; pending = null; p } ?: continue
            val bmp = try { resolve(r) } catch (e: Throwable) {
                Log.i(TAG, "échec pour « ${r.key} » : ${e.javaClass.simpleName} ${e.message ?: ""}")
                failKey = r.key; failAt = SystemClock.elapsedRealtime()
                null
            }
            ui.post { r.cb(r.key, bmp) }
        }
    }

    private fun resolve(r: Req): Bitmap? {
        if (r.key == lastKey) lastBmp?.let { return it }
        val file = File(File(r.ctx.cacheDir, "covers"), hash(r.key) + ".jpg")
        if (file.isFile) {
            decode(file.readBytes())?.let { file.setLastModified(System.currentTimeMillis()); return remember(r.key, it) }
            file.delete() // fichier abîmé : on le redemandera
        }
        if (r.key in notFound) return null
        if (r.key == failKey && SystemClock.elapsedRealtime() - failAt < RETRY_AFTER_MS) return null

        val q = CoverMatch.query(r.title, r.artist)
        val json = JSONObject(String(fetch(r.ctx, SEARCH + URLEncoder.encode(q, "UTF-8"), MAX_JSON_BYTES), Charsets.UTF_8))
        // Deezer répond 200 même en cas d'erreur (quota dépassé…) : ce n'est pas « introuvable ».
        if (json.has("error")) throw IllegalStateException("Deezer : " + json.optJSONObject("error")?.optString("message"))
        val data = json.optJSONArray("data")
        val cands = ArrayList<CoverMatch.Candidate>()
        if (data != null) for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val album = o.optJSONObject("album") ?: continue
            val url = if (album.isNull("cover_medium")) "" else album.optString("cover_medium")
            cands.add(CoverMatch.Candidate(o.optString("title"), o.optJSONObject("artist")?.optString("name") ?: "", url))
        }
        val url = CoverMatch.pick(cands, r.title, r.artist)
        if (url == null || !url.startsWith("https://")) {
            Log.i(TAG, "aucune pochette pour « $q » (${cands.size} résultats)")
            notFound.add(r.key)
            return null
        }
        val bytes = fetch(r.ctx, url, MAX_IMAGE_BYTES)
        val bmp = decode(bytes) ?: throw IllegalStateException("image illisible")
        save(file, bytes)
        Log.i(TAG, "pochette trouvée pour « $q »")
        return remember(r.key, bmp)
    }

    private fun remember(key: String, bmp: Bitmap): Bitmap { lastKey = key; lastBmp = bmp; return bmp }

    private fun decode(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 } // 2 octets/pixel : mémoire limitée
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    }

    private fun fetch(ctx: Context, url: String, max: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            if (c is HttpsURLConnection) Tls.socketFactory(ctx)?.let { c.sslSocketFactory = it }
            c.connectTimeout = 6000; c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "FocusLauncher")
            val code = c.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            val out = ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw IllegalStateException("réponse trop grosse")
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    private fun save(file: File, bytes: ByteArray) {
        try {
            val dir = file.parentFile ?: return
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
            val all = dir.listFiles() ?: return
            if (all.size > MAX_FILES) all.sortedBy { it.lastModified() }.take(all.size - MAX_FILES + 50).forEach { it.delete() }
        } catch (_: Exception) {
            // cache plein ou indisponible : la pochette reste affichée, elle sera redemandée la prochaine fois
        }
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
