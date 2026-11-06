package fr.nassim.focuslauncher

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic de la musique Bluetooth (appui long sur « Musique BT »).
 *
 * But : observer, sans rien deviner, ce que fait l'appli Bluetooth d'origine quand le son arrive.
 *  - canal audio MTC lu en continu (AudioManager.getParameters("av_channel"), lecture seule) ;
 *  - journal système capturé par root (logcat), filtré à l'écran, complet dans Download ;
 *  - activités de l'appli Bluetooth (pour l'ouvrir directement sur la musique).
 * Le seul bouton qui agit est « Essai », et uniquement avec un canal relevé sur l'autoradio lui-même.
 */
object BtDiag {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    val history = ArrayList<String>()          // changements de canal audio
    val log = ArrayList<String>()              // lignes de journal retenues
    @Volatile var channel = "?"
    @Volatile var root = "non testé"
    @Volatile var capturing = false
    @Volatile private var watching = false
    @Volatile private var proc: Process? = null
    private var watchUntil = 0L

    private val KEYS = Regex("(?i)av_channel|microntek|mtc|a2dp|avrcp|bluetooth|setparam|audio_channel|bootcheck|btmusic|music")

    fun now(): String = fmt.format(Date())

    /** Surveille le canal audio pendant 15 min (lecture seule, 2 fois par seconde). */
    fun watch(ctx: Context) {
        watchUntil = System.currentTimeMillis() + 15 * 60_000L
        if (watching) return
        watching = true
        val am = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Thread({
            try {
                while (System.currentTimeMillis() < watchUntil) {
                    val c = try { am.getParameters("av_channel") ?: "" } catch (e: Exception) { "erreur ${e.message}" }
                    if (c != channel) {
                        channel = c
                        synchronized(history) { history.add("${now()}  ${if (c.isEmpty()) "(vide)" else c}"); if (history.size > 30) history.removeAt(0) }
                    }
                    Thread.sleep(500)
                }
            } catch (_: InterruptedException) {
            } finally { watching = false }
        }, "diag-channel").apply { isDaemon = true }.start()
    }

    fun checkRoot() {
        root = "test en cours…"
        Thread({
            root = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val out = StringBuilder()
                val t = Thread({ try { out.append(p.inputStream.bufferedReader().readText().trim()) } catch (_: Exception) {} })
                t.start(); t.join(60_000)
                if (t.isAlive) { p.destroy(); "pas de réponse (demande root refusée ?)" }
                else if (out.contains("uid=0")) "OK ($out)" else "refusé (${out.ifEmpty { "aucune sortie" }})"
            } catch (e: Exception) { "absent (${e.message})" }
        }, "diag-root").apply { isDaemon = true }.start()
    }

    /** Capture root du journal système : tout dans Download/focus-diag-logcat.txt, extrait filtré à l'écran. */
    fun startCapture(): String {
        if (capturing) return "Capture déjà en cours"
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val file = File(dir, "focus-diag-logcat.txt")
        val p = try { Runtime.getRuntime().exec(arrayOf("su", "-c", "logcat -v time")) }
                catch (e: Exception) { return "Root indisponible : ${e.message}" }
        proc = p
        capturing = true
        synchronized(log) { log.clear(); log.add("${now()}  --- début de la capture ---") }
        Thread({
            val end = System.currentTimeMillis() + 10 * 60_000L // arrêt automatique après 10 min
            try {
                FileWriter(file, false).use { w ->
                    p.inputStream.bufferedReader().useLines { lines ->
                        for (l in lines) {
                            w.write(l); w.write("\n")
                            if (KEYS.containsMatchIn(l) && !l.contains("focuslauncher")) synchronized(log) {
                                log.add(l); if (log.size > 300) log.removeAt(0)
                            }
                            if (!capturing || System.currentTimeMillis() > end) break
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                capturing = false
                try { p.destroy() } catch (_: Exception) {}
                synchronized(log) { log.add("${now()}  --- fin de la capture ---") }
            }
        }, "diag-logcat").apply { isDaemon = true }.start()
        return "Capture démarrée : ouvre la musique Bluetooth"
    }

    fun stopCapture() {
        capturing = false
        try { proc?.destroy() } catch (_: Exception) {}
    }
}

class DiagActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var out: TextView
    private lateinit var capBtn: Button
    private lateinit var apps: AppResolver
    private var resumed = false
    private var info = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Focus_Night)
        super.onCreate(savedInstanceState)
        apps = AppResolver(this).also { it.refresh() }

        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xFF0B0C0F.toInt()); setPadding(8, 8, 8, 8) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val colScroll = ScrollView(this).apply { addView(col) }
        root.addView(colScroll, LinearLayout.LayoutParams(260, LinearLayout.LayoutParams.MATCH_PARENT))

        fun btn(t: String, f: (Button) -> Unit) = Button(this).apply {
            text = t; textSize = 13f; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { f(this) }
            col.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        capBtn = btn("1. Démarrer la capture root") {
            if (BtDiag.capturing) { BtDiag.stopCapture(); toast("Capture arrêtée") } else toast(BtDiag.startCapture())
        }
        btn("2. Ouvrir l'appli Bluetooth") {
            val i = apps.find(arrayOf("Bluetooth"))
            if (i == null) toast("Appli Bluetooth introuvable") else try { startActivity(i) } catch (e: Exception) { toast("Impossible : ${e.message}") }
        }
        btn("3. Mémoriser le canal actuel") {
            val c = BtDiag.channel
            if (c.isEmpty() || c == "?" || c.startsWith("erreur")) toast("Aucun canal lisible")
            else { prefs().edit().putString("bt_channel", c).apply(); toast("Canal mémorisé : $c"); render() }
        }
        btn("4. Essai : basculer sur ce canal") { tryChannel() }
        btn("Enregistrer le rapport") { saveReport() }
        btn("Fermer") { finish() }

        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(0xFFF2F3F5.toInt()); setPadding(10, 0, 0, 0)
        }
        root.addView(ScrollView(this).apply { addView(out) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)

        info = staticInfo()
        BtDiag.watch(this)
        if (BtDiag.root == "non testé") BtDiag.checkRoot()
    }

    override fun onResume() { super.onResume(); resumed = true; BtDiag.watch(this); ui.post(tick) }
    override fun onPause() { resumed = false; ui.removeCallbacks(tick); super.onPause() }

    private val tick = object : Runnable {
        override fun run() { if (!resumed) return; render(); ui.postDelayed(this, 1000) }
    }

    private fun prefs() = getSharedPreferences("ui", Context.MODE_PRIVATE)

    /**
     * Canal mémorisé → AudioManager.setParameters("av_channel_enter=<canal>").
     * Les valeurs MTC courantes ressemblent à « sys », « fm », « gsm_bt »… : on n'envoie que
     * celle relevée sur cet autoradio (étape 3), jamais une valeur inventée.
     */
    private fun tryChannel() {
        val raw = prefs().getString("bt_channel", null) ?: run { toast("Mémorise d'abord un canal (étape 3)"); return }
        val v = raw.substringAfter("=").trim()
        if (v.isEmpty() || v.contains(";") || v.contains(" ")) { toast("Canal inutilisable : $raw"); return }
        try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).setParameters("av_channel_enter=$v")
            synchronized(BtDiag.history) { BtDiag.history.add("${BtDiag.now()}  essai av_channel_enter=$v") }
            toast("Essai envoyé : av_channel_enter=$v")
        } catch (e: Exception) { toast("Refusé : ${e.message}") }
    }

    private fun staticInfo(): String {
        val sb = StringBuilder()
        val pm = packageManager
        sb.append("Focus Launcher v").append(BuildConfig.VERSION_CODE).append('\n')
        val bt = apps.find(arrayOf("Bluetooth"))?.component
        sb.append("\nAPPLI BLUETOOTH : ").append(bt?.flattenToShortString() ?: "introuvable").append('\n')
        if (bt != null) try {
            pm.getPackageInfo(bt.packageName, PackageManager.GET_ACTIVITIES).activities?.forEach {
                sb.append("  ").append(if (it.exported) "[ouvrable] " else "[interne]  ").append(it.name.removePrefix(bt.packageName)).append('\n')
            }
        } catch (e: Exception) { sb.append("  activités illisibles : ").append(e.message).append('\n') }
        sb.append("\nPAQUETS MTC / BLUETOOTH :\n")
        val re = Regex("(?i)microntek|mtc|bluetooth|\\.bt|bt\\.|btmusic|a2dp")
        try {
            pm.getInstalledPackages(0).map { it.packageName }.filter { re.containsMatchIn(it) }.sorted()
                .forEach { sb.append("  ").append(it).append('\n') }
        } catch (e: Exception) { sb.append("  illisible : ").append(e.message).append('\n') }
        return sb.toString()
    }

    private fun report(): String {
        val sb = StringBuilder()
        sb.append("Root : ").append(BtDiag.root).append('\n')
        sb.append("Canal audio actuel : ").append(BtDiag.channel.ifEmpty { "(vide)" }).append('\n')
        sb.append("Canal mémorisé : ").append(prefs().getString("bt_channel", "aucun")).append('\n')
        sb.append("Capture : ").append(if (BtDiag.capturing) "EN COURS" else "arrêtée").append('\n')
        sb.append("\nHISTORIQUE DU CANAL :\n")
        synchronized(BtDiag.history) { BtDiag.history.forEach { sb.append("  ").append(it).append('\n') } }
        sb.append('\n').append(info)
        sb.append("\nJOURNAL (lignes retenues, les plus récentes en bas) :\n")
        synchronized(BtDiag.log) { BtDiag.log.takeLast(40).forEach { sb.append(it.take(150)).append('\n') } }
        return sb.toString()
    }

    private fun render() {
        out.text = report()
        capBtn.text = if (BtDiag.capturing) "1. Arrêter la capture" else "1. Démarrer la capture root"
    }

    private fun saveReport() {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val f = File(dir, "focus-diag.txt")
            val full = StringBuilder(report())
            full.append("\nJOURNAL COMPLET RETENU :\n")
            synchronized(BtDiag.log) { BtDiag.log.forEach { full.append(it).append('\n') } }
            f.writeText(full.toString())
            toast("Enregistré : Download/focus-diag.txt")
        } catch (e: Exception) { toast("Échec : ${e.message}") }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

}
