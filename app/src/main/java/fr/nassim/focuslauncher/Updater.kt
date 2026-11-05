package fr.nassim.focuslauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mise à jour sans clé USB : lit version.json publié par GitHub Actions
 * ({"versionCode": 12, "apk": "https://…/focus-launcher.apk"}), télécharge l'APK
 * puis ouvre l'installeur Android. Tout se fait par le partage de connexion du téléphone.
 */
object Updater {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    var pendingApk: String? = null
        private set

    /** Vérifie en arrière-plan. onResult(true) si une version plus récente existe. */
    fun check(ctx: Context, force: Boolean, onResult: (available: Boolean, msg: String) -> Unit) {
        val url = BuildConfig.UPDATE_URL
        if (url.isEmpty()) { onResult(false, "Adresse de mise à jour non configurée"); return }
        val prefs = ctx.getSharedPreferences("upd", Context.MODE_PRIVATE)
        val last = prefs.getLong("last", 0)
        if (!force && System.currentTimeMillis() - last < 6 * 3600_000L) return
        if (busy) return
        busy = true
        Thread({
            var available = false
            var msg: String
            try {
                val json = JSONObject(get(url))
                prefs.edit().putLong("last", System.currentTimeMillis()).apply()
                val remote = json.getInt("versionCode")
                if (remote > BuildConfig.VERSION_CODE) {
                    pendingApk = json.getString("apk")
                    available = true
                    msg = "Version $remote disponible"
                } else msg = "Déjà à jour (v${BuildConfig.VERSION_CODE})"
            } catch (e: Exception) {
                msg = "Pas de connexion au serveur de mise à jour"
            }
            busy = false
            ui.post { onResult(available, msg) }
        }, "update-check").start()
    }

    /** Télécharge l'APK puis lance l'installeur. */
    fun install(ctx: Context, onMsg: (String) -> Unit) {
        val apk = pendingApk ?: return
        if (busy) return
        busy = true
        onMsg("Téléchargement de la mise à jour…")
        val app = ctx.applicationContext
        Thread({
            try {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val f = File(dir, "focus-launcher-update.apk")
                download(apk, f)
                ui.post {
                    val i = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    app.startActivity(i)
                }
            } catch (e: Exception) {
                ui.post { onMsg("Échec du téléchargement : ${e.message}") }
            } finally { busy = false }
        }, "update-dl").start()
    }

    private fun open(u: String): HttpURLConnection {
        var url = u
        // GitHub redirige vers son stockage : on suit les redirections à la main (http→https inclus)
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000; c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "FocusLauncher")
            val code = c.responseCode
            if (code in 300..399) {
                url = c.getHeaderField("Location") ?: throw IllegalStateException("Redirection vide")
                c.disconnect()
            } else if (code == 200) return c
            else throw IllegalStateException("HTTP $code")
        }
        throw IllegalStateException("Trop de redirections")
    }

    private fun get(u: String): String = open(u).run {
        try { inputStream.bufferedReader().readText() } finally { disconnect() }
    }

    private fun download(u: String, f: File) {
        val c = open(u)
        try {
            c.inputStream.use { inp -> f.outputStream().use { out -> inp.copyTo(out, 16 * 1024) } }
        } finally { c.disconnect() }
    }
}
