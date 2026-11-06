package fr.nassim.focuslauncher

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
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
 * Observe, sans rien deviner, ce qui se passe quand le son arrive :
 *  - canal audio MTC lu en continu (lecture seule, voir MtcAudio) ;
 *  - journal système capturé par root (logcat), filtré à l'écran, complet dans Download ;
 *  - journal du noyau (dmesg) : le pilote MTC y écrit « --mtc enter <canal> » à chaque bascule ;
 *  - activités de l'appli Bluetooth et paquets MTC installés.
 * Deux boutons d'essai envoient les commandes de bascule documentées (gsm_bt / sys).
 */
object BtDiag {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    val history = ArrayList<String>()          // changements de canal audio
    val log = ArrayList<String>()              // lignes de journal retenues
    val kernel = ArrayList<String>()           // lignes MTC du journal noyau
    @Volatile var channel = "?"
    @Volatile var root = "non testé"
    @Volatile var capturing = false
    @Volatile private var watching = false
    @Volatile private var proc: Process? = null
    private var watchUntil = 0L

    private val KEYS = Regex("(?i)av_channel|microntek|mtc|a2dp|avrcp|bluetooth|setparam|audio_channel|bootcheck|btmusic|music|gsm_bt")
    /** Bruit sans intérêt : interrogation du Bluetooth Android (éteint sur cet autoradio), chaque seconde. */
    private val NOISE = Regex("getState\\(\\)|STATE_OFF")
    private val KERNEL = Regex("(?i)mtc|av_channel|gsm_bt|enter|exit|bt_")

    fun now(): String = fmt.format(Date())

    fun note(s: String) {
        synchronized(history) { history.add("${now()}  $s"); if (history.size > 30) history.removeAt(0) }
    }

    /** Surveille le canal audio pendant 15 min (lecture seule, 2 fois par seconde). */
    fun watch(ctx: Context) {
        watchUntil = System.currentTimeMillis() + 15 * 60_000L
        if (watching) return
        watching = true
        val app = ctx.applicationContext
        Thread({
            try {
                while (System.currentTimeMillis() < watchUntil) {
                    val c = MtcAudio.channel(app)
                    if (c != channel) { channel = c; note(if (c.isEmpty()) "(illisible)" else c) }
                    Thread.sleep(500)
                }
            } catch (_: InterruptedException) {
            } finally { watching = false }
        }, "diag-channel").apply { isDaemon = true }.start()
    }

    /** Lance une commande root et renvoie sa sortie (null si root absent, refusé ou sans réponse). */
    private fun su(cmd: String, timeoutMs: Long): String? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = StringBuilder()
        val t = Thread({ try { out.append(p.inputStream.bufferedReader().readText()) } catch (_: Exception) {} })
        t.start(); t.join(timeoutMs)
        if (t.isAlive) { p.destroy(); null } else out.toString()
    } catch (_: Exception) { null }

    fun checkRoot() {
        root = "test en cours…"
        Thread({
            val o = su("id", 60_000)?.trim()
            root = when {
                o == null -> "absent ou refusé"
                o.contains("uid=0") -> "OK"
                else -> "refusé (${o.ifEmpty { "aucune sortie" }})"
            }
        }, "diag-root").apply { isDaemon = true }.start()
    }

    /** Relit le journal du noyau et garde les lignes du pilote MTC. */
    fun readKernel(done: () -> Unit) {
        Thread({
            val o = su("dmesg", 30_000)
            synchronized(kernel) {
                kernel.clear()
                if (o == null) kernel.add("journal noyau illisible (root ?)")
                else o.lineSequence().filter { KERNEL.containsMatchIn(it) }.toList().takeLast(40).forEach { kernel.add(it) }
                if (kernel.isEmpty()) kernel.add("aucune ligne MTC")
            }
            done()
        }, "diag-dmesg").apply { isDaemon = true }.start()
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
        val mine = Regex("\\(\\s*${android.os.Process.myPid()}\\)")
        synchronized(log) { log.clear(); log.add("${now()}  --- début de la capture ---") }
        Thread({
            val end = System.currentTimeMillis() + 10 * 60_000L // arrêt automatique après 10 min
            try {
                FileWriter(file, false).use { w ->
                    p.inputStream.bufferedReader().useLines { lines ->
                        for (l in lines) {
                            w.write(l); w.write("\n")
                            if (KEYS.containsMatchIn(l) && !NOISE.containsMatchIn(l) && !mine.containsMatchIn(l)) synchronized(log) {
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
        return "Capture démarrée"
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
        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(260, LinearLayout.LayoutParams.MATCH_PARENT))

        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 13f; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { f() }
            col.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        btn("Essai : son Bluetooth") {
            val r = MtcAudio.enterBluetooth(this)
            BtDiag.note("essai av_channel_enter=gsm_bt → code $r")
            toast(if (r == 0) "Commande acceptée : son Bluetooth" else "Code $r (0 = accepté, -1 = permission refusée)")
        }
        btn("Essai : son Android") {
            val r = MtcAudio.enterSystem(this)
            BtDiag.note("essai av_channel_enter=sys → code $r")
            toast(if (r == 0) "Commande acceptée : son Android" else "Code $r (0 = accepté, -1 = permission refusée)")
        }
        btn("Journal noyau (MTC)") { toast("Lecture…"); BtDiag.readKernel { ui.post { render() } } }
        capBtn = btn("Capture du journal") {
            if (BtDiag.capturing) { BtDiag.stopCapture(); toast("Capture arrêtée") } else toast(BtDiag.startCapture())
        }
        btn("Ouvrir l'appli Bluetooth") {
            val i = apps.find(arrayOf("Bluetooth"))
            if (i == null) toast("Appli Bluetooth introuvable") else try { startActivity(i) } catch (e: Exception) { toast("Impossible : ${e.message}") }
        }
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

    private fun staticInfo(): String {
        val sb = StringBuilder()
        val pm = packageManager
        val bt = apps.find(arrayOf("Bluetooth"))?.component
        sb.append("APPLI BLUETOOTH : ").append(bt?.flattenToShortString() ?: "introuvable").append('\n')
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
        sb.append("Focus Launcher v").append(BuildConfig.VERSION_CODE)
            .append("  ·  root : ").append(BtDiag.root)
            .append("  ·  capture : ").append(if (BtDiag.capturing) "EN COURS" else "arrêtée").append('\n')
        sb.append("Canal audio actuel : ").append(BtDiag.channel.ifEmpty { "(illisible)" })
            .append("  ·  permission audio : ").append(if (MtcAudio.hasPermission(this)) "OK" else "ABSENTE").append('\n')
        sb.append("\nHISTORIQUE :\n")
        synchronized(BtDiag.history) { BtDiag.history.forEach { sb.append("  ").append(it).append('\n') } }
        sb.append("\nJOURNAL NOYAU (MTC) :\n")
        synchronized(BtDiag.kernel) {
            if (BtDiag.kernel.isEmpty()) sb.append("  (bouton « Journal noyau »)\n")
            else BtDiag.kernel.forEach { sb.append(it.take(120)).append('\n') }
        }
        sb.append("\nJOURNAL SYSTÈME (les plus récentes en bas) :\n")
        synchronized(BtDiag.log) { BtDiag.log.takeLast(30).forEach { sb.append(it.take(150)).append('\n') } }
        sb.append('\n').append(info)
        return sb.toString()
    }

    private fun render() {
        out.text = report()
        capBtn.text = if (BtDiag.capturing) "Arrêter la capture" else "Capture du journal"
    }

    private fun saveReport() {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val full = StringBuilder(report())
            full.append("\nJOURNAL SYSTÈME COMPLET RETENU :\n")
            synchronized(BtDiag.log) { BtDiag.log.forEach { full.append(it).append('\n') } }
            File(dir, "focus-diag.txt").writeText(full.toString())
            toast("Enregistré : Download/focus-diag.txt")
        } catch (e: Exception) { toast("Échec : ${e.message}") }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
