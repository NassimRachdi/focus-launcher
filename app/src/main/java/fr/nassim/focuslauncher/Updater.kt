package fr.nassim.focuslauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * Mise à jour sans clé USB : lit version.json publié par GitHub Actions
 * ({"versionCode": 12, "apk": "https://…/focus-launcher.apk"}), télécharge l'APK
 * puis l'installe : en silence par root (`pm install -r`) si l'autoradio le permet,
 * sinon avec l'installeur Android. Tout se fait par le partage de connexion du téléphone.
 */
object Updater {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    var pendingApk: String? = null
        private set
    /** Pour les certificats racines embarqués (voir Tls). */
    @Volatile private var appCtx: Context? = null

    /** Vérifie en arrière-plan. onResult(true) si une version plus récente existe. */
    fun check(ctx: Context, force: Boolean, onResult: (available: Boolean, msg: String) -> Unit) {
        val url = BuildConfig.UPDATE_URL
        if (url.isEmpty()) { onResult(false, "Adresse de mise à jour non configurée"); return }
        appCtx = ctx.applicationContext
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
                msg = "Mise à jour impossible : " + reason(e)
            }
            busy = false
            ui.post { onResult(available, msg) }
        }, "update-check").start()
    }

    /** Télécharge l'APK puis l'installe (root en silence, sinon installeur Android). */
    fun install(ctx: Context, onMsg: (String) -> Unit) {
        val apk = pendingApk ?: return
        if (busy) return
        busy = true
        onMsg("Téléchargement de la mise à jour…")
        val app = ctx.applicationContext
        appCtx = app
        Thread({
            try {
                // Dossier privé de l'appli : lisible par root et par l'installeur (targetSdk 22, file:// autorisé)
                val dir = app.filesDir
                val f = File(dir, "focus-launcher-update.apk")
                download(apk, f)
                dir.setExecutable(true, false)
                f.setReadable(true, false)
                ui.post { onMsg("Installation…") }
                // En cas de succès, Android remplace l'appli et le launcher redémarre tout seul.
                if (!rootInstall(f)) {
                    ui.post {
                        val i = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { app.startActivity(i) } catch (e: Exception) { onMsg("Installeur introuvable : ${e.message}") }
                    }
                }
            } catch (e: Exception) {
                ui.post { onMsg("Échec du téléchargement : " + reason(e)) }
            } finally { busy = false }
        }, "update-dl").start()
    }

    /**
     * Installation silencieuse par root. Le fichier est d'abord copié dans /data/local/tmp,
     * toujours lisible par le gestionnaire de paquets. false si root est absent ou refusé.
     */
    private fun rootInstall(f: File): Boolean {
        val tmp = "/data/local/tmp/focus-launcher.apk"
        val cmd = "cat '${f.absolutePath}' > $tmp && chmod 644 $tmp && pm install -r $tmp; rm -f $tmp"
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = StringBuilder()
            val reader = Thread({ try { out.append(p.inputStream.bufferedReader().readText()) } catch (_: Exception) {} }, "update-su")
            reader.start()
            reader.join(TimeUnit.SECONDS.toMillis(120)) // laisse le temps d'accepter une éventuelle demande root
            if (reader.isAlive) { p.destroy(); return false }
            out.contains("Success")
        } catch (_: Exception) {
            false // pas de binaire su
        }
    }

    /** Explication lisible de l'erreur, pour savoir quoi corriger. */
    private fun reason(e: Exception): String {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        return when {
            e is SSLException && year < 2025 -> "date de l'autoradio incorrecte ($year), règle-la puis réessaie"
            e is UnknownHostException -> "pas d'Internet (partage de connexion activé ?)"
            e is SocketTimeoutException -> "connexion trop lente, réessaie"
            e is SSLException -> "connexion sécurisée refusée (${e.message})"
            else -> "${e.javaClass.simpleName} ${e.message ?: ""}".trim()
        }
    }

    private fun open(u: String): HttpURLConnection {
        var url = u
        // GitHub redirige vers son stockage : on suit les redirections à la main (http→https inclus)
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            if (c is HttpsURLConnection) appCtx?.let { Tls.socketFactory(it) }?.let { c.sslSocketFactory = it }
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
