package fr.nassim.focuslauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var apps: AppResolver
    private var obd: ObdClient? = null
    private var resumed = false

    private var cTxt = 0
    private var cMute = 0
    private val accent = 0xFFE5322D.toInt()
    private val ok = 0xFF3CCF91.toInt()
    private val warn = 0xFFF2B33D.toInt()

    private val fmtTime = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val fmtDate = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)

    private val pages = intArrayOf(R.id.page_home, R.id.page_music, R.id.page_car, R.id.page_apps)
    private val navs = intArrayOf(R.id.nav_home, R.id.nav_music, R.id.nav_car, R.id.nav_apps)

    // ---------------------------------------------------------------- cycle de vie

    /**
     * La maquette validée est dessinée pour 800×480 avec 1 dp = 1 px (densité 160).
     * L'autoradio annonce une densité plus élevée : l'accueil ne tient plus en hauteur
     * (date et commandes du lecteur rognées). On force donc 160 pour ce launcher uniquement.
     * Aucun effet si l'écran est déjà à 160 ou moins.
     */
    override fun attachBaseContext(base: Context) {
        val cfg = Configuration(base.resources.configuration)
        if (cfg.densityDpi <= DisplayMetrics.DENSITY_MEDIUM) { super.attachBaseContext(base); return }
        cfg.densityDpi = DisplayMetrics.DENSITY_MEDIUM
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
        setTheme(if (prefs.getBoolean("day", false)) R.style.Theme_Focus_Day else R.style.Theme_Focus_Night)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cTxt = themeColor(R.attr.fTxt)
        cMute = themeColor(R.attr.fMute)

        apps = AppResolver(this).also { it.refresh() }
        setupNav()
        setupDock()
        setupMedia()
        setupCar()
        setupAppsGrid()
        setupUpdate()
        showPage(prefs.getInt("page", 0))
        BtMusic.start() // titre / artiste de la musique Bluetooth (suivi du journal par root)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        registerReceiver(timeRx, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        registerReceiver(metaRx, IntentFilter().apply { META_ACTIONS.forEach { addAction(it) } })
        NowPlaying.addListener(mediaChanged)
        updateClock()
        renderMedia()
        ui.post(ticker)
        startObd()
        Updater.check(this, false, ::onUpdateResult)
    }

    override fun onPause() {
        resumed = false
        try { unregisterReceiver(timeRx) } catch (_: Exception) {}
        try { unregisterReceiver(metaRx) } catch (_: Exception) {}
        NowPlaying.removeListener(mediaChanged)
        ui.removeCallbacks(ticker)
        obd?.stop(); obd = null
        super.onPause()
    }

    /** Bouton « maison » physique de l'autoradio : retour à l'accueil. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        showPage(0)
    }

    override fun onBackPressed() { showPage(0) } // un launcher ne se ferme jamais

    // ---------------------------------------------------------------- navigation

    private fun setupNav() {
        navs.forEachIndexed { i, id -> v<TextView>(id).apply { tintTop(this, cMute); press(this); setOnClickListener { showPage(i) } } }
        val day = prefs.getBoolean("day", false)
        v<TextView>(R.id.nav_mode).apply {
            text = if (day) "Jour" else "Nuit"
            setCompoundDrawablesWithIntrinsicBounds(0, if (day) R.drawable.ic_sun else R.drawable.ic_moon, 0, 0)
            tintTop(this, cMute)
            press(this)
            setOnClickListener {
                prefs.edit().putBoolean("day", !day).apply()
                recreate()
            }
        }
    }

    private fun showPage(i: Int) {
        val idx = i.coerceIn(0, pages.size - 1)
        pages.forEachIndexed { k, id -> findViewById<View>(id).visibility = if (k == idx) View.VISIBLE else View.GONE }
        navs.forEachIndexed { k, id ->
            val tv = v<TextView>(id)
            tv.isSelected = k == idx
            tv.setTextColor(if (k == idx) 0xFFFFFFFF.toInt() else cMute)
            tintTop(tv, if (k == idx) 0xFFFFFFFF.toInt() else cMute)
        }
        if (idx == 3) refreshGrid()
        prefs.edit().putInt("page", idx).apply()
    }

    // ---------------------------------------------------------------- raccourcis

    private fun setupDock() {
        v<TextView>(R.id.dock_bt).apply {
            tintTop(this, 0xFFFFFFFF.toInt()); press(this)
            setOnClickListener { openBtMusic() }
            setOnLongClickListener { startActivity(Intent(this@MainActivity, DiagActivity::class.java)); true }
        }
        bindLaunch(R.id.dock_nav, "Navigation", arrayOf("Navigation", "Waze", "Maps"), arrayOf("com.waze", "com.google.android.apps.maps"), cTxt)
        bindLaunch(R.id.dock_phone, "Téléphone", arrayOf("Bluetooth"), emptyArray(), cTxt)
        v<TextView>(R.id.dock_all).apply { tintTop(this, cTxt); press(this); setOnClickListener { showPage(3) } }
    }

    private fun bindLaunch(id: Int, name: String, labels: Array<String>, pkgs: Array<String>, tint: Int) {
        val tv = v<TextView>(id)
        tintTop(tv, tint)
        press(tv)
        tv.setOnClickListener { launch(name, labels, pkgs) }
    }

    private fun launch(name: String, labels: Array<String>, pkgs: Array<String> = emptyArray()) {
        val i = apps.find(labels, pkgs)
        if (i == null) { toast("$name : appli introuvable sur l'écran"); return }
        try { startActivity(i) } catch (e: Exception) { toast("Impossible d'ouvrir $name") }
    }

    /**
     * Musique Bluetooth : bascule directement la source audio de l'autoradio sur l'entrée Bluetooth
     * (voir MtcAudio), sans ouvrir l'appli Bluetooth d'origine. Le bouton « Téléphone » l'ouvre toujours.
     */
    private fun openBtMusic() {
        val r = MtcAudio.enterBluetooth(this)
        if (r == 0 || r == MtcAudio.UNKNOWN) toast("Son : Bluetooth")
        else { toast("Bascule refusée (code $r)"); launch("Musique Bluetooth", BT_APP) } // repli : l'appli d'origine
    }

    // ---------------------------------------------------------------- musique

    private val mediaChanged: () -> Unit = { renderMedia() }

    private fun setupMedia() {
        for (id in intArrayOf(R.id.home_prev, R.id.m_prev)) v<ImageButton>(id).apply { press(this); setOnClickListener { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS) } }
        for (id in intArrayOf(R.id.home_next, R.id.m_next)) v<ImageButton>(id).apply { press(this); setOnClickListener { media(KeyEvent.KEYCODE_MEDIA_NEXT) } }
        for (id in intArrayOf(R.id.home_pp, R.id.m_pp)) v<ImageButton>(id).apply { press(this); setOnClickListener { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) } }
        v<TextView>(R.id.m_bt).apply { press(this); setOnClickListener { openBtMusic() } }
        v<TextView>(R.id.m_files).apply { press(this); setOnClickListener { launch("Musique", arrayOf("Musique", "Music")) } }
        // Appui sur le titre : réglage d'accès aux notifications s'il manque, sinon musique Bluetooth
        val askAccess = View.OnClickListener {
            if (!MediaListener.isEnabled(this)) {
                try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
                catch (_: Exception) { toast("Réglage introuvable : voir le README (commande adb)") }
            } else if (NowPlaying.title.isEmpty()) {
                openBtMusic()
            }
        }
        v<TextView>(R.id.home_title).setOnClickListener(askAccess)
        v<TextView>(R.id.m_title).setOnClickListener(askAccess)
    }

    private fun media(code: Int) {
        val c = NowPlaying.controller
        // Musique Bluetooth : pas de session média et les touches média sont ignorées par le module,
        // on passe par les commandes du service MTC (voir BtMusic.command).
        if (NowPlaying.bluetooth || (c == null && BtMusic.isPlaying)) {
            BtMusic.command(this, code)
            return
        }
        if (c != null) {
            val t = c.transportControls
            when (code) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> t.skipToNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> t.skipToPrevious()
                else -> if (NowPlaying.playing) t.pause() else t.play()
            }
            return
        }
        // Repli : touche média système (comprise par la plupart des lecteurs Android)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    private fun renderMedia() {
        val np = NowPlaying
        val has = np.title.isNotEmpty()
        val title = when {
            has -> np.title
            !MediaListener.isEnabled(this) -> "Activer l'affichage musique"
            else -> "Aucune lecture"
        }
        val artist = when {
            has -> np.artist
            !MediaListener.isEnabled(this) -> "Appuyez ici une seule fois"
            else -> "Appuyez ici pour la musique Bluetooth"
        }
        v<TextView>(R.id.home_title).text = title
        v<TextView>(R.id.m_title).text = title
        v<TextView>(R.id.home_artist).text = artist
        v<TextView>(R.id.m_artist).text = if (has && np.album.isNotEmpty()) "${np.artist} — ${np.album}" else artist
        val src = if (np.source.isNotEmpty()) np.source else "Bluetooth"
        v<TextView>(R.id.home_src).text = "En lecture · $src"
        v<TextView>(R.id.m_src).text = src
        v<TextView>(R.id.st_src).text = src

        val art = np.art
        for ((img, ph) in arrayOf(R.id.home_cover to R.id.home_cover_ph, R.id.m_cover to 0)) {
            val iv = v<ImageView>(img)
            if (art != null) { iv.setImageBitmap(art); iv.visibility = View.VISIBLE }
            else { iv.setImageDrawable(null); iv.visibility = View.GONE }
            if (ph != 0) findViewById<View>(ph).visibility = if (art != null) View.GONE else View.VISIBLE
        }
        val icon = if (np.playing) R.drawable.ic_pause else R.drawable.ic_play
        v<ImageButton>(R.id.home_pp).setImageResource(icon)
        v<ImageButton>(R.id.m_pp).setImageResource(icon)
        v<TextView>(R.id.home_dur).text = mmss(np.durationMs)
        v<TextView>(R.id.m_dur).text = mmss(np.durationMs)
        renderProgress()
    }

    private fun renderProgress() {
        val d = NowPlaying.durationMs
        val p = NowPlaying.position()
        val prog = if (d > 0) (p * 1000 / d).toInt() else 0
        v<ProgressBar>(R.id.home_bar).progress = prog
        v<ProgressBar>(R.id.m_bar).progress = prog
        val s = mmss(p)
        v<TextView>(R.id.home_cur).text = s
        v<TextView>(R.id.m_cur).text = s
    }

    /** 1 Hz : progression uniquement (le reste est rafraîchi à l'événement) */
    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (NowPlaying.playing) renderProgress()
            ui.postDelayed(this, 1000)
        }
    }

    /** Repli sans accès aux notifications : broadcasts « metachanged » envoyés par certains lecteurs. */
    private val metaRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (NowPlaying.controller != null) return
            val t = i.getStringExtra("track") ?: i.getStringExtra("title") ?: return
            NowPlaying.bluetooth = false
            NowPlaying.title = t
            NowPlaying.artist = i.getStringExtra("artist") ?: ""
            NowPlaying.album = i.getStringExtra("album") ?: ""
            NowPlaying.playing = i.getBooleanExtra("playing", true)
            NowPlaying.changed()
        }
    }

    // ---------------------------------------------------------------- voiture (OBD)

    private fun setupCar() {
        v<GaugeView>(R.id.g_temp).setColor(accent)
        v<GaugeView>(R.id.g_rpm).setColor(0xFFFF6B3D.toInt())
        v<GaugeView>(R.id.g_volt).setColor(ok)
        val toggle = View.OnLongClickListener {
            val next = when (obdMode()) { OBD_OFF -> "bt"; "bt" -> "wifi"; else -> OBD_OFF }
            prefs.edit().putString("obd", next).apply()
            toast(when (next) {
                "bt" -> "OBD : mode Bluetooth"
                "wifi" -> "OBD : mode Wi-Fi (192.168.0.10)"
                else -> "OBD : désactivé"
            })
            obd?.stop(); obd = null; startObd()
            true
        }
        findViewById<View>(R.id.c_link_card).setOnLongClickListener(toggle)
        v<TextView>(R.id.st_obd).setOnLongClickListener(toggle)
        v<TextView>(R.id.st_obd).setOnClickListener { showPage(2) }
    }

    /** OBD désactivé par défaut : aucun dongle pour l'instant, et on évite tout accès Bluetooth inutile. */
    private fun obdMode(): String = prefs.getString("obd", OBD_OFF) ?: OBD_OFF

    private fun startObd() {
        if (obd != null) return
        val mode = obdMode()
        if (mode == OBD_OFF) { renderObdOff(); return }
        obd = ObdClient(mode, ::renderObd, ::renderObdStatus).also { it.start() }
    }

    private fun renderObdOff() {
        v<TextView>(R.id.st_obd).setCompoundDrawablesWithIntrinsicBounds(R.drawable.dot_off, 0, 0, 0)
        v<TextView>(R.id.c_link).apply {
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.dot_off, 0, 0, 0)
            text = "Désactivé"
        }
        v<TextView>(R.id.c_link_s).text = "Appui long : activer"
    }

    private fun renderObdStatus(connected: Boolean, text: String) {
        val dot = if (connected) R.drawable.dot_ok else R.drawable.dot_off
        v<TextView>(R.id.st_obd).setCompoundDrawablesWithIntrinsicBounds(dot, 0, 0, 0)
        v<TextView>(R.id.c_link).apply {
            setCompoundDrawablesWithIntrinsicBounds(dot, 0, 0, 0)
            this.text = if (connected) "Connecté" else "Hors ligne"
        }
        v<TextView>(R.id.c_link_s).text = if (connected) "Dongle $text" else "$text · appui long : BT/Wi-Fi"
    }

    private fun renderObd(d: ObdData) {
        d.coolantC?.let {
            v<TextView>(R.id.h_temp).text = "$it °C"
            v<TextView>(R.id.h_temp).setTextColor(if (it >= 105) accent else cTxt)
            v<GaugeView>(R.id.g_temp).set((it - 40) / 80f, "$it")
        }
        d.volts?.let {
            val s = String.format(Locale.FRANCE, "%.1f", it)
            v<TextView>(R.id.h_volt).text = "$s V"
            v<TextView>(R.id.h_volt).setTextColor(if (it < 12.2f) warn else cTxt)
            v<GaugeView>(R.id.g_volt).set((it - 10f) / 5f, s)
            v<TextView>(R.id.c_volt_u).text = when {
                it >= 13.2f -> "V · charge OK"
                it >= 12.2f -> "V · moteur arrêté"
                else -> "V · batterie faible"
            }
        }
        d.rpm?.let {
            v<TextView>(R.id.h_rpm).text = "$it"
            v<GaugeView>(R.id.g_rpm).set(it / 6500f, "$it")
        }
        d.loadPct?.let { v<TextView>(R.id.c_load).text = "$it %" }
        d.dtcCount?.let { n ->
            val hasMil = d.mil == true
            v<TextView>(R.id.c_dtc).apply { text = "$n"; setTextColor(if (n > 0) accent else ok) }
            v<TextView>(R.id.c_dtc_s).text = if (hasMil) "Voyant moteur allumé" else "Dernier scan : à l'instant"
            v<TextView>(R.id.h_diag).apply {
                text = if (n == 0) "Aucun défaut" else "$n défaut" + if (n > 1) "s" else ""
                setTextColor(if (n == 0) ok else accent)
            }
        }
    }

    // ---------------------------------------------------------------- applis

    private var gridItems: List<AppEntry> = emptyList()
    private val gridAdapter = object : BaseAdapter() {
        override fun getCount() = gridItems.size + 1 // + « Mises à jour »
        override fun getItem(p: Int): Any? = if (p < gridItems.size) gridItems[p] else null
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val view = convert ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val badge = view.findViewById<View>(R.id.app_badge)
            val icon = view.findViewById<ImageView>(R.id.app_icon)
            val label = view.findViewById<TextView>(R.id.app_label)
            if (p >= gridItems.size) {
                label.text = "Mises à jour"
                badge.background = badgeBg(0xFF2A6FB0.toInt())
                icon.setImageResource(R.drawable.ic_update)
                return view
            }
            val e = gridItems[p]
            label.text = e.label
            if (e.glyph != 0) {
                badge.background = badgeBg(e.color)
                icon.scaleType = ImageView.ScaleType.CENTER
                icon.setImageResource(e.glyph)
            } else {
                badge.background = null
                icon.scaleType = ImageView.ScaleType.FIT_CENTER
                icon.setImageDrawable(e.icon)
            }
            return view
        }
    }

    private fun badgeBg(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14f) }

    private fun setupAppsGrid() {
        val g = v<GridView>(R.id.page_apps)
        g.adapter = gridAdapter
        g.setOnItemClickListener { _, _, p, _ ->
            if (p >= gridItems.size) { checkUpdateNow(); return@setOnItemClickListener }
            try { startActivity(gridItems[p].intent) } catch (_: Exception) { toast("Impossible d'ouvrir ${gridItems[p].label}") }
        }
        g.setOnItemLongClickListener { _, _, p, _ ->
            if (p < gridItems.size) {
                val pkg = gridItems[p].intent.component?.packageName
                if (pkg != null) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg")))
            }
            true
        }
    }

    private fun refreshGrid() {
        apps.refresh()
        gridItems = apps.all()
        gridAdapter.notifyDataSetChanged()
    }

    // ---------------------------------------------------------------- mise à jour

    private fun setupUpdate() {
        v<TextView>(R.id.st_update).setOnClickListener { Updater.install(this, ::toast) }
    }

    /** Vérification automatique (au plus toutes les 6 h) : bandeau en haut si une version existe. */
    private fun onUpdateResult(available: Boolean, msg: String) {
        v<TextView>(R.id.st_update).visibility = if (available) View.VISIBLE else View.GONE
    }

    /** Tuile « Mises à jour » : vérifie tout de suite, affiche le résultat et installe directement. */
    private fun checkUpdateNow() {
        toast("Recherche de mise à jour…")
        Updater.check(this, true) { available, msg ->
            onUpdateResult(available, msg)
            toast(msg)
            if (available) Updater.install(this, ::toast)
        }
    }

    // ---------------------------------------------------------------- horloge

    private val timeRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateClock()
    }

    private fun updateClock() {
        val now = Date()
        val t = fmtTime.format(now)
        v<TextView>(R.id.home_clock).text = t
        v<TextView>(R.id.st_clock).text = t
        v<TextView>(R.id.home_date).text = fmtDate.format(now).replaceFirstChar { it.uppercase() }
    }

    // ---------------------------------------------------------------- utilitaires

    private val cache = HashMap<Int, View>()
    @Suppress("UNCHECKED_CAST")
    private fun <T : View> v(id: Int): T = cache.getOrPut(id) { findViewById<View>(id) } as T

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun tintTop(tv: TextView, color: Int) {
        val d = tv.compoundDrawables
        for (x in d) x?.mutate()?.setTint(color)
    }

    /** Retour visuel de pression : une simple mise à l'échelle (transformation GPU, coût quasi nul). */
    private fun press(view: View) {
        view.setOnTouchListener { vv, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { vv.scaleX = 0.96f; vv.scaleY = 0.96f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { vv.scaleX = 1f; vv.scaleY = 1f }
            }
            false
        }
    }

    private var lastToast: Toast? = null
    private fun toast(msg: String) {
        lastToast?.cancel()
        lastToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun mmss(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        return String.format(Locale.FRANCE, "%d:%02d", s / 60, s % 60)
    }

    companion object {
        private val BT_APP = arrayOf("Bluetooth")
        private const val OBD_OFF = "off"
        private val META_ACTIONS = arrayOf(
            "com.android.music.metachanged", "com.android.music.playstatechanged",
            "com.htc.music.metachanged", "fm.last.android.metachanged"
        )
    }
}
